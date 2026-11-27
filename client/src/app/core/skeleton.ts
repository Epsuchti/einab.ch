import { ChangeDetectionStrategy, Component, Directive, Injectable, OnDestroy, OnInit, computed, inject, input, signal } from '@angular/core';

import { I18nService } from './i18n.service';

/** How long skeletons may stay on screen before the banana loader keeps the user company. */
export const LONG_WAIT_MS = 3000;

/**
 * Counts the skeleton regions on screen. When any skeleton has been visible for longer than {@link LONG_WAIT_MS},
 * `longWait` turns true until every skeleton is gone again (the app shell then shows the banana loader).
 */
@Injectable({ providedIn: 'root' })
export class SkeletonActivityService {
  private readonly visible = signal(0);
  private readonly waitedLong = signal(false);
  private timer: ReturnType<typeof setTimeout> | null = null;

  readonly active = computed(() => this.visible() > 0);
  readonly longWait = computed(() => this.active() && this.waitedLong());

  register(): void {
    this.visible.update((count) => count + 1);
    if (this.timer === null && !this.waitedLong()) {
      this.timer = setTimeout(() => {
        this.timer = null;
        if (this.visible() > 0) {
          this.waitedLong.set(true);
        }
      }, LONG_WAIT_MS);
    }
  }

  unregister(): void {
    // Wait a frame: a region that is swapped for another skeleton (e.g. a tab change) keeps the wait running.
    queueMicrotask(() => {
      this.visible.update((count) => Math.max(0, count - 1));
      if (this.visible() === 0) {
        if (this.timer !== null) {
          clearTimeout(this.timer);
          this.timer = null;
        }
        this.waitedLong.set(false);
      }
    });
  }
}

/**
 * Marks the container of a skeleton: announces "Loading" to screen readers, fades in after a short delay
 * (so fast responses do not flash) and lets the banana loader know that the user is waiting.
 */
@Directive({
  selector: '[appSkeletonRegion]',
  standalone: true,
  host: {
    class: 'skeleton-region',
    role: 'status',
    'aria-live': 'polite',
    'aria-busy': 'true',
    '[attr.aria-label]': 'i18n.t("common.loading")'
  }
})
export class SkeletonRegionDirective implements OnInit, OnDestroy {
  protected readonly i18n = inject(I18nService);
  private readonly activity = inject(SkeletonActivityService);

  ngOnInit(): void {
    this.activity.register();
  }

  ngOnDestroy(): void {
    this.activity.unregister();
  }
}

export type SkeletonShape = 'text' | 'title' | 'circle' | 'pill' | 'block' | 'button' | 'tag';

/** One grey placeholder shape that shimmers. Combine several of them to mirror the layout that is loading. */
@Component({
  selector: 'app-skeleton',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '',
  host: {
    class: 'skeleton',
    'aria-hidden': 'true',
    '[class]': '"skeleton skeleton--" + shape()',
    '[style.width]': 'width()',
    '[style.height]': 'height()'
  }
})
export class SkeletonComponent {
  readonly shape = input<SkeletonShape>('text');
  /** Any CSS width; text lines default to the full width of their container. */
  readonly width = input<string | null>(null);
  readonly height = input<string | null>(null);
}

export interface SkeletonColumn {
  /** Real column header, so the table already reads right while it loads. */
  label?: string;
  /** toggle: expand chevron, action: the "…" menu, tag: a status pill, text: a line of text. */
  kind?: 'text' | 'toggle' | 'action' | 'tag' | 'button';
}

/** Rotating line widths, so the placeholder rows look like real, uneven content. */
const WIDTHS = ['78%', '56%', '88%', '64%', '47%', '72%'];

/**
 * A data table that is still loading: the real column headers with shimmering cells on desktop,
 * and stacked cards like `.einab-card` / `.admin-user-card` on phones (≤ 768px), as the real lists do.
 */
@Component({
  selector: 'app-skeleton-table',
  standalone: true,
  imports: [SkeletonComponent, SkeletonRegionDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div appSkeletonRegion class="skeleton-table" [class.skeleton-table--keep-table]="!phoneCards()">
      <table class="skeleton-table__table">
        <thead>
          <tr>
            @for (column of columns(); track $index) {
              <th [class.skeleton-table__narrow]="column.kind === 'toggle' || column.kind === 'action'">
                @if (column.label) { {{ column.label }} }
              </th>
            }
          </tr>
        </thead>
        <tbody>
          @for (row of rowIndexes(); track row) {
            <tr>
              @for (column of columns(); track $index; let c = $index) {
                <td [class.skeleton-table__narrow]="column.kind === 'toggle' || column.kind === 'action'">
                  @switch (column.kind) {
                    @case ('toggle') { <app-skeleton shape="block" width="1.25rem" height="1.25rem" /> }
                    @case ('action') { <app-skeleton shape="circle" width="1.6rem" height="1.6rem" /> }
                    @case ('tag') { <app-skeleton shape="pill" width="4.5rem" height="1.4rem" /> }
                    @case ('button') { <app-skeleton shape="button" width="5.5rem" height="2rem" /> }
                    @default { <app-skeleton [width]="width(row, c)" /> }
                  }
                </td>
              }
            </tr>
          }
        </tbody>
      </table>

      <div class="skeleton-table__cards">
        @for (row of rowIndexes(); track row) {
          <article class="skeleton-card">
            <div class="skeleton-card__topline">
              @if (avatar()) { <app-skeleton shape="circle" width="2.75rem" height="2.75rem" /> }
              <div class="skeleton-card__title">
                <app-skeleton shape="title" [width]="width(row, 0, 30)" />
                <app-skeleton [width]="width(row, 1, 15)" />
              </div>
              <app-skeleton shape="circle" width="2rem" height="2rem" />
            </div>
            <div class="skeleton-card__meta">
              <app-skeleton shape="pill" width="6.5rem" height="1.1rem" />
              <app-skeleton shape="pill" width="5rem" height="1.1rem" />
            </div>
          </article>
        }
      </div>
    </div>
  `,
  styles: `
    :host { display: block; }

    .skeleton-table__table {
      width: 100%;
      border-collapse: collapse;
      table-layout: auto;
    }

    th {
      padding: 0.75rem 1rem;
      border-bottom: 1px solid var(--app-border);
      background: var(--app-surface-strong);
      color: var(--app-text-muted);
      font-weight: 600;
      text-align: left;
      white-space: nowrap;
    }

    td {
      height: 3.4rem;
      padding: 0.75rem 1rem;
      border-bottom: 1px solid #eaf0eb;
      vertical-align: middle;
    }

    .skeleton-table__narrow { width: 3rem; }

    .skeleton-table__cards { display: none; }

    .skeleton-card {
      display: grid;
      gap: 0.75rem;
      padding: 0.9rem;
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius-sm);
      background: #fff;
    }

    .skeleton-card__topline {
      display: flex;
      align-items: flex-start;
      gap: 0.75rem;
    }

    .skeleton-card__title {
      display: grid;
      flex: 1 1 auto;
      gap: 0.45rem;
      min-width: 0;
    }

    .skeleton-card__meta {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem 0.75rem;
    }

    @media (max-width: 768px) {
      .skeleton-table:not(.skeleton-table--keep-table) .skeleton-table__table { display: none; }

      .skeleton-table:not(.skeleton-table--keep-table) .skeleton-table__cards {
        display: grid;
        gap: 0.75rem;
      }
    }
  `
})
export class SkeletonTableComponent {
  readonly columns = input.required<SkeletonColumn[]>();
  readonly rows = input(5);
  /** Phone cards start with a round avatar (people lists). */
  readonly avatar = input(false);
  /** Show stacked cards instead of the table on phones, as the real list does. */
  readonly phoneCards = input(true);

  protected readonly rowIndexes = computed(() => Array.from({ length: this.rows() }, (_, index) => index));

  protected width(row: number, column: number, shrink = 0): string {
    const base = parseInt(WIDTHS[(row * 3 + column) % WIDTHS.length], 10);
    return `${Math.max(25, base - shrink)}%`;
  }
}

/** Everything a page needs to build its own skeleton. */
export const SKELETON = [SkeletonComponent, SkeletonRegionDirective, SkeletonTableComponent] as const;
