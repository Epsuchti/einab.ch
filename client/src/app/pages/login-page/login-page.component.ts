import { HttpErrorResponse } from '@angular/common/http';
import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { catchError, finalize, of } from 'rxjs';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { resolveApiError } from '../../core/api-error';
import { AuthFacadeService } from '../../core/auth-facade.service';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { I18nService } from '../../core/i18n.service';
import { RecentFoodsharingIdsService } from '../../core/recent-foodsharing-ids.service';
import { isSurveyPageUrl, safeReturnUrl } from '../../core/return-url';
import { AuthResponse, DemoAccountRequestRoleEnum } from '../../api';
import { Observable } from 'rxjs';
import { SessionService } from '../../core/session.service';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { InputTextModule } from 'primeng/inputtext';
import { MessageService } from 'primeng/api';

@Component({
  selector: 'app-login-page',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink, CardModule, ButtonModule, InputTextModule],
  templateUrl: './login-page.component.html',
  styleUrl: './login-page.component.scss'
})
export class LoginPageComponent {
  readonly i18n = inject(I18nService);
  protected readonly bezirkContext = inject(BezirkContextService);
  protected readonly loading = signal(false);
  /** Foodsharing-ID the login link was sent to; switches the card to the "check your messages" state. */
  protected readonly sentTo = signal<string | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    foodsharingId: ['', [Validators.required]]
  });

  private readonly recentIds = inject(RecentFoodsharingIdsService);
  private readonly typedId = toSignal(this.form.controls.foodsharingId.valueChanges, { initialValue: '' });
  /** IDs used on this device before, narrowed to the ones matching what has been typed so far. */
  protected readonly suggestedIds = computed(() => {
    const typed = this.typedId().trim();
    return this.recentIds.recentIds().filter((id) => id !== typed && id.startsWith(typed));
  });

  private readonly authFacade = inject(AuthFacadeService);
  private readonly messageService = inject(MessageService);
  private readonly returnUrl = safeReturnUrl(inject(ActivatedRoute).snapshot.queryParamMap.get('returnUrl'));
  private readonly router = inject(Router);
  private readonly sessionService = inject(SessionService);

  /**
   * Test server only: testers and store reviewers enter this shared Foodsharing ID and get a new demo account
   * (Neuling or EinAb-Geber:in) instead of a login link, which they could not receive.
   */
  private readonly demoSharedId = toSignal(
    this.authFacade.demoSharedFoodsharingId().pipe(catchError(() => of(null))), { initialValue: null });
  /** The shared tester ID was entered: show the role choice instead of the login form. */
  protected readonly chooseDemoRole = signal(false);
  protected readonly demoLoading = signal(false);

  protected readonly DemoRole = DemoAccountRequestRoleEnum;

  createDemoAccount(role: DemoAccountRequestRoleEnum): void {
    const sharedId = this.demoSharedId();
    if (!sharedId) {
      return;
    }
    this.runDemoLogin(this.authFacade.createDemoAccount(sharedId, role));
  }

  private runDemoLogin(login: Observable<AuthResponse>): void {
    if (this.demoLoading()) {
      return;
    }
    this.demoLoading.set(true);
    login.pipe(
      finalize(() => this.demoLoading.set(false))
    ).subscribe({
      next: () => {
        this.messageService.add({ severity: 'success', summary: this.i18n.t('login.verified') });
        const returnUrl = this.returnUrl;
        void this.router.navigateByUrl(this.sessionService.session()?.privacyConsentRequired && !isSurveyPageUrl(returnUrl)
          ? this.router.createUrlTree(this.bezirkContext.route('privacy-consent'), returnUrl ? { queryParams: { returnUrl } } : {})
          : returnUrl ?? this.sessionService.primaryRoute());
      },
      error: (error) => this.messageService.add({ severity: 'error', summary: resolveApiError(error, this.i18n) })
    });
  }

  useSuggestion(id: string): void {
    this.form.controls.foodsharingId.setValue(id);
  }

  forgetSuggestion(id: string): void {
    this.recentIds.forget(id);
  }

  submit(): void {
    if (this.form.invalid || this.loading()) {
      return;
    }
    const sharedId = this.demoSharedId();
    if (sharedId && this.form.getRawValue().foodsharingId.trim() === sharedId) {
      this.chooseDemoRole.set(true);
      return;
    }
    this.loading.set(true);
    this.authFacade.requestLogin(this.bezirkContext.currentSlug(), this.form.getRawValue().foodsharingId, this.returnUrl).pipe(
      finalize(() => this.loading.set(false))
    ).subscribe({
      next: () => {
        const id = this.form.getRawValue().foodsharingId.trim();
        this.recentIds.remember(id);
        this.sentTo.set(id);
      },
      error: (error) => this.messageService.add({
        severity: 'error',
        summary: resolveApiError(error, this.i18n),
        // A deactivated account needs the support contact, so that message stays until it is closed.
        sticky: error instanceof HttpErrorResponse && error.error?.code === 'ACCOUNT_DISABLED'
      })
    });
  }
}
