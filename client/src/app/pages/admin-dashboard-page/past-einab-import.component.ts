import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { Subscription, timer } from 'rxjs';
import { switchMap } from 'rxjs/operators';

import {
  AdminImportService,
  PastEinAbImportPreview,
  PastEinAbImportRequest,
  PastEinAbImportResult,
  PastEinAbImportRow,
  UserRefreshProgress
} from '../../api';
import { resolveApiError } from '../../core/api-error';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { ErrorNotifierService } from '../../core/error-notifier.service';
import { I18nService } from '../../core/i18n.service';
import { ButtonModule } from 'primeng/button';
import { ProgressBarModule } from 'primeng/progressbar';
import { TagModule } from 'primeng/tag';
import { MessageService } from 'primeng/api';

type RowFilter = 'ALL' | 'CREATE' | 'SKIP' | 'NEEDS_ATTENTION';

const MAX_CSV_BYTES = 1_500_000;
const MAX_XLSX_BYTES = 10_000_000;
const PROGRESS_POLL_MS = 3000;

/** Two-step admin import of past EinAbs from the EinAb protocol workbook (.xlsx) or a CSV export of it. */
@Component({
  selector: 'app-past-einab-import',
  standalone: true,
  imports: [CommonModule, ButtonModule, ProgressBarModule, TagModule],
  templateUrl: './past-einab-import.component.html',
  styleUrl: './past-einab-import.component.scss'
})
export class PastEinAbImportComponent implements OnInit, OnDestroy {
  protected readonly fileName = signal('');
  readonly i18n = inject(I18nService);
  private readonly importApi = inject(AdminImportService);
  private readonly bezirkContext = inject(BezirkContextService);
  private readonly messageService = inject(MessageService);
  private readonly errorNotifier = inject(ErrorNotifierService);

  /** The upload as sent to the server: CSV text or the Base64 encoded workbook. */
  protected readonly upload = signal<PastEinAbImportRequest | null>(null);
  protected readonly busy = signal(false);
  protected readonly preview = signal<PastEinAbImportPreview | null>(null);
  protected readonly result = signal<PastEinAbImportResult | null>(null);
  protected readonly progress = signal<UserRefreshProgress | null>(null);
  protected readonly filter = signal<RowFilter>('ALL');

  protected readonly rows = computed(() => {
    const filter = this.filter();
    return (this.preview()?.rows ?? []).filter((row) => filter === 'ALL' || this.group(row) === filter);
  });
  protected readonly importableCount = computed(() => {
    const counts = this.preview()?.counts;
    return counts ? counts.willCreate + counts.teacherUpdates : 0;
  });
  protected readonly progressPercent = computed(() => {
    const progress = this.progress();
    return progress && progress.total > 0
      ? Math.round(((progress.done + progress.failed) / progress.total) * 100)
      : 0;
  });

  private pollSubscription?: Subscription;

  ngOnInit(): void {
    this.startPolling();
  }

  ngOnDestroy(): void {
    this.pollSubscription?.unsubscribe();
  }

  protected async onFileSelected(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    this.reset();
    if (!file) {
      return;
    }
    const xlsx = /\.xlsx$/i.test(file.name);
    if (file.size > (xlsx ? MAX_XLSX_BYTES : MAX_CSV_BYTES)) {
      this.toastError(this.i18n.t('import.fileTooLarge'));
      input.value = '';
      return;
    }
    this.fileName.set(file.name);
    this.upload.set(xlsx ? { xlsx: await this.toBase64(file) } : { csv: await file.text() });
  }

  protected analyse(): void {
    const upload = this.upload();
    if (this.busy() || !upload) {
      return;
    }
    this.busy.set(true);
    this.importApi
      .previewPastEinAbImport({
        bezirkSlug: this.bezirkContext.currentSlug(),
        pastEinAbImportRequest: upload
      })
      .subscribe({
        next: (preview) => {
          this.busy.set(false);
          this.result.set(null);
          this.filter.set('ALL');
          this.preview.set(preview);
        },
        error: (error) => {
          this.busy.set(false);
          this.toastError(resolveApiError(error, this.i18n));
        }
      });
  }

  protected confirm(): void {
    const upload = this.upload();
    if (this.busy() || !this.preview() || !upload) {
      return;
    }
    this.busy.set(true);
    this.importApi
      .confirmPastEinAbImport({
        bezirkSlug: this.bezirkContext.currentSlug(),
        pastEinAbImportRequest: upload
      })
      .subscribe({
        next: (result) => {
          this.busy.set(false);
          this.result.set(result);
          this.preview.set(null);
          this.upload.set(null);
          this.fileName.set('');
          this.messageService.add({ severity: 'success', summary: this.i18n.t('common.created') });
          this.startPolling();
        },
        error: (error) => {
          this.busy.set(false);
          this.toastError(resolveApiError(error, this.i18n));
        }
      });
  }

  protected reset(): void {
    this.preview.set(null);
    this.result.set(null);
    this.upload.set(null);
    this.fileName.set('');
  }

  protected group(row: PastEinAbImportRow): RowFilter {
    const status = row.status as string;
    if (status === 'CREATE') {
      return 'CREATE';
    }
    return status === 'NEEDS_ATTENTION' ? 'NEEDS_ATTENTION' : 'SKIP';
  }

  protected severity(row: PastEinAbImportRow): 'success' | 'secondary' | 'warn' {
    const group = this.group(row);
    return group === 'CREATE' ? 'success' : group === 'SKIP' ? 'secondary' : 'warn';
  }

  /** Reasons that only inform ("name corrected", "no feedback") are shown too, but the status is what counts. */
  protected reasonLabels(row: PastEinAbImportRow): string[] {
    return row.reasons.map((reason) => this.i18n.t(`import.reason.${reason}`));
  }

  protected filterCount(filter: RowFilter): number {
    const counts = this.preview()?.counts;
    if (!counts) {
      return 0;
    }
    switch (filter) {
      case 'CREATE': return counts.willCreate;
      case 'SKIP': return counts.willSkip;
      case 'NEEDS_ATTENTION': return counts.needsAttention;
      default: return counts.totalRows;
    }
  }

  protected progressText(): string {
    const progress = this.progress();
    return this.i18n
      .t('import.progress.text')
      .replace('{done}', '' + (progress ? progress.done + progress.failed : 0))
      .replace('{total}', '' + (progress?.total ?? 0));
  }

  /** Polls while a refresh batch is running; stops by itself once nothing is pending any more. */
  private startPolling(): void {
    this.pollSubscription?.unsubscribe();
    this.pollSubscription = timer(0, PROGRESS_POLL_MS)
      .pipe(switchMap(() => this.importApi.getUserRefreshProgress({ bezirkSlug: this.bezirkContext.currentSlug() })))
      .subscribe({
        next: (progress) => {
          this.progress.set(progress);
          if (!progress.active) {
            this.pollSubscription?.unsubscribe();
          }
        },
        error: (error) => {
          this.pollSubscription?.unsubscribe();
          this.errorNotifier.notify(error);
        }
      });
  }

  private async toBase64(file: File): Promise<string> {
    const bytes = new Uint8Array(await file.arrayBuffer());
    let binary = '';
    for (let i = 0; i < bytes.length; i += 0x8000) {
      binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    }
    return btoa(binary);
  }

  private toastError(detail: string): void {
    this.messageService.add({ severity: 'error', summary: this.i18n.t('common.error'), detail });
  }
}
