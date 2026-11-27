import { CommonModule } from '@angular/common';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { CheckboxModule } from 'primeng/checkbox';
import { InputTextModule } from 'primeng/inputtext';
import { AuthService, UserService } from '../../api';
import { resolveApiError } from '../../core/api-error';
import { AuthFacadeService } from '../../core/auth-facade.service';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { MarkdownViewComponent } from '../../core/markdown-view.component';
import { I18nService } from '../../core/i18n.service';
import { isValidPhoneNumber } from '../../core/phone-number';
import { SessionService } from '../../core/session.service';
import { safeReturnUrl } from '../../core/return-url';
import { SkeletonComponent, SkeletonRegionDirective } from '../../core/skeleton';

@Component({
  selector: 'app-privacy-consent-page',
  standalone: true,
  imports: [SkeletonComponent, SkeletonRegionDirective, CommonModule, FormsModule, RouterLink, ButtonModule, CardModule, CheckboxModule, InputTextModule, MarkdownViewComponent],
  templateUrl: './privacy-consent-page.component.html',
  styleUrl: './privacy-consent-page.component.scss'
})
export class PrivacyConsentPageComponent {
  readonly i18n = inject(I18nService);
  protected readonly accepted = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly phoneNumber = signal('');
  protected readonly step = signal<'consent' | 'phone' | 'info'>('consent');
  /** Markdown of the home Bezirk's info page (third step); null while it loads. */
  protected readonly bezirkInfo = signal<string | null>(null);
  protected readonly bezirkName = signal('');
  private readonly authFacade = inject(AuthFacadeService);
  private readonly authApi = inject(AuthService);
  private readonly bezirkContext = inject(BezirkContextService);
  private readonly userApi = inject(UserService);
  private readonly sessionService = inject(SessionService);
  private readonly router = inject(Router);
  private readonly returnUrl = safeReturnUrl(inject(ActivatedRoute).snapshot.queryParamMap.get('returnUrl'));
  /** Fixed on page load: saving the number updates the session, and the field must not vanish mid-save. */
  protected readonly phoneRequired = this.sessionService.session()?.phoneNumberRequired === true;
  /** The consent is already given (e.g. the page was reloaded during the info step): only the info step is left. */
  protected readonly consentRequired = this.sessionService.session()?.privacyConsentRequired === true;
  /** The home Bezirk shows its info page as last step. Fixed on load like the phone step. */
  protected readonly infoRequired = this.sessionService.session()?.bezirkInfoRequired === true;
  /** The steps shown in the progress bar; a single step needs no bar. */
  protected readonly steps = [
    ...(this.consentRequired ? [{ id: 'consent' as const, label: 'privacy.step.consent' }] : []),
    ...(this.consentRequired && this.phoneRequired ? [{ id: 'phone' as const, label: 'privacy.step.phone' }] : []),
    ...(this.infoRequired ? [{ id: 'info' as const, label: 'privacy.step.info' }] : [])
  ];
  protected readonly stepIndex = computed(() => this.steps.findIndex((entry) => entry.id === this.step()));
  protected readonly phoneInvalid = computed(() => this.phoneNumber().trim().length > 0 && !isValidPhoneNumber(this.phoneNumber()));
  protected readonly phoneValid = computed(() => isValidPhoneNumber(this.phoneNumber()) && this.phoneNumber().trim().length <= 50);

  constructor() {
    if (!this.consentRequired && !this.infoRequired) {
      // Nothing left to do (e.g. opened from the browser history): go on to where the user wanted to go.
      void this.router.navigateByUrl(this.returnUrl ?? this.sessionService.primaryRoute());
    } else if (!this.consentRequired) {
      this.showInfoStep();
    }
    // The Bezirk info is written per app language: switching the language on the info step loads that text.
    effect(() => {
      this.i18n.language();
      untracked(() => {
        if (this.step() === 'info' && this.bezirkInfo() !== null) {
          this.loadBezirkInfo();
        }
      });
    });
  }

  /** The consent step only advances to the phone step; the consent is saved with the phone number. */
  next(): void {
    if (!this.accepted() || this.saving()) return;
    if (this.phoneRequired) {
      this.error.set('');
      this.step.set('phone');
    } else {
      this.accept();
    }
  }

  back(): void {
    if (this.saving()) return;
    this.error.set('');
    this.step.set('consent');
  }

  /** Last step: the user read the Bezirk info. Afterwards the app opens up. */
  finishInfo(): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    this.authApi.acknowledgeBezirkInfo().subscribe({
      next: (info) => {
        this.sessionService.applySessionInfo(info);
        this.done();
      },
      error: (error) => { this.saving.set(false); this.error.set(resolveApiError(error, this.i18n)); }
    });
  }

  accept(): void {
    if (!this.accepted() || (this.phoneRequired && !this.phoneValid()) || this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    if (!this.phoneRequired) {
      this.acceptConsent();
      return;
    }
    this.userApi.updateMyProfile({ updateMyProfileRequest: { phoneNumber: this.phoneNumber().trim() } }).subscribe({
      next: () => {
        this.sessionService.markPhoneNumberProvided();
        this.acceptConsent();
      },
      error: (error) => { this.saving.set(false); this.error.set(resolveApiError(error, this.i18n)); }
    });
  }

  private acceptConsent(): void {
    this.authFacade.acceptPrivacyConsent('2026-10-25').subscribe({
      next: (response) => {
        if (response.bezirkInfoRequired) {
          this.saving.set(false);
          this.showInfoStep();
        } else {
          this.done();
        }
      },
      error: () => { this.saving.set(false); this.error.set(this.i18n.t('privacy.error')); }
    });
  }

  private showInfoStep(): void {
    this.error.set('');
    this.step.set('info');
    window.scrollTo({ top: 0 });
    this.loadBezirkInfo();
  }

  private loadBezirkInfo(): void {
    const bezirk = this.sessionService.session()?.bezirk;
    const slug = bezirk?.slug ?? this.bezirkContext.selectedBezirk()?.slug;
    this.bezirkName.set(bezirk?.name ?? this.bezirkContext.selectedBezirk()?.name ?? '');
    if (!slug) {
      this.bezirkInfo.set('');
      return;
    }
    this.userApi.getBezirkInfo({ bezirkSlug: slug, language: this.i18n.language() }).subscribe({
      next: (info) => { this.bezirkName.set(info.bezirkName); this.bezirkInfo.set(info.markdown); },
      error: (error) => { this.bezirkInfo.set(''); this.error.set(resolveApiError(error, this.i18n)); }
    });
  }

  private done(): void {
    void this.router.navigateByUrl(this.returnUrl ?? this.sessionService.primaryRoute());
  }
}
