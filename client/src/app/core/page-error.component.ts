import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { ButtonModule } from 'primeng/button';

import { resolveApiError } from './api-error';
import { I18nService } from './i18n.service';

type PageErrorKind = 'offline' | 'unreachable' | 'server' | 'notFound' | 'denied' | 'other';

/** Full-page message for a page that could not be loaded, with a way to try again. */
@Component({
  selector: 'app-page-error',
  standalone: true,
  imports: [ButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page-error" role="alert">
      <span class="page-error__icon" aria-hidden="true"><i [class]="'pi ' + icon()"></i></span>
      <h1>{{ i18n.t('pageError.' + kind() + '.title') }}</h1>
      <p class="page-error__text">{{ i18n.t('pageError.' + kind() + '.text') }}</p>
      @if (detail()) {
        <p class="page-error__detail">{{ detail() }}</p>
      }
      <div class="page-error__actions">
        <button pButton type="button" icon="pi pi-refresh" [label]="i18n.t('pageError.retry')" (click)="retry.emit()"></button>
        <a pButton severity="secondary" [outlined]="true" icon="pi pi-home" href="/" [label]="i18n.t('pageError.home')"></a>
      </div>
      @if (kind() === 'server' || kind() === 'other') {
        <p class="page-error__support">
          {{ i18n.t('pageError.supportHint') }}
          <a href="https://foodsharing.de/user/420344/profile" target="_blank" rel="noopener">{{ i18n.t('footer.support') }}</a>
        </p>
      }
    </section>
  `,
  styles: `
    .page-error {
      display: grid;
      justify-items: center;
      gap: 0.85rem;
      max-width: 36rem;
      margin: clamp(2rem, 8vh, 5rem) auto;
      padding: 2rem 1.5rem;
      border: 1px solid #efc7c4;
      border-radius: var(--app-radius-sm, 0.75rem);
      background: #fff;
      box-shadow: 0 10px 30px rgb(20 50 30 / 0.06);
      text-align: center;
    }

    .page-error__icon {
      display: grid;
      place-items: center;
      width: 4rem;
      height: 4rem;
      border-radius: 50%;
      background: #fff3f2;
      color: #b4372f;
      font-size: 1.75rem;
    }

    .page-error__icon .pi {
      font-size: 1.75rem;
    }

    h1 {
      margin: 0.25rem 0 0;
      font-size: clamp(1.35rem, 3vw, 1.75rem);
      line-height: 1.25;
    }

    p {
      margin: 0;
    }

    .page-error__text {
      color: #4a5a50;
      line-height: 1.5;
    }

    .page-error__detail {
      padding: 0.3rem 0.75rem;
      border-radius: 999px;
      background: #f3f5f3;
      color: #5f6d64;
      font-size: 0.9rem;
    }

    .page-error__actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: center;
      gap: 0.75rem;
      margin-top: 0.5rem;
    }

    .page-error__support {
      color: #5f6d64;
      font-size: 0.9rem;
      line-height: 1.5;
    }
  `
})
export class PageErrorComponent {
  readonly i18n = inject(I18nService);
  readonly error = input<unknown>();
  readonly retry = output<void>();

  readonly kind = computed<PageErrorKind>(() => {
    const error = this.error();
    if (!(error instanceof HttpErrorResponse)) {
      return 'other';
    }
    if (error.status === 0) {
      return typeof navigator !== 'undefined' && !navigator.onLine ? 'offline' : 'unreachable';
    }
    if (error.status === 404) {
      return 'notFound';
    }
    if (error.status === 403) {
      return 'denied';
    }
    if (error.status >= 500) {
      const code = typeof error.error === 'object' && error.error ? (error.error as { code?: unknown }).code : undefined;
      return [502, 503, 504].includes(error.status) && !code ? 'unreachable' : 'server';
    }
    return 'other';
  });

  readonly icon = computed(() => ({
    offline: 'pi-wifi',
    unreachable: 'pi-server',
    server: 'pi-exclamation-triangle',
    notFound: 'pi-search',
    denied: 'pi-lock',
    other: 'pi-exclamation-circle'
  })[this.kind()]);

  /** The concrete server message, when it says more than the general text above it. */
  readonly detail = computed(() => {
    const kind = this.kind();
    if (kind === 'offline' || kind === 'unreachable') {
      return '';
    }
    const message = resolveApiError(this.error(), this.i18n);
    return message === this.i18n.t('error.UNEXPECTED_ERROR') ? '' : message;
  });
}
