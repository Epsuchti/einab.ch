import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { ProgressSpinnerModule } from 'primeng/progressspinner';

import { I18nService } from './i18n.service';

/** Centered spinner shown while a view waits for its backend data. Use it instead of empty content. */
@Component({
  selector: 'app-loading-indicator',
  standalone: true,
  imports: [ProgressSpinnerModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="loading-box" [class.loading-box--compact]="compact()" role="status" aria-live="polite">
      <p-progressSpinner
        strokeWidth="4"
        [style]="{ width: compact() ? '1.75rem' : '2.5rem', height: compact() ? '1.75rem' : '2.5rem' }"
        [ariaLabel]="i18n.t('common.loading')"
      ></p-progressSpinner>
      @if (showLabel()) {
        <span>{{ i18n.t('common.loading') }}</span>
      }
    </div>
  `,
  styles: `
    .loading-box {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 0.75rem;
    }

    .loading-box--compact {
      min-height: 0;
      padding: 1rem;
      border: 0;
      background: transparent;
    }
  `
})
export class LoadingIndicatorComponent {
  protected readonly i18n = inject(I18nService);
  readonly compact = input(false);
  readonly showLabel = input(true);
}
