import { ActivatedRoute, Router } from '@angular/router';
import { AdminSupportPageComponent } from '../admin-support-page/admin-support-page.component';
import { AdminInfoNotesComponent } from '../admin-info-notes/admin-info-notes.component';
import { SupportBadgeService } from '../../core/support-badge.service';
import { CommonModule, NgTemplateOutlet } from '@angular/common';
import { Component, DestroyRef, ElementRef, OnInit, afterNextRender, computed, inject, signal, viewChild } from '@angular/core';
import { Menu, MenuModule } from 'primeng/menu';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';

import {
  AdminBezirkResponse,
  AdminBezirkSummary,
  BezirkDisableImpactResponse,
  BezirkDisableImpactUser,
  AdminBookingUserPageResponse,
  AdminBookingUserResponse,
  BookingCommentResponse,
  AdminEinAbBooking,
  AdminEinAbListResponse,
  AdminEinAbResponse,
  EinAbCategory,
  AdminService,
  FoodsharingMessagingTelegramStatus,
  TelegramChat,
  BezirkResponse,
  BookingDetailResponse,
  BookingUserResponse,
  BulkAddTeacherResult,
  BulkAddTeacherResultStatusEnum,
  CreateBookingCommentRequest,
  PublicService,
  SlotStatus
} from '../../api';
import { resolveApiError } from '../../core/api-error';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { ErrorNotifierService } from '../../core/error-notifier.service';
import { confirmAction } from '../../core/confirm-action';
import { I18nService } from '../../core/i18n.service';
import { LoadingIndicatorComponent } from '../../core/loading-indicator.component';
import { MarkdownViewComponent } from '../../core/markdown-view.component';
import { SessionService } from '../../core/session.service';
import { appStickyTop, trackStickyHeaders } from '../../core/sticky-headers';
import { ZurichDateTimePipe } from '../../core/zurich-date-time.pipe';
import { PastEinAbImportComponent } from './past-einab-import.component';
import { PastEinAbFormComponent, PastEinAbPerson } from '../past-einab-form/past-einab-form.component';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { CheckboxModule } from 'primeng/checkbox';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { DialogModule } from 'primeng/dialog';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { PaginatorModule } from 'primeng/paginator';
import { SelectModule } from 'primeng/select';
import { MultiSelectModule } from 'primeng/multiselect';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { TextareaModule } from 'primeng/textarea';
import { ConfirmationService, MenuItem, MessageService } from 'primeng/api';
import { Observable, finalize, map, of } from 'rxjs';

type PermissionKey =
  | 'canGiveEinAbs'
  | 'canManageUsers'
  | 'canManageSurveys'
  | 'canSeeUserPickupCountGrouping'
  | 'canUseAutomations'
  | 'canSeeAllAutomationDecisions';

type AdminTab = 'users' | 'einabs' | 'record' | 'add' | 'settings' | 'bezirke' | 'notes' | 'support';

type InfoLanguage = 'de' | 'gws' | 'en';

@Component({
  selector: 'app-admin-dashboard-page',
  standalone: true,
  imports: [
    AdminSupportPageComponent,
    AdminInfoNotesComponent,
    LoadingIndicatorComponent,
    MarkdownViewComponent,
    CommonModule,
    ZurichDateTimePipe,
    PastEinAbImportComponent,
    PastEinAbFormComponent,
    FormsModule,
    CardModule,
    ButtonModule,
    CheckboxModule,
    ConfirmDialogModule,
    DialogModule,
    InputNumberModule,
    InputTextModule,
    ReactiveFormsModule,
    PaginatorModule,
    SelectModule,
    MultiSelectModule,
    TableModule,
    TagModule,
    TextareaModule,
    MenuModule,
    NgTemplateOutlet
  ],
  templateUrl: './admin-dashboard-page.component.html',
  styleUrl: './admin-dashboard-page.component.scss'
})
export class AdminDashboardPageComponent implements OnInit {
  /** Tabs instead of six accordions; the daily task (users) comes first. */
  protected readonly adminTabs: { key: AdminTab; label: string; icon: string }[] = [
    { key: 'users', label: 'admin.tabUsers', icon: 'pi pi-users' },
    { key: 'einabs', label: 'admin.tabEinAbs', icon: 'pi pi-calendar' },
    { key: 'record', label: 'admin.tabRecord', icon: 'pi pi-history' },
    { key: 'add', label: 'admin.tabAdd', icon: 'pi pi-user-plus' },
    { key: 'settings', label: 'admin.tabSettings', icon: 'pi pi-cog' },
    { key: 'bezirke', label: 'admin.tabBezirke', icon: 'pi pi-map' },
    { key: 'notes', label: 'admin.tabInfoNotes', icon: 'pi pi-megaphone' },
    { key: 'support', label: 'admin.tabSupport', icon: 'pi pi-inbox' }
  ];
  protected readonly activeTab = signal<AdminTab>('users');
  protected readonly supportBadge = inject(SupportBadgeService);
  /** Rare or risky account actions (deactivate, remove verification) live in a menu, away from Verify. */
  protected readonly userActionItems = signal<MenuItem[]>([]);
  private readonly userActionsMenu = viewChild<Menu>('userActionsMenu');
  private static readonly ALL_BEZIRKE = '__all__';
  private static readonly UNASSIGNED_BEZIRK = '__unassigned__';

  readonly i18n = inject(I18nService);
  readonly SlotStatus = SlotStatus;

  protected readonly usersPage = signal<AdminBookingUserPageResponse | null>(null);
  protected readonly einAbsPage = signal<AdminEinAbListResponse | null>(null);
  protected readonly einAbsLoading = signal(true);
  protected readonly einAbDeleteTarget = signal<AdminEinAbResponse | null>(null);
  protected readonly einAbDeleteDialogVisible = signal(false);
  protected readonly einAbDeleteLoading = signal(false);
  protected readonly removingBookingSlotId = signal<string | null>(null);
  protected readonly einAbActionItems = signal<MenuItem[]>([]);
  private readonly einAbActionsMenu = viewChild<Menu>('einAbActionsMenu');
  private readonly zurichDateTime = new ZurichDateTimePipe();
  protected readonly einAbDeleteForm = inject(FormBuilder).nonNullable.group({
    reason: ['', [Validators.required, Validators.maxLength(1000)]]
  });
  protected readonly bezirkSettings = signal<AdminBezirkResponse | null>(null);
  protected readonly bezirke = signal<BezirkResponse[]>([]);
  /** All Bezirke including disabled ones, for creating and disabling Bezirke. */
  protected readonly adminBezirke = signal<AdminBezirkSummary[]>([]);
  protected readonly adminBezirkeLoading = signal(true);
  protected readonly activeAdminBezirkCount = computed(() => this.adminBezirke().filter((bezirk) => bezirk.active).length);
  protected readonly bezirkToggling = signal<string | null>(null);
  protected readonly bezirkDisableDialogVisible = signal(false);
  protected readonly bezirkDisableTarget = signal<AdminBezirkSummary | null>(null);
  protected readonly bezirkDisableImpact = signal<BezirkDisableImpactResponse | null>(null);
  protected readonly bezirkDisableImpactLoading = signal(false);
  protected readonly newBezirkName = signal('');
  protected readonly newBezirkSlug = signal('');
  /** The slug follows the name until the admin edits it. */
  private newBezirkSlugEdited = false;
  protected readonly creatingBezirk = signal(false);
  protected readonly canCreateBezirk = computed(() =>
    this.newBezirkName().trim().length > 0 && /^[a-z0-9]+(-[a-z0-9]+)*$/.test(this.newBezirkSlug()) && this.newBezirkSlug().length >= 2
  );
  protected readonly selectedUserBezirki = signal<Record<string, string | null>>({});
  protected readonly cleaningStoreId = signal<number | null>(null);
  protected readonly preventDuplicateTeacherBookings = signal(true);
  protected readonly preventDuplicateCategoryBookings = signal(true);
  protected readonly maxActiveBookingsPerUser = signal(3);
  protected readonly pickupEinAbLeadMinutes = signal(10);
  /** Bezirk info page (Markdown): optional onboarding step after the privacy consent, and reachable later. */
  protected readonly infoStepEnabled = signal(false);
  protected readonly infoStepOnlyUnverified = signal(false);
  /** The info text per app language; German is the fallback for an empty language. */
  protected readonly infoTexts = signal<Record<InfoLanguage, string>>({ de: '', gws: '', en: '' });
  protected readonly infoLanguage = signal<InfoLanguage>('de');
  protected readonly infoLanguages: { value: InfoLanguage; label: string }[] = [
    { value: 'de', label: 'lang.deLong' },
    { value: 'gws', label: 'lang.gwsLong' },
    { value: 'en', label: 'lang.enLong' }
  ];
  protected readonly infoText = computed(() => this.infoTexts()[this.infoLanguage()]);
  protected readonly hasAnyInfoText = computed(() => Object.values(this.infoTexts()).some((text) => text.trim()));
  protected readonly infoPreview = signal(false);
  protected readonly requireFairteilerVisitByThirdEinAb = signal(false);
  protected readonly useFoodsharingUserInfoApi = signal(false);
  protected readonly ignoredCategories = signal<EinAbCategory[]>([]);
  protected readonly disabledCategories = signal<EinAbCategory[]>([]);
  protected readonly foodsharingMessagingEmail = signal('');
  protected foodsharingMessagingPassword = '';
  protected readonly changingFoodsharingMessagingPassword = signal(false);
  protected readonly settingsSaving = signal(false);
  protected readonly messagingTelegram = signal<FoodsharingMessagingTelegramStatus | null>(null);
  protected readonly messagingTelegramChats = signal<TelegramChat[]>([]);
  protected readonly messagingTelegramChatId = signal<string | null>(null);
  protected readonly messagingTelegramEnabled = signal(true);
  protected readonly messagingTelegramBusy = signal(false);
  protected messagingTelegramBotToken = '';
  protected readonly messagingTelegramChatOptions = computed(() => this.messagingTelegramChats().map((chat) => ({
    label: chat.type ? `${chat.title} (${chat.type})` : chat.title,
    value: chat.id
  })));
  protected readonly onlyThreePickups = signal(false);
  protected readonly activeOnly = signal(true);
  protected readonly unverifiedOnly = signal(true);
  protected readonly selectedBezirkFilter = signal('');
  protected readonly userSearch = signal('');
  protected readonly newUserFoodsharingId = signal('');
  protected readonly creatingUser = signal(false);
  protected readonly bulkTeacherIdsText = signal('');
  protected readonly bulkTeacherIds = computed(() => parseFoodsharingIds(this.bulkTeacherIdsText()));
  protected readonly bulkTeachersSaving = signal(false);
  protected readonly bulkTeacherResults = signal<BulkAddTeacherResult[]>([]);
  protected readonly bulkTeacherRefreshQueued = signal(0);
  protected readonly usersLoading = signal(true);
  protected readonly refreshingUserIds = signal<Record<string, boolean>>({});
  protected readonly expandedPickupUserIds = signal<Record<string, boolean>>({});
  protected readonly commentLoading = signal(false);
  protected readonly commentDialogVisible = signal(false);
  protected readonly commentTarget = signal<BookingDetailResponse | null>(null);
  protected readonly commentForm = inject(FormBuilder).nonNullable.group({
    comment: ['', [Validators.required, Validators.minLength(2)]]
  });

  protected readonly pageSize = 20;
  protected readonly allCategoryOptions = Object.values(EinAbCategory).map((value) => ({ label: this.i18n.categoryLabel(value), value }));
  protected readonly categoryOptions = computed(() => this.bezirkContext.enabledCategories(this.bezirkContext.selectedBezirk()?.slug)
    .map((value) => ({ label: this.i18n.categoryLabel(value), value })));
  /** Person search of the past EinAb form: asks the server as the admin types (the form debounces). */
  protected readonly searchMigrationUsers = (query: string): Observable<PastEinAbPerson[]> => this.adminApi
    .searchAdminBookingUsers({ bezirkSlug: this.bezirkContext.currentSlug(), query })
    .pipe(map((response) => response.users ?? []));

  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly adminApi = inject(AdminService);
  private readonly publicApi = inject(PublicService);
  private readonly bezirkContext = inject(BezirkContextService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly messageService = inject(MessageService);
  private readonly errorNotifier = inject(ErrorNotifierService);
  private readonly sessionService = inject(SessionService);
  private readonly hostElement = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly stickyUserHeaders = afterNextRender(() =>
    trackStickyHeaders(this.hostElement.nativeElement, '[data-sticky-user]', this.destroyRef)
  );

  ngOnInit(): void {
    this.selectedBezirkFilter.set(this.bezirkContext.currentSlug());
    this.loadBezirkSettings();
    this.loadMessagingTelegram();
    this.loadBezirke();
    this.loadAdminBezirke();
    // The Foodsharing message about a new support request links to ?tab=support.
    const presetTab = this.route.snapshot.queryParamMap.get('tab');
    if (presetTab && this.adminTabs.some((tab) => tab.key === presetTab)) {
      // Through selectTab, so a link to ?tab=einabs also loads the EinAbs instead of spinning forever.
      this.selectTab(presetTab as AdminTab);
    }
    const presetSearch = this.route.snapshot.queryParamMap.get('userSearch');
    if (presetSearch) {
      this.userSearch.set(presetSearch);
    }
    this.loadUsersPage(presetSearch ? 0 : this.usersPage()?.page ?? 0);
  }

  loadUsersPage(page: number): void {
    this.usersLoading.set(true);
    const selectedBezirkFilter = this.selectedBezirkFilter();
    const bezirkSlug = selectedBezirkFilter && selectedBezirkFilter !== AdminDashboardPageComponent.ALL_BEZIRKE && selectedBezirkFilter !== AdminDashboardPageComponent.UNASSIGNED_BEZIRK
      ? selectedBezirkFilter
      : this.bezirkContext.currentSlug();
    this.adminApi.getAdminUsers({
      bezirkSlug,
      search: this.userSearch().trim() || undefined,
      page,
      size: this.pageSize,
      threePickupsOnly: this.onlyThreePickups(),
      activeOnly: this.activeOnly(),
      unverifiedOnly: this.unverifiedOnly(),
      unassigned: selectedBezirkFilter === AdminDashboardPageComponent.UNASSIGNED_BEZIRK,
      allBezirke: selectedBezirkFilter === AdminDashboardPageComponent.ALL_BEZIRKE
    }).subscribe({
      next: (response) => {
        this.usersPage.set(response);
        this.selectedUserBezirki.set(Object.fromEntries((response.users ?? []).map((user) => [user.user.id, user.user.bezirk?.slug ?? null])));
        this.usersLoading.set(false);
      },
      error: (error) => {
        this.usersLoading.set(false);
        this.toastError(resolveApiError(error, this.i18n));
      }
    });
  }

  onUsersPageChange(event: { page?: number }): void {
    this.loadUsersPage(event.page ?? 0);
  }

  loadEinAbsPage(page: number): void {
    this.einAbsLoading.set(true);
    this.adminApi.getAdminEinAbs({
      bezirkSlug: this.bezirkContext.currentSlug(),
      page,
      size: this.pageSize
    }).subscribe({
      next: (response) => {
        this.einAbsPage.set(response);
        this.einAbsLoading.set(false);
      },
      error: (error) => {
        this.einAbsLoading.set(false);
        this.toastError(resolveApiError(error, this.i18n));
      }
    });
  }

  onEinAbsPageChange(event: { page?: number }): void {
    this.loadEinAbsPage(event.page ?? 0);
  }

  selectTab(tab: AdminTab): void {
    this.activeTab.set(tab);
    if (tab === 'einabs') {
      this.loadEinAbsPage(0);
    }
  }

  onEinAbsToggle(event: Event): void {
    if ((event.target as HTMLDetailsElement).open) {
      this.loadEinAbsPage(0);
    }
  }

  openEinAbActions(event: Event, einAb: AdminEinAbResponse): void {
    this.einAbActionItems.set([
      { label: this.i18n.t('common.delete'), icon: 'pi pi-trash', styleClass: 'menu-item--danger', command: () => this.confirmDeleteEinAb(einAb) }
    ]);
    this.einAbActionsMenu()?.toggle(event);
  }

  protected isOnlineEinAb(einAb: AdminEinAbResponse): boolean {
    return einAb.category === EinAbCategory.Online;
  }

  protected einAbLocation(einAb: AdminEinAbResponse): string {
    if (this.isOnlineEinAb(einAb)) {
      return this.i18n.t('category.online');
    }
    const parts = [einAb.businessName, einAb.publicLocation ?? einAb.location].filter((part) => !!part?.trim());
    return parts.length ? parts.join(' · ') : '-';
  }

  /** One placeholder per place that nobody has booked yet, shown as "Freier Platz" like in Meine EinAbs. */
  protected freeSlotPlaceholders(einAb: AdminEinAbResponse): number[] {
    return Array.from({ length: Math.max(0, einAb.slotCount - einAb.bookings.length) }, (_, index) => index);
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

  confirmDeleteEinAb(einAb: AdminEinAbResponse): void {
    if (!this.isOwnEinAb(einAb)) {
      this.einAbDeleteForm.reset();
      this.einAbDeleteTarget.set(einAb);
      this.einAbDeleteDialogVisible.set(true);
      return;
    }
    const message = einAb.bookings.length
      ? `${this.i18n.t('confirm.deleteEinab')} ${this.deleteEinAbBookedNotice(einAb)}`
      : this.i18n.t('confirm.deleteEinab');
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('confirmDialog.deleteEinab.title'),
      acceptLabel: this.i18n.t('confirmDialog.delete.accept'),
      destructive: true,
      message,
      accept: () => {
        this.confirmationService.close();
        this.adminApi.deleteAdminEinAb({ bezirkSlug: einAb.bezirk.slug, einAbId: einAb.id }).subscribe({
          next: () => this.loadEinAbsPage(this.einAbsPage()?.page ?? 0),
          error: (error) => this.toastError(resolveApiError(error, this.i18n))
        });
      }
    });
  }

  saveEinAbDeletion(): void {
    const einAb = this.einAbDeleteTarget();
    if (!einAb || this.einAbDeleteForm.invalid) {
      return;
    }
    this.einAbDeleteLoading.set(true);
    this.adminApi.deleteAdminEinAb({
      bezirkSlug: einAb.bezirk.slug,
      einAbId: einAb.id,
      deleteAdminEinAbRequest: { reason: this.einAbDeleteForm.getRawValue().reason.trim() }
    }).pipe(finalize(() => this.einAbDeleteLoading.set(false))).subscribe({
      next: () => {
        this.einAbDeleteDialogVisible.set(false);
        this.loadEinAbsPage(this.einAbsPage()?.page ?? 0);
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  protected deleteEinAbBookedNotice(einAb: AdminEinAbResponse): string {
    const names = einAb.bookings.map((booking) => booking.name).join(', ');
    return this.i18n.t(einAb.bookings.length === 1 ? 'admin.deleteEinAbBookedOne' : 'admin.deleteEinAbBookedMany')
      .replace('{0}', names);
  }

  confirmRemoveBooking(einAb: AdminEinAbResponse, booking: AdminEinAbBooking): void {
    this.confirmAndRemoveBooking(einAb.bezirk.slug, booking.slotId, booking.name, einAb.startDateTime,
      () => this.loadEinAbsPage(this.einAbsPage()?.page ?? 0));
  }

  /** Admin unbooks a user from one of their upcoming EinAbs (user details list). */
  confirmRemoveUserBooking(booking: BookingDetailResponse): void {
    if (!this.canRemoveUserBooking(booking)) {
      return;
    }
    this.confirmAndRemoveBooking(booking.bezirk.slug, booking.slotId, booking.bookingUser?.name ?? '', booking.startDateTime,
      () => this.loadUsersPage(this.usersPage()?.page ?? 0));
  }

  protected canRemoveUserBooking(booking: BookingDetailResponse): boolean {
    return booking.status === SlotStatus.Booked && !this.isPast(booking.startDateTime);
  }

  private confirmAndRemoveBooking(bezirkSlug: string, slotId: string, name: string, startDateTime: string, reload: () => void): void {
    const date = this.zurichDateTime.transform(startDateTime);
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('confirmDialog.removeFromEinAb.title'),
      acceptLabel: this.i18n.t('confirmDialog.removeFromEinAb.accept'),
      rejectLabel: this.i18n.t('common.keep'),
      destructive: true,
      message: this.i18n.t('confirm.removeFromEinAb').replace('{0}', name).replace('{1}', date),
      accept: () => {
        this.confirmationService.close();
        this.removingBookingSlotId.set(slotId);
        this.adminApi.removeAdminSlotBooking({ bezirkSlug, slotId })
          .pipe(finalize(() => this.removingBookingSlotId.set(null)))
          .subscribe({
            next: () => {
              this.messageService.add({ severity: 'success', summary: this.i18n.t('admin.removedFromEinAb').replace('{0}', name) });
              reload();
            },
            error: (error) => this.toastError(resolveApiError(error, this.i18n))
          });
      }
    });
  }

  private isOwnEinAb(einAb: AdminEinAbResponse): boolean {
    return einAb.teacher.foodsharingId === this.sessionService.session()?.foodsharingId;
  }

  setOnlyThreePickups(checked: boolean): void {
    this.onlyThreePickups.set(checked);
    this.loadUsersPage(0);
  }

  setActiveOnly(checked: boolean): void {
    this.activeOnly.set(checked);
    this.loadUsersPage(0);
  }

  setUnverifiedOnly(checked: boolean): void {
    this.unverifiedOnly.set(checked);
    this.loadUsersPage(0);
  }

  toggleIgnoredCategory(category: EinAbCategory, checked: boolean): void {
    this.ignoredCategories.update((categories) => checked
      ? [...new Set([...categories, category])]
      : categories.filter((entry) => entry !== category));
  }

  toggleEnabledCategory(category: EinAbCategory, enabled: boolean): void {
    this.disabledCategories.update((categories) => enabled
      ? categories.filter((entry) => entry !== category)
      : [...new Set([...categories, category])]);
  }

  setBezirkFilter(bezirkSlug: string): void {
    this.selectedBezirkFilter.set(bezirkSlug);
    this.loadUsersPage(0);
  }

  setUserSearch(search: string): void {
    this.userSearch.set(search);
    this.loadUsersPage(0);
  }

  createBookingUser(): void {
    const foodsharingId = this.newUserFoodsharingId().trim();
    if (!foodsharingId || this.creatingUser()) {
      return;
    }
    this.creatingUser.set(true);
    this.adminApi.createAdminBookingUser({
      createBookingUserRequest: {
        foodsharingId,
        bezirkSlug: this.bezirkContext.currentSlug()
      }
    }).subscribe({
      next: () => {
        this.newUserFoodsharingId.set('');
        this.creatingUser.set(false);
        this.messageService.add({ severity: 'success', summary: this.i18n.t('common.created') });
        this.loadUsersPage(0);
      },
      error: (error) => {
        this.creatingUser.set(false);
        this.toastError(resolveApiError(error, this.i18n));
      }
    });
  }

  addBulkTeachers(): void {
    const foodsharingIds = this.bulkTeacherIds();
    if (foodsharingIds.length === 0 || this.bulkTeachersSaving()) {
      return;
    }
    this.bulkTeachersSaving.set(true);
    this.adminApi.bulkAddAdminTeachers({
      bulkAddTeachersRequest: {
        foodsharingIds,
        bezirkSlug: this.bezirkContext.currentSlug()
      }
    }).pipe(finalize(() => this.bulkTeachersSaving.set(false))).subscribe({
      next: (response) => {
        this.bulkTeacherResults.set(response.results);
        this.bulkTeacherRefreshQueued.set(response.refreshQueued ?? 0);
        const failedIds = response.results
          .filter((result) => result.status === BulkAddTeacherResultStatusEnum.Failed)
          .map((result) => result.foodsharingId);
        // Keep only the IDs that failed in the box, so they can be fixed and retried.
        this.bulkTeacherIdsText.set(failedIds.join('\n'));
        this.loadUsersPage(0);
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  bulkTeacherSeverity(result: BulkAddTeacherResult): 'success' | 'info' | 'secondary' | 'danger' {
    switch (result.status) {
      case BulkAddTeacherResultStatusEnum.Created: return 'success';
      case BulkAddTeacherResultStatusEnum.Granted: return 'info';
      case BulkAddTeacherResultStatusEnum.AlreadyTeacher: return 'secondary';
      default: return 'danger';
    }
  }

  bulkTeacherError(result: BulkAddTeacherResult): string {
    if (!result.errorCode) {
      return '';
    }
    const specificKey = `admin.bulkTeachers.error.${result.errorCode}`;
    const specific = this.i18n.t(specificKey);
    return specific !== specificKey ? specific : this.i18n.errorLabel(result.errorCode);
  }

  protected currentAdminName(): string {
    return this.sessionService.session()?.displayName ?? '';
  }

  protected currentBezirkSlug(): string {
    return this.bezirkContext.currentSlug();
  }

  saveBezirkSettings(): void {
    this.settingsSaving.set(true);
    this.adminApi.updateAdminBezirk({
      bezirkSlug: this.bezirkContext.currentSlug(),
      updateBezirkRequest: {
        cleaningStoreId: this.cleaningStoreId(),
        preventDuplicateTeacherBookings: this.preventDuplicateTeacherBookings(),
        preventDuplicateCategoryBookings: this.preventDuplicateCategoryBookings(),
        maxActiveBookingsPerUser: this.maxActiveBookingsPerUser(),
        pickupEinAbLeadMinutes: this.pickupEinAbLeadMinutes() ?? 10,
        requireFairteilerVisitByThirdEinAb: this.requireFairteilerVisitByThirdEinAb(),
        useFoodsharingUserInfoApi: this.useFoodsharingUserInfoApi(),
        foodsharingMessagingEmail: this.foodsharingMessagingEmail().trim(),
        foodsharingMessagingPassword: this.foodsharingMessagingPassword || undefined,
        ignoredCategories: this.ignoredCategories(),
        disabledCategories: this.disabledCategories(),
        infoStepEnabled: this.infoStepEnabled(),
        infoStepOnlyUnverified: this.infoStepOnlyUnverified(),
        infoMarkdown: this.infoTexts().de,
        infoMarkdownGws: this.infoTexts().gws,
        infoMarkdownEn: this.infoTexts().en
      }
    }).subscribe({
      next: (response) => {
        this.bezirkSettings.set(response);
        this.cleaningStoreId.set(response.cleaningStoreId ?? null);
        this.preventDuplicateTeacherBookings.set(response.preventDuplicateTeacherBookings ?? true);
        this.preventDuplicateCategoryBookings.set(response.preventDuplicateCategoryBookings ?? true);
        this.maxActiveBookingsPerUser.set(response.maxActiveBookingsPerUser ?? 3);
        this.pickupEinAbLeadMinutes.set(response.pickupEinAbLeadMinutes ?? 10);
        this.requireFairteilerVisitByThirdEinAb.set(response.requireFairteilerVisitByThirdEinAb ?? false);
        this.useFoodsharingUserInfoApi.set(response.useFoodsharingUserInfoApi ?? false);
        this.foodsharingMessagingEmail.set(response.foodsharingMessagingEmail ?? '');
        this.foodsharingMessagingPassword = '';
        this.ignoredCategories.set(response.ignoredCategories ?? []);
        this.applyDisabledCategories(response.disabledCategories ?? []);
        this.applyBezirkInfo(response);
        this.bezirkContext.setInfoAvailable(response.slug, response.infoAvailable === true);
        this.settingsSaving.set(false);
        this.changingFoodsharingMessagingPassword.set(false);
        this.loadMessagingTelegram();
        this.messageService.add({ severity: 'success', summary: this.i18n.t('common.saved') });
      },
      error: (error) => {
        this.settingsSaving.set(false);
        this.toastError(resolveApiError(error, this.i18n));
      }
    });
  }

  saveMessagingTelegram(enabled = this.messagingTelegramEnabled()): void {
    if (this.messagingTelegramBusy()) return;
    const chatId = this.messagingTelegramChatId();
    const chat = this.messagingTelegramChats().find((value) => value.id === chatId);
    this.messagingTelegramBusy.set(true);
    this.adminApi.updateFoodsharingMessagingTelegram({
      bezirkSlug: this.bezirkContext.currentSlug(),
      foodsharingMessagingTelegramRequest: {
        telegramBotToken: this.messagingTelegramBotToken.trim() || undefined,
        telegramChatId: chatId,
        telegramChatTitle: chat?.title ?? null,
        enabled
      }
    }).subscribe({
      next: (response) => {
        const tokenWasAdded = !!this.messagingTelegramBotToken.trim();
        this.messagingTelegramBusy.set(false);
        this.applyMessagingTelegram(response);
        this.messageService.add({ severity: 'success', summary: this.i18n.t('common.saved') });
        if (tokenWasAdded) this.refreshMessagingTelegramChats();
      },
      error: (error) => {
        this.messagingTelegramBusy.set(false);
        this.toastError(resolveApiError(error, this.i18n));
      }
    });
  }

  refreshMessagingTelegramChats(): void {
    this.adminApi.getFoodsharingMessagingTelegramChats({ bezirkSlug: this.bezirkContext.currentSlug() }).subscribe({
      next: (chats) => {
        this.messagingTelegramChats.set(chats);
        if (!chats.length) {
          this.messageService.add({ severity: 'info', summary: this.i18n.t('admin.messagingTelegramNoChats') });
        }
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  sendMessagingTelegramTestMessage(): void {
    this.messagingTelegramBusy.set(true);
    this.adminApi.sendFoodsharingMessagingTelegramTestMessage({ bezirkSlug: this.bezirkContext.currentSlug() }).subscribe({
      next: () => {
        this.messagingTelegramBusy.set(false);
        this.messageService.add({ severity: 'success', summary: this.i18n.t('admin.messagingTelegramTestSent') });
      },
      error: (error) => {
        this.messagingTelegramBusy.set(false);
        this.toastError(resolveApiError(error, this.i18n));
      }
    });
  }

  removeMessagingTelegram(): void {
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('confirmDialog.removeTelegram.title'),
      acceptLabel: this.i18n.t('confirmDialog.remove.accept'),
      destructive: true,
      message: this.i18n.t('confirm.removeMessagingTelegram'),
      accept: () => {
        this.confirmationService.close();
        this.adminApi.deleteFoodsharingMessagingTelegram({ bezirkSlug: this.bezirkContext.currentSlug() }).subscribe({
          next: () => {
            this.messagingTelegramChats.set([]);
            this.loadMessagingTelegram();
          },
          error: (error) => this.toastError(resolveApiError(error, this.i18n))
        });
      }
    });
  }

  private loadMessagingTelegram(): void {
    this.adminApi.getFoodsharingMessagingTelegram({ bezirkSlug: this.bezirkContext.currentSlug() }).subscribe({
      next: (response) => {
        this.applyMessagingTelegram(response);
        if (response.telegramBotTokenConfigured && !this.messagingTelegramChats().length && response.telegramChatId) {
          this.messagingTelegramChats.set([{ id: response.telegramChatId, title: response.telegramChatTitle ?? response.telegramChatId, type: '' }]);
        }
      },
      error: () => this.messagingTelegram.set(null)
    });
  }

  private applyMessagingTelegram(response: FoodsharingMessagingTelegramStatus): void {
    this.messagingTelegram.set(response);
    this.messagingTelegramBotToken = '';
    this.messagingTelegramChatId.set(response.telegramChatId ?? null);
    this.messagingTelegramEnabled.set(response.telegramBotTokenConfigured && response.telegramChatId ? response.enabled : true);
  }

  cancelFoodsharingMessagingPasswordChange(): void {
    this.foodsharingMessagingPassword = '';
    this.changingFoodsharingMessagingPassword.set(false);
  }

  removeFoodsharingMessagingConnection(): void {
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('confirmDialog.removeMessagingAccount.title'),
      acceptLabel: this.i18n.t('confirmDialog.remove.accept'),
      destructive: true,
      message: this.i18n.t('confirm.removeFoodsharingMessagingAccount'),
      accept: () => {
        this.confirmationService.close();
        this.foodsharingMessagingEmail.set('');
        this.foodsharingMessagingPassword = '';
        this.saveBezirkSettings();
      }
    });
  }


  saveUserPermissions(user: AdminBookingUserResponse): void {
    this.adminApi.setAdminUserPermissions({
      userId: user.user.id,
      userPermissionsRequest: {
        canGiveEinAbs: user.user.canGiveEinAbs,
        canManageUsers: user.user.canManageUsers,
        canUseAutomations: user.user.canUseAutomations,
        canSeeUserPickupCountGrouping: user.user.canSeeUserPickupCountGrouping,
        canSeeAllAutomationDecisions: user.user.canSeeAllAutomationDecisions,
        canManageSurveys: user.user.canManageSurveys
      }
    }).subscribe({
      // Keep the list as it is so the user stays open; the checkboxes already hold the saved values.
      next: () => this.messageService.add({
        severity: 'success',
        summary: this.i18n.t('common.saved'),
        detail: `${this.i18n.t('admin.userPermissions')}: ${user.user.name}`
      }),
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  selectedBezirkSlug(user: AdminBookingUserResponse): string | null {
    const selectedBezirki = this.selectedUserBezirki();
    return Object.prototype.hasOwnProperty.call(selectedBezirki, user.user.id)
      ? selectedBezirki[user.user.id]
      : user.user.bezirk?.slug ?? null;
  }

  setSelectedBezirkSlug(user: AdminBookingUserResponse, bezirkSlug: string | null): void {
    const previousBezirkSlug = this.selectedBezirkSlug(user);
    if (previousBezirkSlug === bezirkSlug) {
      return;
    }
    this.selectedUserBezirki.update((current) => ({ ...current, [user.user.id]: bezirkSlug }));
    this.adminApi.updateAdminUserBezirk({
      userId: user.user.id,
      updateUserBezirkRequest: {
        bezirkSlug
      }
    }).subscribe({
      next: (updatedUser) => {
        this.patchUser(updatedUser);
        this.messageService.add({ severity: 'success', summary: this.i18n.t('common.saved') });
      },
      error: (error) => {
        this.selectedUserBezirki.update((current) => ({ ...current, [user.user.id]: previousBezirkSlug }));
        this.toastError(resolveApiError(error, this.i18n));
      }
    });
  }

  disableBookingUser(user: AdminBookingUserResponse): void {
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('confirmDialog.disableUser.title'),
      acceptLabel: this.i18n.t('confirmDialog.disableUser.accept'),
      destructive: true,
      message: this.i18n.t('confirm.disableBookingUser'),
      accept: () => {
        this.confirmationService.close();
        this.adminApi.disableAdminBookingUser({ bookingUserId: user.user.id }).subscribe({
          next: () => this.loadUsersPage(this.usersPage()?.page ?? 0),
          error: (error) => this.toastError(resolveApiError(error, this.i18n))
        });
      }
    });
  }

  openUserActions(event: Event, user: AdminBookingUserResponse): void {
    const items: MenuItem[] = [];
    if (user.user.verified) {
      items.push({ label: this.i18n.t('admin.unverifyBookingUser'), icon: 'pi pi-times-circle', command: () => this.unverifyBookingUser(user) });
    }
    items.push(user.user.active
      ? { label: this.i18n.t('admin.disableBookingUser'), icon: 'pi pi-ban', styleClass: 'menu-item--danger', command: () => this.disableBookingUser(user) }
      : { label: this.i18n.t('admin.enableBookingUser'), icon: 'pi pi-check', command: () => this.enableBookingUser(user) });
    this.userActionItems.set(items);
    this.userActionsMenu()?.toggle(event);
  }

  /** Not verified yet and would show up under "Bereit zum Verifizieren" (the server uses the same check for both). */
  protected isReadyToVerify(user: AdminBookingUserResponse): boolean {
    return user.user.active && !user.user.verified && user.readyToVerify;
  }

  /** "vor 4 Tagen" instead of a full timestamp that wraps on phones. */
  protected relativeTime(value: string | undefined | null): string {
    if (!value) {
      return '-';
    }
    const diff = new Date(value).getTime() - Date.now();
    const formatter = new Intl.RelativeTimeFormat(this.i18n.language() === 'en' ? 'en' : 'de', { numeric: 'auto' });
    const minutes = Math.round(diff / 60_000);
    if (Math.abs(minutes) < 60) return formatter.format(minutes, 'minute');
    const hours = Math.round(minutes / 60);
    if (Math.abs(hours) < 24) return formatter.format(hours, 'hour');
    const days = Math.round(hours / 24);
    if (Math.abs(days) < 30) return formatter.format(days, 'day');
    const months = Math.round(days / 30);
    if (Math.abs(months) < 12) return formatter.format(months, 'month');
    return formatter.format(Math.round(months / 12), 'year');
  }

  verifyBookingUser(user: AdminBookingUserResponse): void {
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('confirmDialog.verifyUser.title'),
      acceptLabel: this.i18n.t('confirmDialog.verifyUser.accept'),
      message: this.i18n.t('confirm.verifyBookingUser'),
      accept: () => {
        this.confirmationService.close();
        this.adminApi.verifyAdminBookingUser({ bookingUserId: user.user.id }).subscribe({
          next: () => {
            this.messageService.add({ severity: 'success', summary: this.i18n.t('admin.userVerified') });
            this.loadUsersPage(this.usersPage()?.page ?? 0);
          },
          error: (error) => this.toastError(resolveApiError(error, this.i18n))
        });
      }
    });
  }

  unverifyBookingUser(user: AdminBookingUserResponse): void {
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('confirmDialog.unverifyUser.title'),
      acceptLabel: this.i18n.t('confirmDialog.unverifyUser.accept'),
      destructive: true,
      message: this.i18n.t('confirm.unverifyBookingUser'),
      accept: () => {
        this.confirmationService.close();
        this.adminApi.unverifyAdminBookingUser({ bookingUserId: user.user.id }).subscribe({
          next: () => {
            this.messageService.add({ severity: 'success', summary: this.i18n.t('admin.userUnverified') });
            this.loadUsersPage(this.usersPage()?.page ?? 0);
          },
          error: (error) => this.toastError(resolveApiError(error, this.i18n))
        });
      }
    });
  }

  setVerificationMessageSent(user: AdminBookingUserResponse, sent: boolean): void {
    this.adminApi
      .setAdminBookingUserVerificationMessageSent({
        bookingUserId: user.user.id,
        adminVerificationMessageSentRequest: { sent }
      })
      .subscribe({
        next: (updatedUser) => this.patchUser(updatedUser),
        error: (error) => {
          this.toastError(resolveApiError(error, this.i18n));
          this.loadUsersPage(this.usersPage()?.page ?? 0);
        }
      });
  }

  enableBookingUser(user: AdminBookingUserResponse): void {
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('confirmDialog.enableUser.title'),
      acceptLabel: this.i18n.t('confirmDialog.enableUser.accept'),
      message: this.i18n.t('confirm.enableBookingUser'),
      accept: () => {
        this.confirmationService.close();
        this.adminApi.enableAdminBookingUser({ bookingUserId: user.user.id }).subscribe({
          next: () => this.loadUsersPage(this.usersPage()?.page ?? 0),
          error: (error) => this.toastError(resolveApiError(error, this.i18n))
        });
      }
    });
  }

  refreshUserData(user: AdminBookingUserResponse): void {
    if (this.isRefreshingUser(user)) {
      return;
    }
    this.refreshingUserIds.update((current) => ({ ...current, [user.user.id]: true }));
    this.adminApi.refreshAdminBookingUser({ bookingUserId: user.user.id }).subscribe({
      next: (updatedUser) => {
        this.clearRefreshingUser(user);
        this.patchUser(updatedUser);
        this.messageService.add({ severity: 'success', summary: this.i18n.t('admin.userDataRefreshed') });
      },
      error: (error) => {
        this.clearRefreshingUser(user);
        this.toastError(resolveApiError(error, this.i18n));
      }
    });
  }

  isRefreshingUser(user: AdminBookingUserResponse): boolean {
    return this.refreshingUserIds()[user.user.id] === true;
  }

  confirmBookingCount(booking: BookingDetailResponse, counted: boolean): void {
    if (!this.isPast(booking.startDateTime)) {
      return;
    }
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t(counted ? 'confirmDialog.countAgain.title' : 'confirmDialog.markDidNotShowUp.title'),
      acceptLabel: this.i18n.t(counted ? 'confirmDialog.countAgain.accept' : 'confirmDialog.markDidNotShowUp.accept'),
      destructive: !counted,
      message: this.i18n.t(counted ? 'confirm.countAgain' : 'confirm.markDidNotShowUp'),
      accept: () => {
        this.adminApi.setAdminBookingCounted({
          bezirkSlug: booking.bezirk.slug,
          slotId: booking.slotId,
          adminBookingCountRequest: { counted }
        }).subscribe({
          next: () => this.loadUsersPage(this.usersPage()?.page ?? 0),
          error: (error) => this.toastError(resolveApiError(error, this.i18n))
        });
      }
    });
  }

  openCommentDialog(booking: BookingDetailResponse): void {
    if (!booking.bookingUser) {
      return;
    }
    this.commentTarget.set(booking);
    this.commentForm.reset({ comment: '' });
    this.commentLoading.set(false);
    this.commentDialogVisible.set(true);
  }

  saveComment(): void {
    const booking = this.commentTarget();
    const comment = this.commentForm.getRawValue().comment.trim();
    if (!booking?.bookingUser || !comment || this.commentForm.invalid || this.commentLoading()) {
      return;
    }

    this.commentLoading.set(true);
    const createBookingCommentRequest: CreateBookingCommentRequest = { comment };
    this.adminApi.addAdminSlotComment({
      bezirkSlug: booking.bezirk.slug,
      slotId: booking.slotId,
      createBookingCommentRequest
    }).subscribe({
      next: () => {
        this.commentLoading.set(false);
        this.commentDialogVisible.set(false);
        this.commentTarget.set(null);
        this.loadUsersPage(this.usersPage()?.page ?? 0);
      },
      error: (error) => {
        this.commentLoading.set(false);
        this.toastError(resolveApiError(error, this.i18n));
      }
    });
  }

  userPanels(): AdminBookingUserResponse[] {
    return this.usersPage()?.users ?? [];
  }

  userFilterOptions(): { label: string; value: string }[] {
    return [
      { label: this.i18n.t('admin.allBezirke'), value: AdminDashboardPageComponent.ALL_BEZIRKE },
      { label: this.i18n.t('bezirk.unassigned'), value: AdminDashboardPageComponent.UNASSIGNED_BEZIRK },
      ...this.bezirke().map((bezirk) => ({ label: bezirk.name, value: bezirk.slug }))
    ];
  }

  protected permissionOptions(): { key: PermissionKey; label: string; help: string }[] {
    const keys: PermissionKey[] = [
      'canGiveEinAbs',
      'canManageUsers',
      'canManageSurveys',
      'canSeeUserPickupCountGrouping',
      'canUseAutomations',
      'canSeeAllAutomationDecisions'
    ];
    return keys
      .map((key) => ({ key, label: this.i18n.t(`permission.${key}`), help: this.i18n.t(`permissionHelp.${key}`) }))
      .sort((left, right) => left.label.localeCompare(right.label));
  }

  bezirkOptions(): { label: string; value: string | null }[] {
    return [
      { label: this.i18n.t('bezirk.unassigned'), value: null },
      ...this.bezirke().map((bezirk) => ({ label: bezirk.name, value: bezirk.slug }))
    ];
  }

  toggleUserPickups(user: AdminBookingUserResponse): void {
    if (this.isUserPickupsExpanded(user)) {
      this.scrollToUser(user);
    }
    this.expandedPickupUserIds.update((current) => {
      const next = { ...current };
      if (next[user.user.id]) {
        delete next[user.user.id];
      } else {
        next[user.user.id] = true;
      }
      return next;
    });
  }

  /** After closing a user whose header was pinned, bring that user back to the top of the screen. */
  protected scrollToUser(user: AdminBookingUserResponse): void {
    const host = this.hostElement.nativeElement;
    const id = CSS.escape(user.user.id);
    const pinned = host.querySelector<HTMLElement>(`[data-sticky-user="${id}"].is-stuck`);
    if (!pinned) {
      return;
    }
    setTimeout(() => {
      const target = host.querySelector<HTMLElement>(`[data-user-row="${id}"]`)?.offsetParent
        ? host.querySelector<HTMLElement>(`[data-user-row="${id}"]`)
        : pinned.closest<HTMLElement>('.admin-user-card');
      if (target) {
        window.scrollBy({ top: target.getBoundingClientRect().top - appStickyTop() - 8 });
      }
    });
  }

  isUserPickupsExpanded(user: AdminBookingUserResponse): boolean {
    return this.expandedPickupUserIds()[user.user.id];
  }

  protected slotComments(user: AdminBookingUserResponse, slotId: string): BookingCommentResponse[] {
    return user.comments.filter((comment) => comment.slotId === slotId);
  }

  protected isPast(startDateTime: string): boolean {
    return new Date(startDateTime).getTime() < Date.now();
  }

  private applyBezirkInfo(response: AdminBezirkResponse): void {
    this.infoStepEnabled.set(response.infoStepEnabled ?? false);
    this.infoStepOnlyUnverified.set(response.infoStepOnlyUnverified ?? false);
    this.infoTexts.set({ de: response.infoMarkdown ?? '', gws: response.infoMarkdownGws ?? '', en: response.infoMarkdownEn ?? '' });
  }

  protected setInfoText(text: string): void {
    const language = this.infoLanguage();
    this.infoTexts.update((texts) => ({ ...texts, [language]: text }));
  }

  private loadBezirkSettings(): void {
    this.adminApi.getAdminBezirk({ bezirkSlug: this.bezirkContext.currentSlug() }).subscribe({
      next: (response) => {
        this.bezirkSettings.set(response);
        this.cleaningStoreId.set(response.cleaningStoreId ?? null);
        this.preventDuplicateTeacherBookings.set(response.preventDuplicateTeacherBookings ?? true);
        this.preventDuplicateCategoryBookings.set(response.preventDuplicateCategoryBookings ?? true);
        this.maxActiveBookingsPerUser.set(response.maxActiveBookingsPerUser ?? 3);
        this.pickupEinAbLeadMinutes.set(response.pickupEinAbLeadMinutes ?? 10);
        this.requireFairteilerVisitByThirdEinAb.set(response.requireFairteilerVisitByThirdEinAb ?? false);
        this.useFoodsharingUserInfoApi.set(response.useFoodsharingUserInfoApi ?? false);
        this.foodsharingMessagingEmail.set(response.foodsharingMessagingEmail ?? '');
        this.foodsharingMessagingPassword = '';
        this.ignoredCategories.set(response.ignoredCategories ?? []);
        this.applyDisabledCategories(response.disabledCategories ?? []);
        this.applyBezirkInfo(response);
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  private applyDisabledCategories(disabledCategories: EinAbCategory[]): void {
    this.disabledCategories.set(disabledCategories);
    this.bezirkContext.setDisabledCategories(this.bezirkContext.currentSlug(), disabledCategories);
  }

  private loadAdminBezirke(): void {
    this.adminApi.getAdminBezirke().subscribe({
      next: (response) => {
        this.adminBezirke.set(response.bezirke ?? []);
        this.adminBezirkeLoading.set(false);
      },
      error: (error) => {
        this.adminBezirkeLoading.set(false);
        this.errorNotifier.notify(error);
      }
    });
  }

  protected bezirkAddressPreview(): string {
    return `${window.location.host}/bezirke/${this.newBezirkSlug()}`;
  }

  protected onNewBezirkNameChange(name: string): void {
    this.newBezirkName.set(name ?? '');
    if (!this.newBezirkSlugEdited) {
      this.newBezirkSlug.set(slugify(name ?? ''));
    }
  }

  protected onNewBezirkSlugChange(slug: string): void {
    this.newBezirkSlugEdited = !!slug;
    this.newBezirkSlug.set((slug ?? '').toLowerCase().replace(/[^a-z0-9-]/g, ''));
  }

  createBezirk(): void {
    if (!this.canCreateBezirk() || this.creatingBezirk()) {
      return;
    }
    this.creatingBezirk.set(true);
    this.adminApi.createAdminBezirk({
      createBezirkRequest: { name: this.newBezirkName().trim(), slug: this.newBezirkSlug() }
    }).pipe(finalize(() => this.creatingBezirk.set(false))).subscribe({
      next: (created) => {
        this.adminBezirke.update((bezirke) => [...bezirke, created]);
        this.newBezirkName.set('');
        this.newBezirkSlug.set('');
        this.newBezirkSlugEdited = false;
        this.refreshBezirkLists();
        this.messageService.add({ severity: 'success', summary: this.i18n.t('admin.bezirke.created').replace('{0}', created.name) });
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  /** Shows who and which upcoming EinAbs are left behind before the admin confirms. */
  disableBezirk(bezirk: AdminBezirkSummary): void {
    this.bezirkDisableTarget.set(bezirk);
    this.bezirkDisableImpact.set(null);
    this.bezirkDisableImpactLoading.set(true);
    this.bezirkDisableDialogVisible.set(true);
    this.adminApi.getAdminBezirkDisableImpact({ bezirkSlug: bezirk.slug })
      .pipe(finalize(() => this.bezirkDisableImpactLoading.set(false)))
      .subscribe({
        next: (impact) => this.bezirkDisableImpact.set(impact),
        error: (error) => {
          this.bezirkDisableDialogVisible.set(false);
          this.toastError(resolveApiError(error, this.i18n));
        }
      });
  }

  confirmDisableBezirk(): void {
    const bezirk = this.bezirkDisableTarget();
    if (!bezirk) {
      return;
    }
    this.setBezirkActive(bezirk, false, () => this.bezirkDisableDialogVisible.set(false));
  }

  protected impactUserNames(users: BezirkDisableImpactUser[]): string {
    return users.map((user) => user.name).join(', ');
  }

  setBezirkActive(bezirk: AdminBezirkSummary, active: boolean, onSuccess?: () => void): void {
    this.bezirkToggling.set(bezirk.slug);
    const request = active
      ? this.adminApi.enableAdminBezirk({ bezirkSlug: bezirk.slug })
      : this.adminApi.disableAdminBezirk({ bezirkSlug: bezirk.slug });
    request.pipe(finalize(() => this.bezirkToggling.set(null))).subscribe({
      next: (updated) => {
        this.adminBezirke.update((bezirke) => bezirke.map((entry) => entry.id === updated.id ? updated : entry));
        onSuccess?.();
        this.messageService.add({
          severity: 'success',
          summary: this.i18n.t(active ? 'admin.bezirke.enabled' : 'admin.bezirke.disabledToast').replace('{0}', updated.name)
        });
        if (!active && updated.slug === this.bezirkContext.selectedBezirk()?.slug) {
          // This page belongs to the Bezirk that was just disabled; continue on the Bezirk selection.
          this.bezirkContext.reloadBezirke().subscribe(() => void this.router.navigate(['/']));
          return;
        }
        this.refreshBezirkLists();
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  /** Keeps the Bezirk switch in the menu and the user Bezirk choices in step with created/disabled Bezirke. */
  private refreshBezirkLists(): void {
    this.bezirkContext.reloadBezirke().subscribe({ error: (error) => this.errorNotifier.notify(error) });
    this.loadBezirke();
  }

  private loadBezirke(): void {
    this.publicApi.getBezirke().subscribe({
      next: (response) => this.bezirke.set(response.bezirke ?? []),
      error: (error) => {
        this.bezirke.set([]);
        this.errorNotifier.notify(error);
      }
    });
  }

  private patchUser(updatedUser: BookingUserResponse): void {
    this.usersPage.update((page) => {
      if (!page) {
        return page;
      }
      return {
        ...page,
        users: page.users.map((entry) => entry.user.id === updatedUser.id
          ? {
              ...entry,
              user: updatedUser
            }
          : entry)
      };
    });
    this.selectedUserBezirki.update((current) => ({ ...current, [updatedUser.id]: updatedUser.bezirk?.slug ?? null }));
  }

  private clearRefreshingUser(user: AdminBookingUserResponse): void {
    this.refreshingUserIds.update((current) => {
      const next = { ...current };
      delete next[user.user.id];
      return next;
    });
  }

  private toastError(detail: string): void {
    this.messageService.add({ severity: 'error', summary: this.i18n.t('common.error'), detail });
  }
}

function parseFoodsharingIds(text: string): string[] {
  return [...new Set(text.split(/[\s,;]+/).map((id) => id.trim()).filter((id) => id.length > 0))];
}

/** "Zürich Nord" → "zuerich-nord", a suggestion for the URL name of a new Bezirk. */
function slugify(name: string): string {
  return name
    .toLowerCase()
    .replace(/ä/g, 'ae')
    .replace(/ö/g, 'oe')
    .replace(/ü/g, 'ue')
    .replace(/ß/g, 'ss')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 100)
    .replace(/-+$/g, '');
}
