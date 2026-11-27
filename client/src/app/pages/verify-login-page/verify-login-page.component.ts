import { CommonModule } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';

import { resolveApiError } from '../../core/api-error';
import { AuthFacadeService } from '../../core/auth-facade.service';
import { I18nService } from '../../core/i18n.service';
import { SessionService } from '../../core/session.service';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { isSurveyPageUrl, safeReturnUrl } from '../../core/return-url';
import { CardModule } from 'primeng/card';
import { MessageService } from 'primeng/api';
import { SkeletonComponent, SkeletonRegionDirective } from '../../core/skeleton';

@Component({
  selector: 'app-verify-login-page',
  standalone: true,
  imports: [SkeletonComponent, SkeletonRegionDirective, CommonModule, RouterLink, ButtonModule, CardModule],
  templateUrl: './verify-login-page.component.html'
})
export class VerifyLoginPageComponent implements OnInit {
  readonly i18n = inject(I18nService);
  protected readonly loading = signal(true);
  protected readonly error = signal('');

  private readonly route = inject(ActivatedRoute);
  private readonly authFacade = inject(AuthFacadeService);
  private readonly sessionService = inject(SessionService);
  private readonly router = inject(Router);
  private readonly messageService = inject(MessageService);
  protected readonly bezirkContext = inject(BezirkContextService);

  ngOnInit(): void {
    const token = this.route.snapshot.queryParamMap.get('token');
    if (!token) {
      this.loading.set(false);
      this.error.set(this.i18n.t('auth.missingToken'));
      return;
    }

    const returnUrl = safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl'));
    this.authFacade.verifyToken(token).subscribe({
      next: () => {
        this.messageService.add({ severity: 'success', summary: this.i18n.t('login.verified') });
        // Coming from a survey link, go straight to the survey; the consent is asked later on normal app use.
        this.router.navigateByUrl(this.sessionService.session()?.privacyConsentRequired && !isSurveyPageUrl(returnUrl)
          ? this.router.createUrlTree(this.bezirkContext.route('privacy-consent'), returnUrl ? { queryParams: { returnUrl } } : {})
          : returnUrl ?? this.sessionService.primaryRoute());
      },
      error: (error) => {
        this.error.set(resolveApiError(error, this.i18n));
        this.loading.set(false);
      }
    });
  }
}
