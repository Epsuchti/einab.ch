import { CommonModule, NgTemplateOutlet } from '@angular/common';
import { Component, ElementRef, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, finalize, forkJoin, map, of } from 'rxjs';

import {
  EinAbCategory,
  IcalCandidate,
  IcalCandidateListResponse,
  SlotStatus,
  SlotResponse,
  TeacherAssignmentOption,
  TeacherEinAbListResponse,
  TeacherEinAbResponse,
  TeacherResponse,
  TeacherSelfResponse,
  TeacherService,
  PrivateInfoTemplateResponse,
  UpdateTeacherMeRequest,
  BezirkResponse,
  BookingCommentResponse,
  CreateBookingCommentRequest,
  UpsertEinAbRequest
} from '../../api';
import { resolveApiError } from '../../core/api-error';
import { revealInvalidControls } from '../../core/reveal-invalid-controls';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { confirmAction } from '../../core/confirm-action';
import { I18nService } from '../../core/i18n.service';
import { LoadingTracker } from '../../core/loading-tracker';
import { ZurichDateTimePipe } from '../../core/zurich-date-time.pipe';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { CheckboxModule } from 'primeng/checkbox';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { DatePickerModule } from 'primeng/datepicker';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';
import { SelectModule } from 'primeng/select';
import { PaginatorModule } from 'primeng/paginator';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { Menu, MenuModule } from 'primeng/menu';
import { ConfirmationService, MenuItem, MessageService } from 'primeng/api';
import { NewcomerFunnelWidgetComponent } from '../newcomer-funnel-page/newcomer-funnel-widget.component';
import { PastEinAbFormComponent } from '../past-einab-form/past-einab-form.component';
import { SkeletonComponent, SkeletonRegionDirective, SkeletonTableComponent } from '../../core/skeleton';

@Component({
  selector: 'app-teacher-dashboard-page',
  standalone: true,
  imports: [SkeletonComponent, SkeletonRegionDirective, SkeletonTableComponent,
    RouterLink,
    CommonModule,
    NgTemplateOutlet,
    MenuModule,
    FormsModule,
    ReactiveFormsModule,
    ZurichDateTimePipe,
    CardModule,
    ButtonModule,
    CheckboxModule,
    ToggleSwitchModule,
    ConfirmDialogModule,
    DatePickerModule,
    DialogModule,
    InputTextModule,
    TextareaModule,
    SelectModule,
    PaginatorModule,
    TableModule,
    TagModule,
    NewcomerFunnelWidgetComponent,
    PastEinAbFormComponent
  ],
  templateUrl: './teacher-dashboard-page.component.html',
  styleUrl: './teacher-dashboard-page.component.scss'
})
export class TeacherDashboardPageComponent implements OnInit {
  readonly i18n = inject(I18nService);
  readonly SlotStatus = SlotStatus;

  protected readonly teacher = signal<TeacherResponse | null>(null);
  protected readonly teacherLoading = signal(true);
  protected readonly icalLoading = signal(true);
  private readonly einAbsTracker = new LoadingTracker();
  protected readonly einAbsLoading = this.einAbsTracker.active;
  protected readonly einAbs = signal<TeacherEinAbResponse[]>([]);
  protected readonly todoEinAbs = signal<TeacherEinAbResponse[]>([]);
  protected readonly einAbsPage = signal<TeacherEinAbListResponse | null>(null);
  protected showPastEinAbs = false;
  protected readonly assignableTeachers = signal<TeacherAssignmentOption[]>([]);
  protected readonly assignTeacherDialogVisible = signal(false);
  protected readonly assignTeacherEinAb = signal<TeacherEinAbResponse | null>(null);
  protected readonly selectedTeacherId = signal<string | null>(null);
  protected readonly assignTeacherLoading = signal(false);
  protected readonly icalCandidates = signal<IcalCandidate[]>([]);
  protected readonly icalCandidatesPage = signal<IcalCandidateListResponse | null>(null);
  protected readonly bezirke = signal<BezirkResponse[]>([]);
  protected readonly icalFeedLink = signal('');
  protected readonly planningToolsHighlight = signal(false);
  protected readonly icalFeedCopied = signal(false);
  private readonly calendarSettingsDetails = viewChild<ElementRef<HTMLDetailsElement>>('calendarSettingsDetails');
  private readonly planningToolsDetails = viewChild<ElementRef<HTMLElement>>('planningToolsDetails');
  /** Quick create in the Meine EinAbs header: chips shown before "+N weitere". */
  protected readonly quickCreateLimit = 5;
  protected readonly quickCreateExpanded = signal(false);
  protected readonly quickCreateChips = computed(() => {
    // Pickups that already have an EinAb are left out.
    const upcoming = this.einAbs();
    return this.icalCandidates()
      .filter((candidate) => !this.isCandidateCovered(candidate, upcoming))
      .map((candidate) => ({ candidate, store: this.candidateStoreName(candidate), covered: false }));
  });
  protected readonly visibleQuickCreateChips = computed(() =>
    this.quickCreateExpanded() ? this.quickCreateChips() : this.quickCreateChips().slice(0, this.quickCreateLimit));
  protected readonly hiddenQuickCreateCount = computed(() => {
    if (this.quickCreateExpanded()) {
      return 0;
    }
    const coveredOnPage = this.icalCandidates().length - this.quickCreateChips().length;
    const total = (this.icalCandidatesPage()?.totalElements ?? this.icalCandidates().length) - coveredOnPage;
    return Math.max(0, total - this.quickCreateLimit);
  });
  protected readonly bezirkSaveLoading = signal(false);
  protected readonly settingsSaveLoading = signal(false);
  protected readonly saveLoading = signal(false);
  protected readonly commentsBySlotId = signal<Record<string, BookingCommentResponse[]>>({});
  protected readonly commentsLoaded = signal(false);
  protected readonly commentLoading = signal(false);
  protected readonly commentDialogVisible = signal(false);
  protected readonly commentTarget = signal<SlotResponse | null>(null);
  protected readonly commentTargetBezirkSlug = signal<string | null>(null);
  /** EinAb of the booking being commented, shown in the comment dialog. */
  protected readonly commentEinAb = signal<TeacherEinAbResponse | null>(null);
  /** Items of the one action menu per EinAb (edit, calendar, hand over, delete). */
  protected readonly einAbActionItems = signal<MenuItem[]>([]);
  /** The Bezirk select in the EinAb form stays hidden until "ändern" is clicked. */
  protected readonly bezirkFieldVisible = signal(false);
  /** The iCal setup on foodsharing as numbered steps instead of an arrow chain. */
  private readonly einAbActionsMenu = viewChild<Menu>('einAbActionsMenu');
  private readonly einabFormElement = viewChild<ElementRef<HTMLFormElement>>('einabFormElement');
  private readonly formBezirkSlug = signal('');
  protected readonly categoryOptions = computed(() => this.bezirkContext.enabledCategories(this.formBezirkSlug(), this.editingEinAb()?.category)
    .map((value) => ({ value, label: this.i18n.categoryLabel(value) })));
  protected readonly privateInfoTemplates = signal<PrivateInfoTemplateResponse[]>([]);
  private readonly privateInfoTemplateCategory = signal<EinAbCategory | null>(null);
  /** Normalized store name of the form, the key of a store's private info template. */
  private readonly privateInfoTemplateStoreKey = signal('');
  protected readonly einabBezirkOptions = computed(() =>
    this.bezirke().map((bezirk) => ({ label: bezirk.name, value: bezirk.slug }))
  );
  protected readonly slotCountOptions = [1, 2, 3].map((value) => ({ value, label: String(value) }));
  protected readonly requiredExperienceOptions = computed(() => {
    const current = this.editingEinAb()?.minimumPickupCount;
    const values = [0, 1, 2];
    if (current && current > 2) {
      values.push(current);
    }
    return values.map((value) => ({ value, label: this.i18n.requiredExperienceLabel(value) }));
  });
  protected readonly assignableTeacherOptions = computed(() => this.assignableTeachers().map((teacher) => ({
    label: teacher.phoneNumber ? `${teacher.name} (${teacher.phoneNumber})` : teacher.name,
    value: teacher.id
  })));
  protected readonly pageSize = 20;
  protected readonly minimumStartDate = new Date(new Date().setHours(0, 0, 0, 0));

  protected einabDialogVisible = false;
  protected readonly editingEinAb = signal<TeacherEinAbResponse | null>(null);
  protected readonly commentForm = inject(FormBuilder).nonNullable.group({
    comment: ['', [Validators.required, Validators.minLength(2)]]
  });

  protected readonly einabForm = inject(FormBuilder).nonNullable.group({
    bezirkSlug: ['', Validators.required],
    // No default: the teacher picks the category for every new EinAb.
    category: [null as EinAbCategory | null, Validators.required],
    startDateTime: [new Date(), Validators.required],
    startTime: ['12:00', Validators.required],
    location: [''],
    publicLocation: [''],
    businessName: ['', Validators.required],
    privateAddress: ['', Validators.required],
    publicGeneralLocation: ['', Validators.required],
    onlineCallLink: [''],
    privateInfo: [''],
    privateInfoTemplate: [null as string | null],
    savePrivateInfoAsTemplate: [false],
    publicInfo: [''],
    visitFairteiler: [false],
    slotCount: [1, Validators.required],
    minimumPickupCount: [0 as number]
  });

  /**
   * Templates are saved per store. A store without its own template offers the category template instead
   * (saved before templates were per store); online EinAbs have no store and keep the category template.
   */
  protected readonly privateInfoTemplateOptions = computed(() => {
    const category = this.privateInfoTemplateCategory();
    const storeKey = this.isOnline(category) ? '' : this.privateInfoTemplateStoreKey();
    const templates = this.privateInfoTemplates();
    const storeTemplate = storeKey ? templates.find((item) => item.storeName && item.storeKey === storeKey) : undefined;
    const categoryTemplate = templates.find((item) => !item.storeName && item.category === category);
    const options: { label: string; value: string | null }[] = [{ label: this.i18n.t('teacher.privateInfoWriteNew'), value: null }];
    if (storeTemplate) {
      options.push({ label: this.i18n.t('teacher.privateInfoUseStoreTemplate').replace('{store}', storeTemplate.storeName ?? ''), value: storeTemplate.content });
    } else if (categoryTemplate && category) {
      options.push({ label: this.privateInfoTemplateLabel(category), value: categoryTemplate.content });
    }
    return options;
  });

  protected readonly teacherSettingsForm = inject(FormBuilder).nonNullable.group({
    icalLink: ['']
  });

  protected readonly todoSlots = computed(() => {
    if (!this.commentsLoaded()) {
      return [];
    }
    return this.todoEinAbs().flatMap((einab) => this.isPast(einab.startDateTime)
      ? einab.slots
        .filter((slot) => slot.bookingUser?.active && !this.commentsForSlot(slot.id).length)
        .map((slot) => ({ einab, slot }))
      : []);
  });

  private readonly teacherApi = inject(TeacherService);
  protected readonly bezirkContext = inject(BezirkContextService);
  private readonly messageService = inject(MessageService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  /** A "create EinAb" request from the newcomer needs (?newEinAb=CATEGORY&fairteiler=1), opened once the teacher is loaded. */
  private readonly pendingCreate = signal<{ category?: EinAbCategory; visitFairteiler: boolean } | null>(null);

  ngOnInit(): void {
    this.route.queryParamMap.subscribe((params) => {
      if (!params.has('newEinAb')) {
        return;
      }
      const category = params.get('newEinAb') as EinAbCategory;
      this.pendingCreate.set({
        category: Object.values(EinAbCategory).includes(category) ? category : undefined,
        visitFairteiler: params.get('fairteiler') === '1'
      });
      this.openPendingCreate();
    });
    this.einabForm.controls.bezirkSlug.valueChanges.subscribe((bezirkSlug) => {
      this.formBezirkSlug.set(bezirkSlug);
      this.ensureEnabledCategory();
    });
    this.einabForm.controls.category.valueChanges.subscribe((category) => {
      this.privateInfoTemplateCategory.set(category);
      this.configureOnlineFields(category);
      this.einabForm.patchValue({ privateInfoTemplate: null, savePrivateInfoAsTemplate: false }, { emitEvent: false });
    });
    this.einabForm.controls.businessName.valueChanges.subscribe((businessName) => {
      const storeKey = privateInfoStoreKey(businessName);
      if (storeKey !== this.privateInfoTemplateStoreKey()) {
        this.privateInfoTemplateStoreKey.set(storeKey);
        this.einabForm.controls.privateInfoTemplate.setValue(null, { emitEvent: false });
      }
    });
    this.reload();
  }

  reload(): void {
    this.teacherApi.getTeacherMe().pipe(finalize(() => this.teacherLoading.set(false))).subscribe({
      next: (response) => {
        this.teacher.set(response);
        this.openPendingCreate();
        this.icalFeedLink.set(response.icalFeedLink ?? '');
        this.teacherSettingsForm.reset({ icalLink: response.icalLink ?? '' });
        this.bezirkContext.loadBezirke().subscribe((bezirke) => this.bezirke.set(bezirke));
        const dashboardBezirkSlug = this.bezirkContext.currentSlug();
        this.loadTeacherEinAbs(dashboardBezirkSlug);
        this.loadAssignableTeachers(dashboardBezirkSlug);
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
    this.teacherApi.getTeacherIcalCandidates({ page: this.icalCandidatesPage()?.page ?? 0, size: this.pageSize }).pipe(finalize(() => this.icalLoading.set(false))).subscribe({
      next: (response) => {
        this.icalCandidates.set(response.candidates);
        this.icalCandidatesPage.set(response);
      },
      error: () => this.icalCandidates.set([])
    });
    this.teacherApi.getTeacherPrivateInfoTemplates().subscribe({
      next: (response) => this.privateInfoTemplates.set(response.templates),
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  private loadTeacherEinAbs(teacherBezirkSlug?: string): void {
    if (!teacherBezirkSlug) {
      this.einAbs.set([]);
      this.todoEinAbs.set([]);
      this.einAbsPage.set(null);
      this.commentsBySlotId.set({});
      this.commentsLoaded.set(true);
      return;
    }
    this.commentsLoaded.set(false);
    forkJoin({
      visible: this.teacherApi.getTeacherEinAbs({
        bezirkSlug: teacherBezirkSlug,
        page: this.einAbsPage()?.page ?? 0,
        size: this.pageSize,
        pastOnly: this.showPastEinAbs
      }),
      todo: this.teacherApi.getTeacherEinAbs({
        bezirkSlug: teacherBezirkSlug,
        page: 0,
        size: 100,
        pastOnly: true
      }).pipe(catchError(() => of(null)))
    }).pipe(this.einAbsTracker.track()).subscribe({
      next: ({ visible, todo }) => {
        const todoEinAbs = todo?.einAbs ?? [];
        this.einAbs.set(visible.einAbs);
        this.einAbsPage.set(visible);
        this.todoEinAbs.set(todoEinAbs);
        this.loadComments(teacherBezirkSlug, [...visible.einAbs, ...todoEinAbs].flatMap((einab) => einab.slots.filter((slot) => Boolean(slot.bookingUser)).map((slot) => slot.id)));
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  private loadAssignableTeachers(teacherBezirkSlug?: string): void {
    if (!teacherBezirkSlug) {
      this.assignableTeachers.set([]);
      return;
    }
    this.teacherApi.getTeacherAssignableTeachers({ bezirkSlug: teacherBezirkSlug }).subscribe({
      next: (response) => this.assignableTeachers.set(response.teachers),
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  onEinAbsPageChange(event: { page?: number }): void {
    this.einAbsPage.update((current) => current ? { ...current, page: event.page ?? 0 } : current);
    this.reload();
  }

  onShowPastEinAbsChange(showPast: boolean): void {
    this.showPastEinAbs = showPast;
    this.einAbsPage.update((current) => current ? { ...current, page: 0 } : current);
    this.loadTeacherEinAbs(this.bezirkContext.currentSlug());
  }

  downloadEinAbIcal(einab: TeacherEinAbResponse): void {
    // The generated client prefers JSON for the Accept header (the spec also lists JSON error responses),
    // which would try to parse the calendar text as JSON, so the text/calendar response type is requested explicitly.
    this.teacherApi.downloadTeacherEinAbIcal(
      { bezirkSlug: einab.bezirk.slug, einAbId: einab.id },
      'body',
      false,
      { httpHeaderAccept: 'text/calendar' }
    ).subscribe({
      next: (calendar) => {
        const downloadUrl = URL.createObjectURL(new Blob([calendar], { type: 'text/calendar;charset=utf-8' }));
        const link = document.createElement('a');
        link.href = downloadUrl;
        link.download = `einab-${einab.id}.ics`;
        link.click();
        URL.revokeObjectURL(downloadUrl);
      },
      error: (error) => this.messageService.add({
        severity: 'error',
        summary: resolveApiError(error, this.i18n),
      }),
    });
  }

  onIcalPageChange(event: { page?: number }): void {
    this.icalCandidatesPage.update((current) => current ? { ...current, page: event.page ?? 0 } : current);
    this.reload();
  }

  saveTeacherSettings(): void {
    this.settingsSaveLoading.set(true);
    const previousIcalLink = (this.teacher()?.icalLink ?? '').trim();
    const updateTeacherMeRequest: UpdateTeacherMeRequest = {
      language: this.i18n.apiLanguage(),
      icalLink: this.teacherSettingsForm.getRawValue().icalLink.trim() || undefined
    };
    this.teacherApi.updateTeacherMe({ updateTeacherMeRequest }).subscribe({
      next: (response: TeacherSelfResponse) => {
        this.teacher.set(response);
        this.icalFeedLink.set(response.icalFeedLink ?? '');
        this.icalCandidates.set(response.icalCandidates ?? []);
        this.icalCandidatesPage.update((current) => current ? { ...current, candidates: response.icalCandidates ?? [] } : current);
        this.teacherSettingsForm.reset({ icalLink: response.icalLink ?? '' });
        this.settingsSaveLoading.set(false);
        const savedIcalLink = (response.icalLink ?? '').trim();
        if (savedIcalLink && savedIcalLink !== previousIcalLink) {
          this.showImportedIcalCandidates();
        } else if (!savedIcalLink && previousIcalLink) {
          this.messageService.add({ severity: 'info', summary: this.i18n.t('teacher.icalLinkRemoved') });
        } else {
          this.messageService.add({ severity: 'success', summary: this.i18n.t('teacher.settingsSaved') });
        }
      },
      error: (error) => {
        this.settingsSaveLoading.set(false);
        this.toastError(resolveApiError(error, this.i18n));
      }
    });
  }

  async copyIcalFeedLink(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.icalFeedLink());
      this.icalFeedCopied.set(true);
      this.messageService.add({ severity: 'success', summary: this.i18n.t('teacher.icalFeedCopiedToast') });
      setTimeout(() => this.icalFeedCopied.set(false), 2500);
    } catch {
      this.messageService.add({ severity: 'warn', summary: this.i18n.t('teacher.icalFeedCopyFailed'), detail: this.icalFeedLink() });
    }
  }

  removeIcalLink(): void {
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('confirmDialog.removeIcalLink.title'),
      acceptLabel: this.i18n.t('confirmDialog.remove.accept'),
      destructive: true,
      message: this.i18n.t('confirm.removeIcalLink'),
      accept: () => {
        this.teacherSettingsForm.reset({ icalLink: '' });
        this.saveTeacherSettings();
      }
    });
  }

  assignBezirk(bezirkSlug: string): void {
    if (!bezirkSlug || this.teacher()?.bezirk || this.bezirkSaveLoading()) {
      return;
    }
    this.bezirkSaveLoading.set(true);
    this.teacherApi.assignTeacherBezirk({ assignTeacherBezirkRequest: { bezirkSlug } }).subscribe({
      next: (teacher) => {
        this.teacher.set(teacher);
        this.bezirkSaveLoading.set(false);
        const dashboardBezirkSlug = this.bezirkContext.currentSlug();
        this.loadTeacherEinAbs(dashboardBezirkSlug);
        this.loadAssignableTeachers(dashboardBezirkSlug);
      },
      error: (error) => {
        this.bezirkSaveLoading.set(false);
        this.toastError(resolveApiError(error, this.i18n));
      }
    });
  }

  openCreate(category?: EinAbCategory, visitFairteiler = false): void {
    this.bezirkFieldVisible.set(false);
    if (!this.requireActiveTeacher()) {
      return;
    }
    this.editingEinAb.set(null);
    this.einabForm.reset({
      bezirkSlug: this.defaultEinAbBezirkSlug(),
      category: category ?? null,
      startDateTime: tomorrow(),
      startTime: '12:00',
      location: '',
      publicLocation: '',
      businessName: '',
      privateAddress: '',
      publicGeneralLocation: '',
      onlineCallLink: '',
      privateInfo: '',
      privateInfoTemplate: null,
      savePrivateInfoAsTemplate: false,
      publicInfo: '',
      visitFairteiler,
      slotCount: 1,
      minimumPickupCount: 0
    });
    this.ensureEnabledCategory();
    this.einabDialogVisible = true;
  }

  private openPendingCreate(): void {
    const pending = this.pendingCreate();
    if (!pending || !this.teacher()) {
      return;
    }
    this.pendingCreate.set(null);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { newEinAb: null, fairteiler: null }, queryParamsHandling: 'merge', replaceUrl: true });
    this.openCreate(pending.category, pending.visitFairteiler);
  }

  openCreateFromCandidate(candidate: IcalCandidate): void {
    this.bezirkFieldVisible.set(false);
    if (!this.requireActiveTeacher()) {
      return;
    }
    this.editingEinAb.set(null);
    // The EinAb starts a few minutes before the pickup itself (Bezirk setting, 10 min by default).
    const leadMinutes = this.teacher()?.bezirk?.pickupEinAbLeadMinutes ?? 10;
    const start = new Date(new Date(candidate.startDateTime).getTime() - leadMinutes * 60_000);
    this.einabForm.reset({
      bezirkSlug: this.defaultEinAbBezirkSlug(),
      category: null,
      startDateTime: start,
      startTime: this.timePart(start),
      location: candidate.location ?? '',
      publicLocation: '',
      businessName: this.candidateStoreName(candidate),
      privateAddress: candidate.location ?? '',
      publicGeneralLocation: '',
      onlineCallLink: '',
      privateInfo: '',
      privateInfoTemplate: null,
      savePrivateInfoAsTemplate: false,
      publicInfo: '',
      visitFairteiler: false,
      slotCount: 1,
      minimumPickupCount: 0
    });
    this.ensureEnabledCategory();
    this.einabDialogVisible = true;
  }

  openEdit(einab: TeacherEinAbResponse): void {
    this.bezirkFieldVisible.set(false);
    if (!this.requireActiveTeacher()) {
      return;
    }
    if (this.isPast(einab.startDateTime)) {
      this.toastError(this.i18n.t('error.PAST_EINAB_NOT_EDITABLE'));
      return;
    }
    this.editingEinAb.set(einab);
    this.einabForm.reset({
      bezirkSlug: einab.bezirk?.slug ?? this.defaultEinAbBezirkSlug(),
      category: einab.category,
      startDateTime: new Date(einab.startDateTime),
      startTime: this.timePart(new Date(einab.startDateTime)),
      location: einab.location ?? '',
      publicLocation: einab.publicLocation ?? '',
      businessName: einab.businessName ?? '',
      privateAddress: einab.privateAddress ?? einab.location ?? '',
      publicGeneralLocation: einab.publicGeneralLocation ?? einab.publicLocation ?? '',
      onlineCallLink: einab.onlineCallLink ?? '',
      privateInfo: einab.privateInfo ?? '',
      privateInfoTemplate: null,
      savePrivateInfoAsTemplate: false,
      publicInfo: einab.publicInfo ?? '',
      visitFairteiler: einab.visitFairteiler,
      slotCount: einab.slotCount,
      minimumPickupCount: einab.minimumPickupCount ?? 0
    });
    this.configureOnlineFields(einab.category);
    this.einabDialogVisible = true;
  }

  saveEinAb(): void {
    if (this.einabForm.invalid) {
      revealInvalidControls(this.einabForm, this.einabFormElement()?.nativeElement);
      return;
    }
    if (!this.requireActiveTeacher()) {
      return;
    }
    this.saveLoading.set(true);
    const formValue = this.einabForm.getRawValue();
    const startDateTime = new Date(formValue.startDateTime);
    const [hours, minutes] = formValue.startTime.split(':').map(Number);
    startDateTime.setHours(hours, minutes, 0, 0);
    const upsertEinAbRequest: UpsertEinAbRequest = {
      category: formValue.category!,
      startDateTime: startDateTime.toISOString(),
      location: formValue.location?.trim() || undefined,
      publicLocation: this.isOnline(formValue.category) ? undefined : formValue.publicLocation.trim(),
      businessName: this.isOnline(formValue.category) ? undefined : formValue.businessName.trim() || undefined,
      privateAddress: this.isOnline(formValue.category) ? undefined : formValue.privateAddress.trim(),
      publicGeneralLocation: this.isOnline(formValue.category) ? undefined : formValue.publicGeneralLocation.trim(),
      onlineCallLink: this.isOnline(formValue.category) ? formValue.onlineCallLink.trim() : undefined,
      privateInfo: formValue.privateInfo?.trim() || undefined,
      savePrivateInfoAsTemplate: formValue.savePrivateInfoAsTemplate && Boolean(formValue.privateInfo?.trim()),
      publicInfo: formValue.publicInfo?.trim() || undefined,
      visitFairteiler: this.isOnline(formValue.category) ? false : formValue.visitFairteiler,
      slotCount: formValue.slotCount,
      minimumPickupCount: this.isOnline(formValue.category) ? undefined : formValue.minimumPickupCount || undefined
    };

    const bezirkSlug = formValue.bezirkSlug.trim();
    if (!bezirkSlug) {
      this.toastError(this.i18n.t('teacher.unassignedHint'));
      this.saveLoading.set(false);
      return;
    }
    const request$ = this.editingEinAb()
      ? this.teacherApi.updateTeacherEinAb({ bezirkSlug, einAbId: this.editingEinAb()!.id, upsertEinAbRequest })
      : this.teacherApi.createTeacherEinAb({ bezirkSlug, upsertEinAbRequest });

    request$.pipe(finalize(() => this.saveLoading.set(false))).subscribe({
      next: () => {
        this.einabDialogVisible = false;
        this.reload();
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  confirmDelete(einab: TeacherEinAbResponse): void {
    if (this.isPast(einab.startDateTime)) {
      this.toastError(this.i18n.t('error.PAST_EINAB_NOT_DELETABLE'));
      return;
    }
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('confirmDialog.deleteEinab.title'),
      acceptLabel: this.i18n.t('confirmDialog.delete.accept'),
      destructive: true,
      message: this.i18n.t('confirm.deleteEinab'),
      accept: () => {
        this.teacherApi.deleteTeacherEinAb({ bezirkSlug: einab.bezirk.slug, einAbId: einab.id }).subscribe({
          next: () => this.reload(),
          error: (error) => this.toastError(resolveApiError(error, this.i18n))
        });
      }
    });
  }

  cancelSlotBooking(slot: SlotResponse, startDateTime: string, bezirkSlug: string): void {
    const past = this.isPast(startDateTime);
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t(past ? 'confirmDialog.markDidNotShowUp.title' : 'confirmDialog.cancelBooking.title'),
      acceptLabel: this.i18n.t(past ? 'confirmDialog.markDidNotShowUp.accept' : 'confirmDialog.cancelBooking.accept'),
      rejectLabel: this.i18n.t('common.keep'),
      destructive: true,
      message: this.i18n.t(past ? 'confirm.markDidNotShowUp' : 'confirm.cancelTeacherBooking'),
      accept: () => {
        this.teacherApi.cancelTeacherSlotBooking({ bezirkSlug, slotId: slot.id }).subscribe({
          next: () => this.reload(),
          error: (error) => this.toastError(resolveApiError(error, this.i18n))
        });
      }
    });
  }

  confirmCountAgain(slot: SlotResponse, bezirkSlug: string): void {
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('confirmDialog.countAgain.title'),
      acceptLabel: this.i18n.t('confirmDialog.countAgain.accept'),
      message: this.i18n.t('confirm.countAgain'),
      accept: () => {
        this.teacherApi.countTeacherSlotBooking({
          bezirkSlug,
          slotId: slot.id
        }).subscribe({
          next: () => this.reload(),
          error: (error) => this.toastError(resolveApiError(error, this.i18n))
        });
      }
    });
  }

  protected initials(name: string | undefined): string {
    const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
    if (!parts.length) {
      return '?';
    }
    const first = parts[0][0];
    const last = parts.length > 1 ? parts[parts.length - 1][0] : '';
    return (first + last).toUpperCase();
  }

  protected isPast(startDateTime: string): boolean {
    return new Date(startDateTime).getTime() < Date.now();
  }

  commentsForSlot(slotId: string): BookingCommentResponse[] {
    return this.commentsBySlotId()[slotId] ?? [];
  }

  openEinAbActions(event: Event, einab: TeacherEinAbResponse): void {
    const upcoming = !this.isPast(einab.startDateTime);
    const items: MenuItem[] = [];
    if (upcoming) {
      items.push({ label: this.i18n.t('common.edit'), icon: 'pi pi-pencil', command: () => this.openEdit(einab) });
    }
    items.push({ label: this.i18n.t('teacher.downloadEinAbIcal'), icon: 'pi pi-download', command: () => this.downloadEinAbIcal(einab) });
    if (upcoming) {
      items.push(
        { label: this.i18n.t('teacher.assignEinAb'), icon: 'pi pi-user-edit', command: () => this.openAssignTeacher(einab) },
        { separator: true },
        { label: this.i18n.t('common.delete'), icon: 'pi pi-trash', styleClass: 'menu-item--danger', command: () => this.confirmDelete(einab) }
      );
    }
    this.einAbActionItems.set(items);
    this.einAbActionsMenu()?.toggle(event);
  }

  openCommentDialog(slot: SlotResponse, bezirkSlug: string, einab?: TeacherEinAbResponse): void {
    if (!slot.bookingUser) return;
    this.commentEinAb.set(einab ?? null);
    this.commentTarget.set(slot);
    this.commentTargetBezirkSlug.set(bezirkSlug);
    this.commentForm.reset({ comment: '' });
    this.commentDialogVisible.set(true);
  }

  saveComment(): void {
    const slot = this.commentTarget();
    const bezirkSlug = this.commentTargetBezirkSlug();
    const comment = this.commentForm.getRawValue().comment.trim();
    if (!slot?.bookingUser || !bezirkSlug || !comment || this.commentForm.invalid) return;

    this.commentLoading.set(true);
    const createBookingCommentRequest: CreateBookingCommentRequest = { comment };
    this.teacherApi.addTeacherSlotComment({ bezirkSlug, slotId: slot.id, createBookingCommentRequest }).subscribe({
      next: () => {
        this.commentDialogVisible.set(false);
        this.commentTarget.set(null);
        this.commentTargetBezirkSlug.set(null);
        this.refreshComments(slot.id, bezirkSlug);
      },
      error: (error) => {
        this.commentLoading.set(false);
        this.toastError(resolveApiError(error, this.i18n));
      }
    });
  }

  openAssignTeacher(einab: TeacherEinAbResponse): void {
    if (this.isPast(einab.startDateTime)) {
      this.toastError(this.i18n.t('error.PAST_EINAB_NOT_REASSIGNABLE'));
      return;
    }
    this.assignTeacherEinAb.set(einab);
    this.selectedTeacherId.set(einab.teacher.id);
    this.assignTeacherDialogVisible.set(true);
  }

  assignTeacherToEinAb(): void {
    const einab = this.assignTeacherEinAb();
    const teacherId = this.selectedTeacherId();
    const bezirkSlug = einab?.bezirk.slug;
    if (!einab || !teacherId || !bezirkSlug || this.assignTeacherLoading()) {
      return;
    }
    this.assignTeacherLoading.set(true);
    this.teacherApi.assignTeacherToEinAb({
      bezirkSlug,
      einAbId: einab.id,
      assignTeacherToEinAbRequest: { teacherId }
    }).subscribe({
      next: () => {
        this.assignTeacherLoading.set(false);
        this.assignTeacherDialogVisible.set(false);
        this.assignTeacherEinAb.set(null);
        this.loadTeacherEinAbs(bezirkSlug);
        this.messageService.add({ severity: 'success', summary: this.i18n.t('teacher.assignEinAbSuccess') });
      },
      error: (error) => {
        this.assignTeacherLoading.set(false);
        this.toastError(resolveApiError(error, this.i18n));
      }
    });
  }

  private showImportedIcalCandidates(): void {
    this.icalLoading.set(true);
    this.teacherApi.getTeacherIcalCandidates({ page: 0, size: this.pageSize }).pipe(finalize(() => this.icalLoading.set(false))).subscribe({
      next: (response) => {
        this.icalCandidates.set(response.candidates);
        this.icalCandidatesPage.set(response);
        const count = response.totalElements ?? response.candidates.length;
        this.messageService.add(count > 0
          ? {
              severity: 'success',
              summary: this.i18n.t('teacher.icalLinkSaved'),
              detail: this.i18n.t('teacher.icalLinkSavedDetail').replace('{count}', '' + count),
              life: 8000
            }
          : {
              severity: 'warn',
              summary: this.i18n.t('teacher.icalLinkSaved'),
              detail: this.i18n.t('teacher.icalLinkSavedNoCandidates'),
              life: 10000
            });
        this.revealPlanningTools();
      },
      error: (error) => {
        this.icalCandidates.set([]);
        this.toastError(resolveApiError(error, this.i18n));
      }
    });
  }

  protected openCalendarSettings(): void {
    const calendarSettings = this.calendarSettingsDetails()?.nativeElement;
    if (!calendarSettings) {
      return;
    }
    calendarSettings.open = true;
    setTimeout(() => calendarSettings.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  }

  private candidateStoreName(candidate: IcalCandidate): string {
    return candidate.summary.replace(/\s+Abholung\s*$/i, '').trim();
  }

  /** A pickup counts as covered when an upcoming EinAb has the same store on the same day. */
  private isCandidateCovered(candidate: IcalCandidate, einAbs: TeacherEinAbResponse[]): boolean {
    const store = this.normalizeStore(this.candidateStoreName(candidate));
    const day = zurichDayKey(candidate.startDateTime);
    return Boolean(store) && einAbs.some((einab) =>
      zurichDayKey(einab.startDateTime) === day && this.normalizeStore(einab.businessName ?? '') === store);
  }

  private normalizeStore(value: string): string {
    return value.toLocaleLowerCase('de-CH').replace(/\s+/g, ' ').trim();
  }

  protected revealPlanningTools(): void {
    const calendarSettings = this.calendarSettingsDetails()?.nativeElement;
    const planningTools = this.planningToolsDetails()?.nativeElement;
    if (calendarSettings) {
      calendarSettings.open = false;
    }
    if (!planningTools) {
      return;
    }
    this.planningToolsHighlight.set(true);
    setTimeout(() => planningTools.scrollIntoView({ behavior: 'smooth', block: 'start' }));
    setTimeout(() => this.planningToolsHighlight.set(false), 2500);
  }

  private toastError(detail: string): void {
    this.messageService.add({ severity: 'error', summary: this.i18n.t('common.error'), detail });
  }

  private loadComments(bezirkSlug: string, slotIds: string[]): void {
    const uniqueSlotIds = Array.from(new Set(slotIds));
    if (!uniqueSlotIds.length) {
      this.commentsBySlotId.set({});
      this.commentsLoaded.set(true);
      return;
    }
    forkJoin(uniqueSlotIds.map((slotId) => this.teacherApi.getTeacherSlotComments({ bezirkSlug, slotId }).pipe(
      map((response) => [slotId, response.comments] as const),
      catchError(() => of([slotId, []] as const))
    ))).subscribe((entries) => {
      this.commentsBySlotId.set(Object.fromEntries(entries));
      this.commentsLoaded.set(true);
    });
  }

  private refreshComments(slotId: string, bezirkSlug: string): void {
    this.teacherApi.getTeacherSlotComments({ bezirkSlug, slotId }).subscribe({
      next: (response) => {
        this.commentsBySlotId.update((comments) => ({ ...comments, [slotId]: response.comments }));
        this.commentLoading.set(false);
      },
      error: (error) => {
        this.commentLoading.set(false);
        this.toastError(resolveApiError(error, this.i18n));
      }
    });
  }

  protected isOnline(category: EinAbCategory | null | undefined): boolean {
    return category === EinAbCategory.Online;
  }

  protected applyPrivateInfoTemplate(content: string | null): void {
    this.einabForm.controls.privateInfo.setValue(content ?? '');
  }

  /** Label of the "save as template" checkbox: per store, or per category for online EinAbs. */
  protected savePrivateInfoTemplateLabel(): string {
    const formValue = this.einabForm.getRawValue();
    if (this.isOnline(formValue.category)) {
      return this.i18n.t('teacher.savePrivateInfoTemplate');
    }
    const store = formValue.businessName.trim();
    return store
      ? this.i18n.t('teacher.savePrivateInfoStoreTemplateNamed').replace('{store}', store)
      : this.i18n.t('teacher.savePrivateInfoStoreTemplate');
  }

  private privateInfoTemplateLabel(category: EinAbCategory): string {
    return `${this.i18n.t('teacher.privateInfoUseTemplatePrefix')} ${this.i18n.categoryLabel(category)} ${this.i18n.t('teacher.privateInfoUseTemplateSuffix')}`;
  }

  /** Booked people first, free places after them. */
  protected slotsBookedFirst(einAb: TeacherEinAbResponse): SlotResponse[] {
    return [...einAb.slots].sort((a, b) => Number(!a.bookingUser) - Number(!b.bookingUser));
  }

  protected bezirkName(slug: string): string {
    return this.bezirke().find((bezirk) => bezirk.slug === slug)?.name ?? slug;
  }

  protected bookedSlotCount(einAb: TeacherEinAbResponse): number {
    return einAb.slots.filter((slot) => slot.status === SlotStatus.Booked).length;
  }

  private timePart(date: Date): string {
    return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
  }

  private configureOnlineFields(category: EinAbCategory | null): void {
    const isOnline = this.isOnline(category);
    const location = this.einabForm.controls.location;
    const publicLocation = this.einabForm.controls.publicLocation;
    const privateAddress = this.einabForm.controls.privateAddress;
    const publicGeneralLocation = this.einabForm.controls.publicGeneralLocation;
    const onlineCallLink = this.einabForm.controls.onlineCallLink;
    location.setValidators([]);
    publicLocation.setValidators([]);
    this.einabForm.controls.businessName.setValidators(isOnline ? [] : [Validators.required]);
    privateAddress.setValidators(isOnline ? [] : [Validators.required]);
    publicGeneralLocation.setValidators(isOnline ? [] : [Validators.required]);
    onlineCallLink.setValidators(isOnline ? [Validators.required] : []);
    if (isOnline) {
      this.einabForm.patchValue({ location: '', publicLocation: '', businessName: '', privateAddress: '', publicGeneralLocation: '', visitFairteiler: false, slotCount: 1, minimumPickupCount: 0 }, { emitEvent: false });
    } else {
      this.einabForm.patchValue({ onlineCallLink: '' }, { emitEvent: false });
    }
    location.updateValueAndValidity({ emitEvent: false });
    publicLocation.updateValueAndValidity({ emitEvent: false });
    this.einabForm.controls.businessName.updateValueAndValidity({ emitEvent: false });
    privateAddress.updateValueAndValidity({ emitEvent: false });
    publicGeneralLocation.updateValueAndValidity({ emitEvent: false });
    onlineCallLink.updateValueAndValidity({ emitEvent: false });
  }

  /** Switches the form away from a category the selected Bezirk has disabled (an edited EinAb keeps its own). */
  private ensureEnabledCategory(): void {
    const enabled = this.categoryOptions().map((option) => option.value);
    const current = this.einabForm.controls.category.value;
    if (current && enabled.length && !enabled.includes(current)) {
      this.einabForm.controls.category.setValue(null);
    }
  }

  /** A spontaneous past EinAb is recorded in the Bezirk new EinAbs default to. */
  protected pastEinAbBezirkSlug(): string {
    return this.defaultEinAbBezirkSlug();
  }

  protected pastEinAbCategoryOptions(): { label: string; value: EinAbCategory }[] {
    return this.bezirkContext.enabledCategories(this.defaultEinAbBezirkSlug())
      .map((value) => ({ label: this.i18n.categoryLabel(value), value }));
  }

  protected readonly searchPastEinAbUsers = (query: string) => this.teacherApi
    .searchTeacherBookingUsers({ bezirkSlug: this.defaultEinAbBezirkSlug(), query })
    .pipe(map((response) => response.users));

  protected onPastEinAbRecorded(): void {
    this.loadTeacherEinAbs(this.bezirkContext.currentSlug());
  }

  private defaultEinAbBezirkSlug(): string {
    const teacher = this.teacher();
    return teacher?.canManageUsers ? this.bezirkContext.currentSlug() : teacher?.bezirk?.slug ?? this.bezirkContext.currentSlug();
  }

  private requireActiveTeacher(): boolean {
    if (!this.teacher()?.bezirk) {
      this.toastError(this.i18n.t('teacher.unassignedHint'));
      return false;
    }
    if (this.teacher()?.active) {
      return true;
    }
    this.toastError(this.i18n.t('teacher.inactiveHint'));
    return false;
  }
}

/** New EinAbs default to tomorrow: same-day EinAbs are rare and easy to book by mistake. */
function tomorrow(): Date {
  const date = new Date();
  date.setDate(date.getDate() + 1);
  date.setHours(0, 0, 0, 0);
  return date;
}

function zurichDayKey(value: string | Date): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Zurich', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value));
}

/** Same key as the server (PrivateInfoTemplate.storeKeyFor): lower case letters and digits only. */
function privateInfoStoreKey(storeName: string | null | undefined): string {
  return (storeName ?? '').toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
}
