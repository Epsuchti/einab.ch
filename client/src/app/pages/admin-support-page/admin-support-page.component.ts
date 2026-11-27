import { Component, OnInit, inject, input, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { ButtonModule } from 'primeng/button';
import { PaginatorModule, PaginatorState } from 'primeng/paginator';
import { TagModule } from 'primeng/tag';
import { MessageService } from 'primeng/api';

import { SupportClientPlatform, SupportRequestCategory, SupportRequestResponse, SupportRequestStatus, SupportService } from '../../api';
import { resolveApiError } from '../../core/api-error';
import { I18nService } from '../../core/i18n.service';
import { SupportBadgeService } from '../../core/support-badge.service';
import { ZurichDateTimePipe } from '../../core/zurich-date-time.pipe';
import { SkeletonComponent, SkeletonRegionDirective } from '../../core/skeleton';

type StatusFilter = 'OPEN' | 'DONE' | 'ALL';

/** Admin tab "Support": questions, problems and ideas sent from the help page, with the release and device the sender used. */
@Component({
  selector: 'app-admin-support-page',
  standalone: true,
  imports: [SkeletonComponent, SkeletonRegionDirective, ButtonModule, PaginatorModule, TagModule, ZurichDateTimePipe],
  templateUrl: './admin-support-page.component.html',
  styleUrl: './admin-support-page.component.scss'
})
export class AdminSupportPageComponent implements OnInit {
  /** Shown as a tab inside the Admin page: no own page frame or heading. */
  readonly embedded = input(false);
  readonly i18n = inject(I18nService);
  private readonly supportApi = inject(SupportService);
  private readonly messageService = inject(MessageService);
  private readonly supportBadge = inject(SupportBadgeService);

  protected readonly pageSize = 20;
  protected readonly filters: { value: StatusFilter; label: string }[] = [
    { value: 'OPEN', label: 'adminSupport.filterOpen' },
    { value: 'DONE', label: 'adminSupport.filterDone' },
    { value: 'ALL', label: 'adminSupport.filterAll' }
  ];
  protected readonly filter = signal<StatusFilter>('OPEN');
  protected readonly page = signal(0);
  protected readonly requests = signal<SupportRequestResponse[]>([]);
  protected readonly totalElements = signal(0);
  protected readonly openCount = signal(0);
  protected readonly loading = signal(true);
  protected readonly updatingId = signal<string | null>(null);
  protected readonly Status = SupportRequestStatus;

  ngOnInit(): void {
    this.load();
  }

  setFilter(value: StatusFilter): void {
    this.filter.set(value);
    this.page.set(0);
    this.load();
  }

  onPageChange(event: PaginatorState): void {
    this.page.set(event.page ?? 0);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    const filter = this.filter();
    this.supportApi.listSupportRequests({
      status: filter === 'ALL' ? undefined : (filter as SupportRequestStatus),
      page: this.page(),
      size: this.pageSize
    }).pipe(finalize(() => this.loading.set(false))).subscribe({
      next: (response) => {
        this.requests.set(response.requests);
        this.totalElements.set(response.totalElements);
        this.openCount.set(response.openCount);
        this.supportBadge.set(response.openCount);
      },
      error: (error) => this.toastError(error)
    });
  }

  setStatus(request: SupportRequestResponse, status: SupportRequestStatus): void {
    this.updatingId.set(request.id);
    this.supportApi.updateSupportRequestStatus({ supportRequestId: request.id, supportRequestStatusUpdateRequest: { status } })
      .pipe(finalize(() => this.updatingId.set(null)))
      .subscribe({
        next: (updated) => {
          const keep = this.filter() === 'ALL' || this.filter() === updated.status;
          this.requests.update((list) => keep ? list.map((entry) => entry.id === updated.id ? updated : entry) : list.filter((entry) => entry.id !== updated.id));
          if (!keep) {
            this.totalElements.update((count) => Math.max(0, count - 1));
          }
          this.openCount.update((count) => count + (status === SupportRequestStatus.Open ? 1 : -1));
          this.supportBadge.set(this.openCount());
        },
        error: (error) => this.toastError(error)
      });
  }

  categoryLabel(category: SupportRequestCategory): string {
    return this.i18n.t(category === SupportRequestCategory.Problem ? 'help.categoryProblem' : category === SupportRequestCategory.Idea ? 'help.categoryIdea' : 'help.categoryQuestion');
  }

  categorySeverity(category: SupportRequestCategory): 'danger' | 'info' | 'secondary' {
    return category === SupportRequestCategory.Problem ? 'danger' : category === SupportRequestCategory.Idea ? 'info' : 'secondary';
  }

  platformLabel(platform: SupportClientPlatform | undefined): string {
    switch (platform) {
      case SupportClientPlatform.AndroidApp:
        return this.i18n.t('adminSupport.platformAndroid');
      case SupportClientPlatform.IosApp:
        return this.i18n.t('adminSupport.platformIos');
      case SupportClientPlatform.Pwa:
        return this.i18n.t('adminSupport.platformPwa');
      case SupportClientPlatform.Web:
        return this.i18n.t('adminSupport.platformWeb');
      default:
        return '-';
    }
  }

  profileUrl(request: SupportRequestResponse): string {
    return `https://foodsharing.network/user/${encodeURIComponent(request.foodsharingId)}/profile`;
  }

  private toastError(error: unknown): void {
    this.messageService.add({ severity: 'error', summary: this.i18n.t('common.error'), detail: resolveApiError(error, this.i18n) });
  }
}
