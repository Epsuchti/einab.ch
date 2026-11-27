import { Component, ElementRef, computed, effect, inject, input, output, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Observable, Subject, catchError, debounceTime, finalize, of, switchMap, tap } from 'rxjs';

import { AdminService, EinAbCategory, TeacherEinAbResponse, TeacherService } from '../../api';
import { ErrorNotifierService } from '../../core/error-notifier.service';
import { I18nService } from '../../core/i18n.service';
import { scrollToFirstInvalidField } from '../../core/reveal-invalid-controls';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { DatePickerModule } from 'primeng/datepicker';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TextareaModule } from 'primeng/textarea';
import { MessageService } from 'primeng/api';
import { SkeletonComponent, SkeletonRegionDirective } from '../../core/skeleton';

export interface PastEinAbPerson {
  id: string;
  name: string;
  foodsharingId: string;
}

export type PastEinAbUserSearch = (query: string) => Observable<PastEinAbPerson[]>;

/** How far back a teacher may record; the server enforces the same limit. Admins have none. */
const TEACHER_MAX_AGE_DAYS = 90;
const MIN_SEARCH_LENGTH = 2;

/**
 * Records an EinAb that already took place and was not booked through the app.
 * Admins use it in the admin area, teachers at the bottom of their EinAb page; the
 * signed-in person is always the giver.
 */
@Component({
  selector: 'app-past-einab-form',
  standalone: true,
  imports: [SkeletonComponent, SkeletonRegionDirective, FormsModule, ButtonModule, CheckboxModule, DatePickerModule, InputTextModule, SelectModule, TextareaModule],
  templateUrl: './past-einab-form.component.html',
  styleUrl: './past-einab-form.component.scss'
})
export class PastEinAbFormComponent {
  readonly mode = input.required<'admin' | 'teacher'>();
  readonly bezirkSlug = input.required<string>();
  readonly categories = input.required<{ label: string; value: EinAbCategory }[]>();
  readonly searchUsers = input.required<PastEinAbUserSearch>();
  /** True while the list the search runs on is still loading (admin). */
  readonly usersLoading = input(false);
  readonly giverName = input('');
  readonly recorded = output<TeacherEinAbResponse>();

  readonly i18n = inject(I18nService);

  protected readonly query = signal('');
  protected readonly results = signal<PastEinAbPerson[] | null>(null);
  protected readonly searching = signal(false);
  protected readonly person = signal<PastEinAbPerson | null>(null);
  protected readonly date = signal('');
  protected readonly time = signal('12:00');
  protected readonly category = signal<EinAbCategory | null>(null);
  protected readonly businessName = signal('');
  protected readonly visitFairteiler = signal(false);
  protected readonly comment = signal('');
  protected readonly saving = signal(false);
  /** Set once the person pressed submit with missing fields; from then on the missing fields show red. */
  protected readonly showErrors = signal(false);
  private readonly formElement = viewChild<ElementRef<HTMLFormElement>>('pastEinAbFormElement');

  protected readonly quickDays = [
    { key: 'pastEinAb.today', value: dayString(0) },
    { key: 'pastEinAb.yesterday', value: dayString(1) },
    { key: 'pastEinAb.dayBefore', value: dayString(2) }
  ];
  protected readonly maxDate = dayString(0);
  protected readonly minDate = computed(() => this.mode() === 'teacher' ? dayString(TEACHER_MAX_AGE_DAYS) : null);
  /** The same values as Date objects for the PrimeNG date picker; `date` itself stays a `yyyy-mm-dd` string. */
  protected readonly dateValue = computed(() => parseDay(this.date()));
  protected readonly maxDateValue = parseDay(this.maxDate);
  protected readonly minDateValue = computed(() => parseDay(this.minDate()));
  protected readonly isOnline = computed(() => this.category() === EinAbCategory.Online);
  protected readonly start = computed(() => {
    const value = this.date() ? new Date(`${this.date()}T${this.time() || '12:00'}:00`) : null;
    return value && !Number.isNaN(value.getTime()) ? value : null;
  });
  protected readonly inFuture = computed(() => (this.start()?.getTime() ?? 0) > Date.now());
  protected readonly tooOld = computed(() => {
    const min = this.minDate();
    return Boolean(min && this.date() && this.date() < min);
  });
  protected readonly canSubmit = computed(() =>
    Boolean(this.person() && this.start() && this.category() && this.businessName().trim() && this.comment().trim()) && !this.inFuture() && !this.tooOld());
  protected readonly invalid = computed(() => {
    const show = this.showErrors();
    return {
      person: show && !this.person(),
      date: show && (!this.start() || this.inFuture() || this.tooOld()),
      category: show && !this.category(),
      businessName: show && !this.businessName().trim(),
      comment: show && !this.comment().trim()
    };
  });
  protected readonly summary = computed(() => {
    const start = this.start();
    const when = start
      ? new Intl.DateTimeFormat('de-CH', { weekday: 'short', day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }).format(start)
      : this.i18n.t('pastEinAb.chooseDate');
    const category = this.category();
    const where = [category ? this.i18n.categoryLabel(category) : this.i18n.t('teacher.chooseCategory'), this.businessName().trim()].filter(Boolean).join(', ');
    return [this.person()?.name ?? this.i18n.t('pastEinAb.choosePerson'), when, where].join(' · ');
  });

  private readonly adminApi = inject(AdminService);
  private readonly teacherApi = inject(TeacherService);
  private readonly messageService = inject(MessageService);
  private readonly errorNotifier = inject(ErrorNotifierService);
  private readonly searchRequests = new Subject<string>();

  constructor() {
    this.searchRequests.pipe(
      tap((text) => this.searching.set(text.length >= MIN_SEARCH_LENGTH)),
      debounceTime(250),
      switchMap((text) => text.length < MIN_SEARCH_LENGTH
        ? of(null)
        : this.searchUsers()(text).pipe(catchError((error) => {
          this.errorNotifier.notify(error);
          return of<PastEinAbPerson[]>([]);
        }))),
      takeUntilDestroyed()
    ).subscribe((results) => {
      this.results.set(results);
      this.searching.set(false);
    });
    // The category starts empty and must be chosen. A category the Bezirk has switched off is cleared again.
    effect(() => {
      const enabled = this.categories().map((option) => option.value);
      const current = this.category();
      if (current && enabled.length && !enabled.includes(current)) {
        this.category.set(null);
      }
    });
  }

  protected onQueryChange(value: string): void {
    this.query.set(value);
    this.searchRequests.next(value.trim());
  }

  protected pickFirstResult(event: Event): void {
    event.preventDefault();
    const first = this.results()?.[0];
    if (first) {
      this.pick(first);
    }
  }

  protected pick(person: PastEinAbPerson): void {
    this.person.set(person);
    this.clearSearch();
  }

  protected setDate(value: Date | null): void {
    this.date.set(value ? formatDay(value) : '');
  }

  protected changePerson(): void {
    this.person.set(null);
    this.clearSearch();
  }

  protected initials(name: string): string {
    return name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]?.toUpperCase() ?? '').join('');
  }

  protected submit(): void {
    const person = this.person();
    const start = this.start();
    const category = this.category();
    if (this.saving()) {
      return;
    }
    if (!person || !start || !category || !this.canSubmit()) {
      this.showErrors.set(true);
      scrollToFirstInvalidField(this.formElement()?.nativeElement, '[data-invalid]');
      return;
    }
    this.saving.set(true);
    const params = {
      bezirkSlug: this.bezirkSlug(),
      bookingUserId: person.id,
      createHistoricalEinAbRequest: {
        category,
        startDateTime: start.toISOString(),
        businessName: this.businessName().trim(),
        visitFairteiler: !this.isOnline() && this.visitFairteiler(),
        comment: this.comment().trim()
      }
    };
    const request$: Observable<TeacherEinAbResponse> = this.mode() === 'admin'
      ? this.adminApi.createAdminHistoricalEinAb(params)
      : this.teacherApi.createTeacherHistoricalEinAb(params);
    request$.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (einAb) => {
        this.messageService.add({ severity: 'success', summary: this.i18n.t('pastEinAb.recorded').replace('{name}', person.name) });
        // Date, time and category stay: several people of the same EinAb are entered one after the other.
        this.person.set(null);
        this.comment.set('');
        this.showErrors.set(false);
        this.recorded.emit(einAb);
      },
      error: (error) => this.errorNotifier.notify(error)
    });
  }

  private clearSearch(): void {
    this.query.set('');
    this.results.set(null);
    this.searchRequests.next('');
  }
}

/** `yyyy-mm-dd` as a local Date at midnight, or null when empty. */
function parseDay(value: string | null): Date | null {
  if (!value) {
    return null;
  }
  const [year, month, day] = value.split('-').map(Number);
  return new Date(year, month - 1, day);
}

/** Local Date as `yyyy-mm-dd`. */
function formatDay(day: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${day.getFullYear()}-${pad(day.getMonth() + 1)}-${pad(day.getDate())}`;
}

/** Local calendar day, `daysAgo` days back, as the value of a date input. */
function dayString(daysAgo: number): string {
  const day = new Date();
  day.setDate(day.getDate() - daysAgo);
  return formatDay(day);
}
