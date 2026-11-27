import { Component, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';

import { UserService } from '../../api';
import { resolveApiError } from '../../core/api-error';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { I18nService } from '../../core/i18n.service';
import { MarkdownViewComponent } from '../../core/markdown-view.component';
import { SkeletonComponent, SkeletonRegionDirective } from '../../core/skeleton';

/** The Bezirk's info page (Markdown from the Bezirk settings), the same text as the onboarding step. */
@Component({
  selector: 'app-bezirk-info-page',
  standalone: true,
  imports: [SkeletonComponent, SkeletonRegionDirective, RouterLink, ButtonModule, CardModule, MarkdownViewComponent],
  template: `
    <section class="page-shell page-shell--narrow">
      <p-card class="page-card--flat">
        <ng-template pTemplate="title">
          <span><i class="pi pi-map-marker title-icon" aria-hidden="true"></i>{{ name() ? i18n.t('bezirkInfo.titleFor').replace('{0}', name()) : i18n.t('bezirkInfo.title') }}</span>
        </ng-template>
        @if (error()) {
          <p class="field-error" role="alert">{{ error() }}</p>
        } @else if (markdown() === null) {
          <div class="skeleton-stack" appSkeletonRegion>
          <app-skeleton shape="title" width="45%" height="1.2rem" />
          <app-skeleton width="96%" />
          <app-skeleton width="90%" />
          <app-skeleton width="72%" />
          <app-skeleton shape="title" width="35%" style="margin-top: 0.6rem" />
          <app-skeleton width="94%" />
          <app-skeleton width="60%" />
        </div>
        } @else if (markdown()) {
          <app-markdown-view [markdown]="markdown()" />
        } @else {
          <p class="form-inline-note">{{ i18n.t('bezirkInfo.empty') }}</p>
        }
        <div class="bezirk-info-actions">
          <a pButton severity="secondary" [outlined]="true" icon="pi pi-question-circle" [routerLink]="bezirkContext.route('help')" [label]="i18n.t('bezirkInfo.toHelp')"></a>
        </div>
      </p-card>
    </section>
  `,
  styles: `
    .bezirk-info-actions {
      display: flex;
      justify-content: flex-end;
      margin-top: 1.5rem;
    }
    @media (max-width: 599px) {
      .bezirk-info-actions a { width: 100%; }
    }
  `
})
export class BezirkInfoPageComponent {
  readonly i18n = inject(I18nService);
  protected readonly bezirkContext = inject(BezirkContextService);
  protected readonly markdown = signal<string | null>(null);
  protected readonly name = signal(this.bezirkContext.selectedBezirk()?.name ?? '');
  protected readonly error = signal('');

  private readonly userApi = inject(UserService);

  constructor() {
    // The text is written per app language (German as fallback): load it again when the language changes.
    effect(() => {
      const language = this.i18n.language();
      untracked(() => this.userApi.getBezirkInfo({ bezirkSlug: this.bezirkContext.currentSlug(), language }).subscribe({
        next: (info) => { this.name.set(info.bezirkName); this.markdown.set(info.markdown); },
        error: (error) => this.error.set(resolveApiError(error, this.i18n))
      }));
    });
  }
}
