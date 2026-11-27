import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DatePickerModule } from 'primeng/datepicker';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TagModule } from 'primeng/tag';
import { TextareaModule } from 'primeng/textarea';

import { AdminBezirkSummary, AdminInfoNoteResponse, AdminService, InfoNoteRequest, InfoNoteService, InfoNoteSeverity } from '../../api';
import { resolveApiError } from '../../core/api-error';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { confirmAction } from '../../core/confirm-action';
import { I18nService } from '../../core/i18n.service';
import { InfoNotesService } from '../../core/info-notes.service';
import { ZurichDateTimePipe } from '../../core/zurich-date-time.pipe';
import { SkeletonComponent, SkeletonRegionDirective } from '../../core/skeleton';

type NoteState = 'active' | 'scheduled' | 'ended';

/** Value of the "Shown in" select for a note on the whole site. */
const SITE_WIDE = '';
const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * Admin tab "Infos": banners for the whole site (updates, maintenance) or for one Bezirk, each with a start and
 * an end. Everyone sees running notes when they open the page until they dismiss them in their browser.
 */
@Component({
  selector: 'app-admin-info-notes',
  standalone: true,
  imports: [SkeletonComponent, SkeletonRegionDirective, FormsModule, ButtonModule, DatePickerModule, DialogModule, InputTextModule, SelectModule, TagModule, TextareaModule, ZurichDateTimePipe],
  templateUrl: './admin-info-notes.component.html',
  styleUrl: './admin-info-notes.component.scss'
})
export class AdminInfoNotesComponent implements OnInit {
  readonly i18n = inject(I18nService);
  private readonly infoNoteApi = inject(InfoNoteService);
  private readonly adminApi = inject(AdminService);
  private readonly bezirkContext = inject(BezirkContextService);
  private readonly infoNotes = inject(InfoNotesService);
  private readonly messageService = inject(MessageService);
  private readonly confirmationService = inject(ConfirmationService);

  protected readonly Severity = InfoNoteSeverity;
  protected readonly maxMessageLength = 2000;
  protected readonly notes = signal<AdminInfoNoteResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly bezirke = signal<AdminBezirkSummary[]>([]);
  protected readonly busyId = signal<string | null>(null);

  protected readonly dialogVisible = signal(false);
  protected readonly editingId = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly submitted = signal(false);
  protected readonly target = signal<string>(SITE_WIDE);
  protected readonly severity = signal<InfoNoteSeverity>(InfoNoteSeverity.Info);
  protected readonly title = signal('');
  protected readonly message = signal('');
  protected readonly startsAt = signal<Date | null>(null);
  protected readonly endsAt = signal<Date | null>(null);

  /** Whole site first, then the active Bezirke (plus the edited note's Bezirk if it was disabled since). */
  protected readonly targetOptions = computed(() => {
    const keep = this.editingId() ? this.target() : null;
    return [
      { label: this.i18n.t('adminInfoNotes.siteWide'), value: SITE_WIDE },
      ...this.bezirke()
        .filter((bezirk) => bezirk.active || bezirk.slug === keep)
        .map((bezirk) => ({ label: bezirk.name, value: bezirk.slug }))
    ];
  });

  /** Running notes first, then scheduled ones, then ended ones (the server sorts by end, latest first). */
  protected readonly sortedNotes = computed(() => {
    const order: Record<NoteState, number> = { active: 0, scheduled: 1, ended: 2 };
    return [...this.notes()].sort((a, b) => order[this.state(a)] - order[this.state(b)]);
  });

  protected readonly messageMissing = computed(() => !this.message().trim());
  protected readonly datesMissing = computed(() => !this.startsAt() || !this.endsAt());
  protected readonly endBeforeStart = computed(() => {
    const start = this.startsAt();
    const end = this.endsAt();
    return !!start && !!end && end.getTime() <= start.getTime();
  });
  protected readonly endInPast = computed(() => {
    const end = this.endsAt();
    return !!end && !this.endBeforeStart() && end.getTime() <= Date.now();
  });
  protected readonly formInvalid = computed(() => this.messageMissing() || this.datesMissing() || this.endBeforeStart());

  ngOnInit(): void {
    this.load();
    this.adminApi.getAdminBezirke().subscribe({
      next: (response) => this.bezirke.set(response.bezirke),
      error: (error) => this.toastError(error)
    });
  }

  load(): void {
    this.loading.set(true);
    this.infoNoteApi.listAdminInfoNotes().pipe(finalize(() => this.loading.set(false))).subscribe({
      next: (response) => this.notes.set(response.notes),
      error: (error) => this.toastError(error)
    });
  }

  state(note: AdminInfoNoteResponse): NoteState {
    const now = Date.now();
    if (new Date(note.endsAt).getTime() <= now) {
      return 'ended';
    }
    return new Date(note.startsAt).getTime() > now ? 'scheduled' : 'active';
  }

  stateSeverity(state: NoteState): 'success' | 'info' | 'secondary' {
    return state === 'active' ? 'success' : state === 'scheduled' ? 'info' : 'secondary';
  }

  /** New notes start now, run for a week and go to the current Bezirk, so nothing reaches every Bezirk by accident. */
  openCreate(): void {
    const start = new Date();
    start.setSeconds(0, 0);
    this.editingId.set(null);
    this.target.set(this.bezirkContext.selectedBezirk()?.slug ?? SITE_WIDE);
    this.severity.set(InfoNoteSeverity.Info);
    this.title.set('');
    this.message.set('');
    this.startsAt.set(start);
    this.endsAt.set(new Date(start.getTime() + 7 * DAY_MS));
    this.submitted.set(false);
    this.dialogVisible.set(true);
  }

  openEdit(note: AdminInfoNoteResponse): void {
    this.editingId.set(note.id);
    this.target.set(note.bezirkSlug ?? SITE_WIDE);
    this.severity.set(note.severity);
    this.title.set(note.title ?? '');
    this.message.set(note.message);
    this.startsAt.set(new Date(note.startsAt));
    this.endsAt.set(new Date(note.endsAt));
    this.submitted.set(false);
    this.dialogVisible.set(true);
  }

  /** "End now" for a running note: keeps it in the list as ended instead of deleting it. */
  endNow(note: AdminInfoNoteResponse): void {
    this.busyId.set(note.id);
    const request: InfoNoteRequest = {
      bezirkSlug: note.bezirkSlug ?? null,
      title: note.title ?? null,
      message: note.message,
      severity: note.severity,
      startsAt: note.startsAt,
      endsAt: new Date().toISOString()
    };
    this.infoNoteApi.updateAdminInfoNote({ infoNoteId: note.id, infoNoteRequest: request })
      .pipe(finalize(() => this.busyId.set(null)))
      .subscribe({
        next: (updated) => this.replace(updated),
        error: (error) => this.toastError(error)
      });
  }

  save(): void {
    this.submitted.set(true);
    if (this.formInvalid()) {
      return;
    }
    const request: InfoNoteRequest = {
      bezirkSlug: this.target() || null,
      title: this.title().trim() || null,
      message: this.message().trim(),
      severity: this.severity(),
      startsAt: this.startsAt()!.toISOString(),
      endsAt: this.endsAt()!.toISOString()
    };
    const id = this.editingId();
    const call = id
      ? this.infoNoteApi.updateAdminInfoNote({ infoNoteId: id, infoNoteRequest: request })
      : this.infoNoteApi.createAdminInfoNote({ infoNoteRequest: request });
    this.saving.set(true);
    call.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (saved) => {
        this.dialogVisible.set(false);
        if (id) {
          this.replace(saved);
        } else {
          this.load();
          this.infoNotes.reload();
        }
        this.messageService.add({ severity: 'success', summary: this.i18n.t(id ? 'adminInfoNotes.updated' : 'adminInfoNotes.created') });
      },
      error: (error) => this.toastError(error)
    });
  }

  remove(note: AdminInfoNoteResponse): void {
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('adminInfoNotes.deleteTitle'),
      message: this.i18n.t('adminInfoNotes.deleteMessage'),
      acceptLabel: this.i18n.t('common.delete'),
      destructive: true,
      accept: () => {
        this.busyId.set(note.id);
        this.infoNoteApi.deleteAdminInfoNote({ infoNoteId: note.id })
          .pipe(finalize(() => this.busyId.set(null)))
          .subscribe({
            next: () => {
              this.notes.update((list) => list.filter((entry) => entry.id !== note.id));
              this.infoNotes.reload();
            },
            error: (error) => this.toastError(error)
          });
      }
    });
  }

  private replace(updated: AdminInfoNoteResponse): void {
    this.notes.update((list) => list.map((entry) => entry.id === updated.id ? updated : entry));
    this.infoNotes.reload();
  }

  private toastError(error: unknown): void {
    this.messageService.add({ severity: 'error', summary: this.i18n.t('common.error'), detail: resolveApiError(error, this.i18n) });
  }
}
