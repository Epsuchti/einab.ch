import { CommonModule } from '@angular/common';
import { Component, DestroyRef, ElementRef, OnInit, afterNextRender, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subject, finalize, takeUntil } from 'rxjs';

import {
  AdminService,
  AutomationRunSummary,
  FoodsharingAutomationAudit,
  FoodsharingExtraAutomationAudit,
  FoodsharingCleaningRuleExemption,
  FoodsharingStoreAutomationExcludedDate,
  FoodsharingConnectionStatus,
  FoodsharingFuturePickupUser,
  FoodsharingGapStoreGroup,
  FoodsharingManagedStore,
  FoodsharingOpenSlotAdvertisementOverview,
  FoodsharingPickupExtraSlotAudit,
  FoodsharingMetricsOverview,
  FoodsharingRunResult,
  FoodsharingRequestAutomationOverview,
  FoodsharingStoreAutomation,
  FoodsharingStoreAutomationLinkedStore,
  FoodsharingStoreAutomationLinkedStoreWindowModeEnum,
  FoodsharingStoreAutomationOverview,
  FoodsharingStoreInfoCancellation,
  FoodsharingStoreInfoSettings,
  UserPermission
} from '../../api';
import { resolveApiError } from '../../core/api-error';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { ErrorNotifierService } from '../../core/error-notifier.service';
import { confirmAction } from '../../core/confirm-action';
import { I18nService } from '../../core/i18n.service';
import { LoadingIndicatorComponent } from '../../core/loading-indicator.component';
import { LoadingTracker } from '../../core/loading-tracker';
import { SessionService } from '../../core/session.service';
import { trackStickyHeaders } from '../../core/sticky-headers';
import { ButtonModule } from 'primeng/button';
import { AccordionModule } from 'primeng/accordion';
import { CardModule } from 'primeng/card';
import { CheckboxModule } from 'primeng/checkbox';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { DatePickerModule } from 'primeng/datepicker';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { MultiSelectModule } from 'primeng/multiselect';
import { ConfirmationService, MessageService } from 'primeng/api';
import { SelectModule } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { TabsModule } from 'primeng/tabs';
import { ZurichDateTimePipe } from '../../core/zurich-date-time.pipe';

@Component({
  selector: 'app-admin-foodsharing-automation-page',
  standalone: true,
  imports: [
    LoadingIndicatorComponent,
    CommonModule,
    FormsModule,
    AccordionModule,
    ButtonModule,
    CardModule,
    CheckboxModule,
    ToggleSwitchModule,
    DatePickerModule,
    InputNumberModule,
    InputTextModule,
    MultiSelectModule,
    SelectModule,
    ZurichDateTimePipe,
    TableModule,
    TagModule,
    TabsModule
  ],
  templateUrl: './admin-foodsharing-automation-page.component.html',
  styleUrl: './admin-foodsharing-automation-page.component.scss'
})
export class AdminFoodsharingAutomationPageComponent implements OnInit {
  readonly i18n = inject(I18nService);
  protected readonly UserPermission = UserPermission;

  protected readonly foodsharingStatus = signal<FoodsharingConnectionStatus | null>(null);
  protected readonly statusLoading = signal(true);
  /** Counts the running loads of the active tab, so one spinner shows until all of them are done. */
  private readonly loadTracker = new LoadingTracker();
  protected readonly tabLoading = this.loadTracker.active;
  protected readonly foodsharingStores = signal<FoodsharingStoreAutomation[]>([]);
  protected readonly availableStores = signal<FoodsharingManagedStore[]>([]);
  protected readonly foodsharingAudit = signal<FoodsharingAutomationAudit[]>([]);
  protected readonly requestAutomationAudit = signal<FoodsharingExtraAutomationAudit[]>([]);
  protected readonly advertisementAutomationAudit = signal<FoodsharingExtraAutomationAudit[]>([]);
  protected readonly plannedAdvertisementNotifications = signal<FoodsharingExtraAutomationAudit[]>([]);
  protected readonly foodsharingFuturePickupUsers = signal<FoodsharingFuturePickupUser[]>([]);
  protected readonly pickupExtraSlotAudit = signal<FoodsharingPickupExtraSlotAudit[]>([]);
  protected readonly pickupExtraSlotUserCount = computed(() =>
    new Set(this.pickupExtraSlotAudit().map((entry) => entry.foodsharingUserId)).size);
  protected readonly metrics = signal<FoodsharingMetricsOverview | null>(null);
  protected readonly metricsLoading = signal(false);
  protected readonly metricsDays = signal(30);
  protected readonly metricsPeriodOptions = computed(() => [7, 30, 90, 365].map((days) => ({
    label: this.i18n.t('metrics.periodDays').replace('{days}', String(days)),
    value: days
  })));
  private readonly metricCounts = computed(() => {
    const counts = new Map<string, number>();
    for (const entry of this.metrics()?.counts ?? []) {
      counts.set(entry.key, entry.value);
    }
    return counts;
  });
  /** Derived numbers for the Metrics tab; percentages are null when there is nothing to divide by. */
  protected readonly metricSummary = computed(() => {
    const c = (...keys: string[]) => keys.reduce((sum, key) => sum + (this.metricCounts().get(key) ?? 0), 0);
    const coverage = this.metrics()?.coverage;
    const advertsSent = c('NOTIFICATION.SENT', 'NOTIFICATION.LATE_CANCELLATION_ADVERTISEMENT_SENT',
      'NOTIFICATION.LATE_CANCELLATION_REMINDER_AND_ADVERTISEMENT_SENT');
    const reminders = c('NOTIFICATION.LATE_CANCELLATION_REMINDER_SENT', 'NOTIFICATION.LATE_CANCELLATION_REMINDER_AND_ADVERTISEMENT_SENT');
    const storeInfo = c('NOTIFICATION.STORE_INFO_IMMEDIATE', 'NOTIFICATION.STORE_INFO_FINAL');
    const filled = c('ADVERT.FILLED');
    const unfilled = c('ADVERT.UNFILLED');
    const replaced = c('LATE_CANCEL.REPLACED');
    const rejoined = c('LATE_CANCEL.REJOINED');
    const notReplaced = c('LATE_CANCEL.NOT_REPLACED');
    const einAbSlots = c('EINAB.SLOTS');
    const einAbBooked = c('EINAB.BOOKED');
    return {
      coverageRate: percent(coverage?.occupiedSlots ?? 0, coverage?.totalSlots ?? 0),
      openSlots: Math.max(0, (coverage?.totalSlots ?? 0) - (coverage?.occupiedSlots ?? 0)),
      advertsSent,
      reminders,
      storeInfo,
      notificationsTotal: advertsSent + storeInfo + c('NOTIFICATION.LATE_CANCELLATION_REMINDER_SENT'),
      notNeeded: c('ADVERT.NOT_NEEDED'),
      filled,
      unfilled,
      fillRate: percent(filled, filled + unfilled),
      averageFillMinutes: filled > 0 ? Math.round(c('ADVERT.FILL_MINUTES') / filled) : null,
      lateCancellations: c('LATE_CANCEL.COUNT'),
      replaced,
      rejoined,
      notReplaced,
      coveredRate: percent(replaced + rejoined, replaced + rejoined + notReplaced),
      slotConfirmed: c('SLOT_APPROVAL.CONFIRMED', 'SLOT_APPROVAL.WOULD_CONFIRM'),
      slotDeclined: c('SLOT_APPROVAL.DECLINED', 'SLOT_APPROVAL.WOULD_DECLINE'),
      slotSkipped: c('SLOT_APPROVAL.SKIPPED'),
      slotFailed: c('SLOT_APPROVAL.FAILED'),
      applicationsApproved: c('APPLICATION.APPROVED', 'APPLICATION.WOULD_APPROVE'),
      applicationsDeclined: c('APPLICATION.DECLINED', 'APPLICATION.WOULD_DECLINE'),
      applicationsSkipped: c('APPLICATION.SKIPPED'),
      einAbSlots,
      einAbBooked,
      einAbBookedRate: percent(einAbBooked, einAbSlots),
      einAbDone: c('EINAB.DONE'),
      einAbUnbooked: c('EINAB.UNBOOKED'),
      einAbCancelled: c('EINAB.CANCELLED'),
      einAbPendingExpired: c('EINAB.PENDING_EXPIRED'),
      einAbUpcomingSlots: c('EINAB.UPCOMING_SLOTS'),
      einAbUpcomingBooked: c('EINAB.UPCOMING_BOOKED'),
      extraSlotsAdded: c('EXTRA_SLOT.ADDED'),
      extraSlotsFilled: c('EXTRA_SLOT.FILLED'),
      extraSlotUsers: c('EXTRA_SLOT.USERS')
    };
  });
  /** One bar per day of the period with all notifications sent that day. */
  protected readonly notificationDays = computed(() => {
    const metrics = this.metrics();
    if (!metrics) {
      return [];
    }
    const perDay = new Map<string, number>();
    for (const entry of metrics.daily) {
      if (entry.key.startsWith('NOTIFICATION.')) {
        perDay.set(entry.day, (perDay.get(entry.day) ?? 0) + entry.value);
      }
    }
    const days: { day: string; value: number }[] = [];
    const cursor = new Date(`${metrics.fromDay}T12:00:00Z`);
    const end = new Date(`${metrics.toDay}T12:00:00Z`);
    while (cursor <= end) {
      const day = cursor.toISOString().slice(0, 10);
      days.push({ day, value: perDay.get(day) ?? 0 });
      cursor.setUTCDate(cursor.getUTCDate() + 1);
    }
    const max = Math.max(1, ...days.map((entry) => entry.value));
    return days.map((entry) => ({ ...entry, height: Math.round((entry.value / max) * 100) }));
  });
  protected readonly notificationPeak = computed(() => Math.max(0, ...this.notificationDays().map((entry) => entry.value)));
  protected readonly lateCancellationHours = computed(() => this.metricBars([
    ['LATE_CANCEL.HOURS.LT6', 'metrics.hoursLt6'],
    ['LATE_CANCEL.HOURS.LT12', 'metrics.hoursLt12'],
    ['LATE_CANCEL.HOURS.LT24', 'metrics.hoursLt24'],
    ['LATE_CANCEL.HOURS.LT48', 'metrics.hoursLt48'],
    ['LATE_CANCEL.HOURS.GE48', 'metrics.hoursGe48']
  ], false));
  protected readonly slotDeclineRules = computed(() => this.metricBars(SLOT_RULES.map(
    (rule) => [`SLOT_DECLINE_RULE.${rule}`, `metrics.rule.${rule}`] as [string, string]), true));
  protected readonly slotSkipRules = computed(() => this.metricBars(SLOT_RULES.map(
    (rule) => [`SLOT_SKIP_RULE.${rule}`, `metrics.rule.${rule}`] as [string, string]), true));
  protected readonly refreshingFoodsharingFuturePickupUsers = signal(false);
  protected readonly onlyUsersWithSlotVerificationErrors = signal(false);
  protected readonly cleaningRuleExemptions = signal<FoodsharingCleaningRuleExemption[]>([]);
  protected readonly excludedDates = signal<FoodsharingStoreAutomationExcludedDate[]>([]);
  protected readonly cleaningStoreConfigured = signal<boolean | null>(null);
  protected readonly userInfoSourceConfigured = signal<boolean | null>(null);
  protected readonly foodsharingEmail = signal('');
  protected readonly cleaningExemptionFoodsharingId = signal('');
  protected readonly cleaningExemptionReason = signal('');
  protected readonly excludedDateStoreId = signal<number | null>(null);
  protected readonly excludedDate = signal<Date | null>(null);
  protected readonly slotRunResult = signal<FoodsharingRunResult | null>(null);
  protected readonly requestRunResult = signal<FoodsharingRunResult | null>(null);
  protected readonly advertisementRunResult = signal<FoodsharingRunResult | null>(null);
  protected readonly onlyMyAutomations = signal(true);
  protected readonly onlyMyDecisions = signal(true);
  protected readonly hideConfirmedDecisions = signal(false);
  protected readonly auditConsoleExpanded = signal(false);
  protected readonly activeAutomationTab = signal('slots');
  protected readonly selectedStoreId = signal<number | null>(null);
  protected readonly maximumLinkedStores = 2;
  protected readonly maximumGapStores = 9;
  protected readonly gapStoreGroups = signal<FoodsharingGapStoreGroup[]>([]);
  protected readonly maximumStoresPerLink = 5;
  /** Default German message for people removed by a linked store rule; prefilled when a link is added. */
  protected readonly linkedStoreDefaultMessage = signal('');
  /** All stores the connected account manages, with or without a slot approval automation. */
  protected readonly managedStores = signal<FoodsharingManagedStore[]>([]);
  /** Stores the connected account may automate; other stores only appear as read-only automations of other accounts. */
  protected readonly managedStoreIds = computed(() => new Set(this.managedStores().map((store) => store.storeId)));
  protected readonly linkedStoreDirectionOptions = computed(() => [
    { label: this.i18n.t('adminAutomation.linkedStoreOneWay'), value: false },
    { label: this.i18n.t('adminAutomation.linkedStoreBidirectional'), value: true }
  ]);
  protected readonly linkedStoreWindowOptions = computed(() => [
    { label: this.i18n.t('adminAutomation.linkedStoreSameDay'), value: FoodsharingStoreAutomationLinkedStoreWindowModeEnum.SameDay },
    { label: this.i18n.t('adminAutomation.linkedStoreLastDays'), value: FoodsharingStoreAutomationLinkedStoreWindowModeEnum.LastDays }
  ]);
  protected readonly selectedRequestStoreId = signal<number | null>(null);
  protected readonly selectedAdvertisementStoreId = signal<number | null>(null);
  protected readonly minimumExcludedDate = new Date(new Date().setHours(0, 0, 0, 0));
  protected readonly telegramChatOptions = signal<{ label: string; value: string }[]>([]);
  protected readonly advertisementMessageTabs = signal<Record<string, 'store' | 'telegram' | 'preview'>>({});
  protected telegramBotToken = "";
  protected readonly telegramBotTokenConfigured = computed(() => this.foodsharingStatus()?.telegramBotTokenConfigured ?? false);
  /** Open panel of the "general settings" accordion; collapsed by default, opened while nothing is connected. */
  protected readonly generalSettingsPanel = signal<string | null>(null);
  // Each tab shows read-only automations of other accounts only when "only my automations" is switched off. The
  // server sends them only to people who may see all automation decisions.
  protected readonly slotApprovalStores = computed(() => this.foodsharingStores()
    .filter((store) => Boolean((store as FoodsharingStoreAutomation & Record<string, unknown>)['slotApprovalConfigured']))
    .filter((store) => !this.onlyMyAutomations() || store.editable));
  protected readonly editableSlotApprovalStores = computed(() => this.slotApprovalStores().filter((store) => store.editable));
  protected readonly requestAutomationStores = computed(() => this.foodsharingStores()
    .filter((store) => Boolean((store as FoodsharingStoreAutomation & Record<string, unknown>)['requestConfigured']))
    .filter((store) => !this.onlyMyAutomations() || this.requestEditable(store)));
  protected readonly advertisementAutomationStores = computed(() => this.foodsharingStores()
    .filter((store) => this.advertisementNumbers(store).length > 0)
    .filter((store) => !this.onlyMyAutomations() || this.advertisementEditable(store)));
  protected readonly sentAdvertisementAudit = computed(() => this.advertisementAutomationAudit());
  protected readonly visibleFoodsharingFuturePickupUsers = computed(() => (this.onlyUsersWithSlotVerificationErrors()
    ? this.foodsharingFuturePickupUsers().filter((user) => this.hasSlotVerificationErrors(user))
    : [...this.foodsharingFuturePickupUsers()])
    .sort((a, b) => b.futurePickupCount - a.futurePickupCount));
  protected readonly availableStoreOptions = computed(() => this.availableStores().map((store) => ({
    label: `${store.storeName} (${store.storeId})`,
    value: store.storeId
  })));
  protected readonly availableRequestStoreOptions = computed(() => this.foodsharingStores()
    .filter((store) => this.managedStoreIds().has(store.storeId))
    .filter((store) => !(store as FoodsharingStoreAutomation & Record<string, unknown>)['requestConfigured'])
    .map((store) => ({ label: `${store.storeName} (${store.storeId})`, value: store.storeId })));
  protected readonly availableAdvertisementStoreOptions = computed(() => this.foodsharingStores()
    .filter((store) => this.managedStoreIds().has(store.storeId))
    .filter((store) => this.advertisementNumbers(store).length === 0)
    .map((store) => ({ label: `${store.storeName} (${store.storeId})`, value: store.storeId })));
  protected foodsharingPassword = '';
  protected readonly changingFoodsharingPassword = signal(false);
  protected readonly storeInfo = signal<FoodsharingStoreInfoSettings | null>(null);
  protected readonly storeInfoCancellations = signal<FoodsharingStoreInfoCancellation[]>([]);
  protected readonly storeInfoSaving = signal(false);
  protected readonly storeInfoTesting = signal(false);
  protected storeInfoForm = {
    enabled: false,
    cutoffEnabled: true,
    maximumHoursBeforePickup: 72,
    telegramChatIds: [] as string[],
    storeIds: [] as number[]
  };
  protected storeInfoManualChatId = '';

  private readonly adminApi = inject(AdminService);
  private readonly bezirkContext = inject(BezirkContextService);
  protected readonly sessionService = inject(SessionService);
  private readonly messageService = inject(MessageService);
  private readonly errorNotifier = inject(ErrorNotifierService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly hostElement = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly destroyRef = inject(DestroyRef);
  // Pins the open store's name below the app bar so it is clear which store is being edited.
  private readonly stickyStoreHeaders = afterNextRender(() =>
    trackStickyHeaders(this.hostElement.nativeElement, '.automation-store-accordion .p-accordionpanel-active > .p-accordionheader', this.destroyRef)
  );

  /** Slug the page data was loaded for; switching the Bezirk in the URL keeps this component and reloads the data. */
  private loadedBezirkSlug: string | null = null;
  /** Cancels loads still running for the previous Bezirk, so a late response cannot overwrite the new Bezirk's data. */
  private readonly bezirkChanged = new Subject<void>();
  private readonly bezirkReload = effect(() => {
    const slug = this.bezirkContext.selectedBezirk()?.slug ?? null;
    untracked(() => {
      if (!slug || slug === this.loadedBezirkSlug) {
        return;
      }
      const firstLoad = this.loadedBezirkSlug == null;
      this.loadedBezirkSlug = slug;
      if (!firstLoad) {
        this.onBezirkChanged();
      }
    });
  });

  ngOnInit(): void {
    this.loadedBezirkSlug = this.bezirkContext.selectedBezirk()?.slug ?? null;
    this.activeAutomationTab.set(this.sessionService.hasPermission(UserPermission.CanUseAutomations) ? 'slots' : 'statistics');
    this.loadBezirkSettings();
    this.loadFoodsharingStatus();
  }

  onAutomationTabChange(tab: string | number | undefined): void {
    if (tab == null) {
      return;
    }
    const nextTab = String(tab);
    this.activeAutomationTab.set(nextTab);
    this.loadActiveAutomationTab();
  }

  private onBezirkChanged(): void {
    this.bezirkChanged.next();
    this.foodsharingStores.set([]);
    this.availableStores.set([]);
    this.managedStores.set([]);
    this.excludedDates.set([]);
    this.cleaningRuleExemptions.set([]);
    this.foodsharingAudit.set([]);
    this.requestAutomationAudit.set([]);
    this.advertisementAutomationAudit.set([]);
    this.plannedAdvertisementNotifications.set([]);
    this.excludedDateStoreId.set(null);
    this.selectedStoreId.set(null);
    this.selectedRequestStoreId.set(null);
    this.selectedAdvertisementStoreId.set(null);
    this.loadBezirkSettings();
    this.loadActiveAutomationTab();
  }

  setHideConfirmedDecisions(hideConfirmed: boolean): void {
    this.hideConfirmedDecisions.set(hideConfirmed);
    this.loadFoodsharingAuditIfActive();
  }

  setOnlyMyDecisions(onlyMyDecisions: boolean): void {
    this.onlyMyDecisions.set(onlyMyDecisions);
    this.loadActiveAutomationTab();
  }

  hasSlotVerificationErrors(user: FoodsharingFuturePickupUser): boolean {
    return user.futurePickups.some((pickup) => pickup.validationErrors.length > 0);
  }

  connectFoodsharing(): void {
    this.adminApi.connectFoodsharing({
      foodsharingConnectRequest: {
        email: this.foodsharingEmail(),
        password: this.foodsharingPassword
      }
    }).subscribe({
      next: (status) => {
        this.foodsharingStatus.set(status);
        this.foodsharingPassword = '';
        this.loadActiveAutomationTab();
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  changeFoodsharingPassword(): void {
    const email = this.foodsharingStatus()?.email;
    if (!email || !this.foodsharingPassword) {
      return;
    }
    // Reconnecting with the same email updates the saved connection in place, so automations stay attached.
    this.adminApi.connectFoodsharing({
      foodsharingConnectRequest: {
        email,
        password: this.foodsharingPassword
      }
    }).subscribe({
      next: (status) => {
        this.foodsharingStatus.set(status);
        this.cancelFoodsharingPasswordChange();
        this.messageService.add({ severity: 'success', summary: this.i18n.t('foodsharingAccount.passwordChanged') });
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  cancelFoodsharingPasswordChange(): void {
    this.foodsharingPassword = '';
    this.changingFoodsharingPassword.set(false);
  }

  saveTelegramBotToken(): void {
    this.adminApi.saveFoodsharingTelegramBotToken({
      foodsharingTelegramBotTokenRequest: {
        telegramBotToken: this.telegramBotToken
      }
    }).subscribe({
      next: () => {
        this.telegramBotToken = '';
        this.messageService.add({ severity: 'success', summary: this.i18n.t('common.saved') });
        this.loadFoodsharingStatus();
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  removeTelegramBotToken(): void {
    this.adminApi.saveFoodsharingTelegramBotToken({
      foodsharingTelegramBotTokenRequest: {
        telegramBotToken: ''
      }
    }).subscribe({
      next: () => {
        this.telegramBotToken = '';
        this.messageService.add({ severity: 'success', summary: this.i18n.t('common.deleted') });
        this.loadFoodsharingStatus();
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  openGeneralSettings(): void {
    this.generalSettingsPanel.set('general-settings');
    if (this.foodsharingStatus()?.connected) {
      this.onAutomationTabChange('settings');
    }
    setTimeout(() => document.getElementById('general-settings')?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  }

  disconnectFoodsharing(): void {
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('confirmDialog.disconnectFoodsharing.title'),
      acceptLabel: this.i18n.t('confirmDialog.disconnectFoodsharing.accept'),
      destructive: true,
      message: this.i18n.t('confirm.disconnectFoodsharing'),
      accept: () => {
        this.confirmationService.close();
        this.doDisconnectFoodsharing();
      }
    });
  }

  private doDisconnectFoodsharing(): void {
    this.adminApi.disconnectFoodsharing().subscribe({
      next: () => {
        this.foodsharingStatus.set({ connected: false });
        this.generalSettingsPanel.set('general-settings');
        this.foodsharingStores.set([]);
        this.availableStores.set([]);
        this.foodsharingFuturePickupUsers.set([]);
        this.pickupExtraSlotAudit.set([]);
        this.foodsharingAudit.set([]);
        this.requestAutomationAudit.set([]);
        this.advertisementAutomationAudit.set([]);
        this.plannedAdvertisementNotifications.set([]);
        this.slotRunResult.set(null);
        this.requestRunResult.set(null);
        this.advertisementRunResult.set(null);
        this.telegramBotToken = '';
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  saveRequestAutomation(store: FoodsharingStoreAutomation & { requestEnabled?: boolean; requestDryRunEnabled?: boolean; requestDistanceRuleEnabled?: boolean; requestMaximumDistanceKm?: number }): void {
    this.adminApi.saveFoodsharingRequestAutomation({
      storeId: store.storeId,
      foodsharingRequestAutomationRequest: {
        storeName: store.storeName,
        enabled: !!store.requestEnabled,
        dryRunEnabled: store.requestDryRunEnabled !== false,
        distanceRuleEnabled: store.requestDistanceRuleEnabled === true,
        maximumDistanceKm: Number(store.requestMaximumDistanceKm ?? 0)
      }
    }).subscribe({ next: () => this.messageService.add({ severity: 'success', summary: this.i18n.t('common.saved') }), error: (error) => this.toastError(resolveApiError(error, this.i18n)) });
  }

  deleteRequestAutomation(store: FoodsharingStoreAutomation): void {
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('confirmDialog.delete.title'),
      acceptLabel: this.i18n.t('confirmDialog.delete.accept'),
      destructive: true,
      message: this.i18n.t('common.deleteConfirm'),
      accept: () => this.deleteRequestAutomationConfirmed(store)
    });
  }

  private deleteRequestAutomationConfirmed(store: FoodsharingStoreAutomation): void {
    this.adminApi.deleteFoodsharingRequestAutomation({ storeId: store.storeId }).subscribe({
      next: () => this.reloadActiveAutomationTab(),
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  addRequestAutomation(): void {
    const storeId = this.selectedRequestStoreId();
    const store = this.foodsharingStores().find((entry) => entry.storeId === storeId);
    if (!store) {
      return;
    }
    this.adminApi.saveFoodsharingRequestAutomation({
      storeId: store.storeId,
      foodsharingRequestAutomationRequest: {
        storeName: store.storeName,
        enabled: false,
        dryRunEnabled: true,
        distanceRuleEnabled: false,
        maximumDistanceKm: 0
      }
    }).subscribe({
      next: () => {
        this.selectedRequestStoreId.set(null);
        this.reloadActiveAutomationTab();
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  previewOpenSlotAdvertisement(store: FoodsharingStoreAutomation, advertNumber: number, channel: 'store' | 'telegram'): string {
    const templates = this.advertisementMessages(store, advertNumber, channel).map((message) => message.trim()).filter(Boolean);
    if (templates.length === 0) {
      return this.i18n.t('automation.addAdvertMessagePreview');
    }
    const sampleDate = new Date(Date.now() + 24 * 60 * 60 * 1000);
    const date = sampleDate.toISOString().slice(0, 10);
    const time = sampleDate.toTimeString().slice(0, 5);
    const adminFoodsharingId = this.sessionService.foodsharingId() || '';
    const dateDe = sampleDate.toLocaleDateString('de-CH', { day: '2-digit', month: '2-digit', year: 'numeric' });
    const weekday = sampleDate.toLocaleDateString('de-CH', { weekday: 'long' });
    return templates.map((template) => template
      .replaceAll('{{date}}', date)
      .replaceAll('{{dateDe}}', dateDe)
      .replaceAll('{{weekday}}', weekday)
      .replaceAll('{{time}}', time)
      .replaceAll('{{datetime}}', `${date} ${time}`)
      .replaceAll('{{datetimeDe}}', `${weekday}, ${dateDe} um ${time}`)
      .replaceAll('{{adminFoodsharingId}}', adminFoodsharingId)
      .replaceAll('{{adminProfileUrl}}', adminFoodsharingId ? `https://foodsharing.de/user/${adminFoodsharingId}/profile` : '')
    ).join('\n\n---\n\n');
  }

  sendTelegramTestMessage(store: FoodsharingStoreAutomation, advertNumber: number): void {
    const data = store as FoodsharingStoreAutomation & Record<string, unknown>;
    const chatId = String(data[`advertTelegramChatId${advertNumber}`] || '').trim();
    if (!chatId) {
      this.toastError(this.i18n.t('automation.selectTelegramChat'));
      return;
    }
    if (this.advertisementMessages(store, advertNumber, 'telegram').every((message) => !message.trim())) {
      this.toastError(this.i18n.t('automation.addTelegramMessage'));
      return;
    }
    this.adminApi.sendFoodsharingTelegramTestMessage({
      telegramTestMessageRequest: {
        chatId,
        message: this.previewOpenSlotAdvertisement(store, advertNumber, 'telegram')
      }
    }).subscribe({
      next: () => this.messageService.add({ severity: 'success', summary: this.i18n.t('automation.telegramTestSent') }),
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  advertisementMessages(store: FoodsharingStoreAutomation, advertNumber: number, channel: 'store' | 'telegram'): string[] {
    const data = store as FoodsharingStoreAutomation & Record<string, unknown>;
    const propertyName = channel === 'store' ? `advertStoreMessages${advertNumber}` : `advertTelegramMessages${advertNumber}`;
    const messages = String(data[propertyName] || '').split('\n---\n').map((message) => message.trim());
    return messages.length > 0 ? messages : [''];
  }

  advertisementMessageTab(store: FoodsharingStoreAutomation, advertNumber: number): 'store' | 'telegram' | 'preview' {
    const tabKey = this.advertisementMessageTabKey(store, advertNumber);
    const tab = this.advertisementMessageTabs()[tabKey] ?? 'store';
    return tab === 'telegram' && !this.telegramBotTokenConfigured() ? 'store' : tab;
  }

  setAdvertisementMessageTab(store: FoodsharingStoreAutomation, advertNumber: number, tab: string | number | undefined): void {
    if (tab !== 'store' && tab !== 'telegram' && tab !== 'preview') {
      return;
    }
    if (tab === 'telegram' && !this.telegramBotTokenConfigured()) {
      return;
    }
    const tabKey = this.advertisementMessageTabKey(store, advertNumber);
    this.advertisementMessageTabs.update((tabs) => ({ ...tabs, [tabKey]: tab }));
  }

  updateAdvertisementMessage(store: FoodsharingStoreAutomation, advertNumber: number, channel: 'store' | 'telegram', index: number, value: string): void {
    const messages = this.advertisementMessages(store, advertNumber, channel);
    messages[index] = value;
    this.setAdvertisementMessages(store, advertNumber, channel, messages);
  }

  addAdvertisementMessage(store: FoodsharingStoreAutomation, advertNumber: number, channel: 'store' | 'telegram'): void {
    const messages = this.advertisementMessages(store, advertNumber, channel);
    messages.push('');
    this.setAdvertisementMessages(store, advertNumber, channel, messages);
  }

  removeAdvertisementMessage(store: FoodsharingStoreAutomation, advertNumber: number, channel: 'store' | 'telegram', index: number): void {
    const messages = this.advertisementMessages(store, advertNumber, channel);
    if (messages.length === 1) {
      return;
    }
    messages.splice(index, 1);
    this.setAdvertisementMessages(store, advertNumber, channel, messages);
  }

  saveOpenSlotAdvertisement(store: FoodsharingStoreAutomation, advertNumber: number): void {
    const data = store as FoodsharingStoreAutomation & Record<string, unknown>;
    const storeMessages = this.advertisementMessages(store, advertNumber, 'store').map((message) => message.trim()).filter(Boolean);
    const telegramMessages = this.advertisementMessages(store, advertNumber, 'telegram').map((message) => message.trim()).filter(Boolean);
    const sendToStoreChat = Boolean(data[`advertSendToStoreChat${advertNumber}`]);
    const sendToTelegram = Boolean(data[`advertSendToTelegram${advertNumber}`]);
    const telegramChatId = String(data[`advertTelegramChatId${advertNumber}`] || '').trim();
    const settingsNumber = this.lateCancellationSettingsNumber(store) ?? advertNumber;
    const lateCancellationMessage = String(data[`advertLateCancellationMessage${settingsNumber}`] || '').trim();
    const sendLateCancellationMessage = Boolean(data[`advertSendLateCancellationMessage${settingsNumber}`]);
    const lateCancellationMessageMaximumHoursBeforePickup = Number(data[`advertLateCancellationMessageMaximumHoursBeforePickup${settingsNumber}`] ?? 72);
    const sendLatestAdvertisementAfterLateCancellation = Boolean(data[`advertSendLatestAdvertisementAfterLateCancellation${settingsNumber}`]);
    if (!sendToStoreChat && !sendToTelegram) {
      this.toastError(this.i18n.t('automation.selectDestination'));
      return;
    }
    if (sendToStoreChat && storeMessages.length === 0) {
      this.toastError(this.i18n.t('automation.addStoreChatMessage'));
      return;
    }
    if (sendToTelegram && !telegramChatId) {
      this.toastError(this.i18n.t('automation.selectTelegramChat'));
      return;
    }
    if (sendToTelegram && telegramMessages.length === 0) {
      this.toastError(this.i18n.t('automation.addTelegramMessage'));
      return;
    }
    if (sendLateCancellationMessage && !lateCancellationMessage) {
      this.toastError(this.i18n.t('automation.lateCancellationMessageRequired'));
      return;
    }
    this.adminApi.saveFoodsharingOpenSlotAdvertisementAutomation({
      storeId: store.storeId,
      advertNumber,
      foodsharingOpenSlotAdvertisementAutomationRequest: {
        storeName: store.storeName,
        enabled: Boolean(data[`advertEnabled${advertNumber}`]),
        dryRunEnabled: Boolean(data[`advertDryRunEnabled${advertNumber}`]),
        triggerHoursBefore: Number(data[`advertHoursBefore${advertNumber}`] || (advertNumber === 1 ? 24 : advertNumber === 2 ? 12 : 3)),
        sendToStoreChat,
        sendToTelegram,
        telegramChatId: telegramChatId || undefined,
        storeMessages,
        telegramMessages,
        lateCancellationMessage,
        sendLateCancellationMessage,
        lateCancellationMessageMaximumHoursBeforePickup,
        sendLatestAdvertisementAfterLateCancellation
      }
    }).subscribe({ next: () => this.messageService.add({ severity: 'success', summary: this.i18n.t('common.saved') }), error: (error) => this.toastError(resolveApiError(error, this.i18n)) });
  }

  deleteAdvertisementAutomation(store: FoodsharingStoreAutomation, advertNumber: number): void {
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('confirmDialog.delete.title'),
      acceptLabel: this.i18n.t('confirmDialog.delete.accept'),
      destructive: true,
      message: this.i18n.t('common.deleteConfirm'),
      accept: () => this.deleteAdvertisementAutomationConfirmed(store, advertNumber)
    });
  }

  private deleteAdvertisementAutomationConfirmed(store: FoodsharingStoreAutomation, advertNumber: number): void {
    this.adminApi.deleteFoodsharingOpenSlotAdvertisementAutomation({ storeId: store.storeId, advertNumber }).subscribe({
      next: () => this.reloadActiveAutomationTab(),
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  private setAdvertisementMessages(store: FoodsharingStoreAutomation, advertNumber: number, channel: 'store' | 'telegram', messages: string[]): void {
    const data = store as FoodsharingStoreAutomation & Record<string, unknown>;
    const propertyName = channel === 'store' ? `advertStoreMessages${advertNumber}` : `advertTelegramMessages${advertNumber}`;
    data[propertyName] = messages.join('\n---\n');
    this.foodsharingStores.update((stores) => [...stores]);
  }

  private advertisementMessageTabKey(store: FoodsharingStoreAutomation, advertNumber: number): string {
    return `${store.storeId}-${advertNumber}`;
  }

  runRequestAutomationDryRun(): void {
    this.adminApi.runFoodsharingRequestAutomationDryRun().subscribe({
      next: (result) => {
        this.requestRunResult.set(this.toRunResult(result));
        this.loadRequestAutomationAuditIfActive();
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  runAdvertisementAutomationDryRun(): void {
    this.adminApi.runFoodsharingOpenSlotAdvertisementDryRun().subscribe({
      next: (result) => {
        this.advertisementRunResult.set(this.toRunResult(result));
        this.loadAdvertisementAutomationAuditIfActive();
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  saveFoodsharingStore(store: FoodsharingStoreAutomation): void {
    const linkedStores = store.linkedStores ?? [];
    if (linkedStores.some((link) => !link.linkedStores?.length)) {
      this.toastError(this.i18n.t('adminAutomation.linkedStoreMissing'));
      return;
    }
    if (linkedStores.some((link) => link.windowMode === FoodsharingStoreAutomationLinkedStoreWindowModeEnum.LastDays
      && (link.windowDaysBefore ?? 0) + (link.windowDaysAfter ?? 0) < 1)) {
      this.toastError(this.i18n.t('adminAutomation.linkedStoreDaysMissing'));
      return;
    }
    this.adminApi.saveFoodsharingStoreAutomation({
      bezirkSlug: this.bezirkContext.currentSlug(),
      storeId: store.storeId,
      foodsharingStoreAutomationRequest: {
        ...store,
        minimumGapDays: store.minimumGapDays ?? 0,
        // The default text is stored as "no custom message", so later improvements to the default apply automatically.
        linkedStores: linkedStores.map((link) => ({
          ...link,
          message: link.message?.trim() && link.message.trim() !== this.linkedStoreDefaultMessage().trim() ? link.message : null
        }))
      }
    }).subscribe({
      next: (savedStore) => {
        this.foodsharingStores.update((stores) => stores.map((entry) => entry.storeId === savedStore.storeId
          ? { ...entry, ...this.withLinkedStoreMessages(savedStore), slotApprovalConfigured: true }
          : entry));
        if (linkedStores.some((link) => link.bidirectional) || store.gapStores?.length || this.isInGapGroup(store)) {
          // Bidirectional links and the gap rule's store list show up on the other stores too.
          this.reloadActiveAutomationTab();
        }
        this.messageService.add({ severity: 'success', summary: this.i18n.t('common.saved') });
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  deleteFoodsharingStore(store: FoodsharingStoreAutomation): void {
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('confirmDialog.delete.title'),
      acceptLabel: this.i18n.t('confirmDialog.delete.accept'),
      destructive: true,
      message: this.i18n.t('common.deleteConfirm'),
      accept: () => this.deleteFoodsharingStoreConfirmed(store)
    });
  }

  private deleteFoodsharingStoreConfirmed(store: FoodsharingStoreAutomation): void {
    this.adminApi.deleteFoodsharingStoreAutomation({ bezirkSlug: this.bezirkContext.currentSlug(), storeId: store.storeId }).subscribe({
      next: () => this.reloadActiveAutomationTab(),
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  advertisementNumbers(store: FoodsharingStoreAutomation): number[] {
    const data = store as FoodsharingStoreAutomation & Record<string, unknown>;
    return [1, 2, 3].filter((number) => data[`advertConfigured${number}`] || data[`advertStoreMessages${number}`] !== undefined || data[`advertTelegramMessages${number}`] !== undefined);
  }

  lateCancellationSettingsNumber(store: FoodsharingStoreAutomation): number | null {
    return this.advertisementNumbers(store)[0] ?? null;
  }

  addAdvertisement(store: FoodsharingStoreAutomation): void {
    const data = store as FoodsharingStoreAutomation & Record<string, unknown>;
    const previousSettingsNumber = this.lateCancellationSettingsNumber(store);
    const advertNumber = [1, 2, 3].find((number) => !data[`advertConfigured${number}`] && data[`advertStoreMessages${number}`] === undefined && data[`advertTelegramMessages${number}`] === undefined);
    if (!advertNumber) {
      return;
    }
    data[`advertConfigured${advertNumber}`] = true;
    data[`advertEnabled${advertNumber}`] = false;
    data[`advertDryRunEnabled${advertNumber}`] = true;
    data[`advertHoursBefore${advertNumber}`] = advertNumber === 1 ? 24 : advertNumber === 2 ? 12 : 3;
    data[`advertSendToStoreChat${advertNumber}`] = false;
    data[`advertSendToTelegram${advertNumber}`] = false;
    data[`advertStoreMessages${advertNumber}`] = '';
    data[`advertTelegramMessages${advertNumber}`] = '';
    if (this.lateCancellationSettingsNumber(store) === advertNumber) {
      data[`advertLateCancellationMessage${advertNumber}`] = previousSettingsNumber === null
        ? 'Du hast gerade den Slot {{datetimeDe}} freigegeben. Du bist trotzdem verantwortlich für diesen Slot! Bitte sorge für Ersatz und notifiziere sowohl das Team als auch den Notfallchat.'
        : data[`advertLateCancellationMessage${previousSettingsNumber}`];
      data[`advertSendLateCancellationMessage${advertNumber}`] = previousSettingsNumber === null
        ? false
        : data[`advertSendLateCancellationMessage${previousSettingsNumber}`];
      data[`advertLateCancellationMessageMaximumHoursBeforePickup${advertNumber}`] = previousSettingsNumber === null
        ? 72
        : data[`advertLateCancellationMessageMaximumHoursBeforePickup${previousSettingsNumber}`];
      data[`advertSendLatestAdvertisementAfterLateCancellation${advertNumber}`] = previousSettingsNumber === null
        ? true
        : data[`advertSendLatestAdvertisementAfterLateCancellation${previousSettingsNumber}`];
    }
    this.foodsharingStores.update((stores) => [...stores]);
  }

  addAdvertisementAutomation(): void {
    const storeId = this.selectedAdvertisementStoreId();
    const store = this.foodsharingStores().find((entry) => entry.storeId === storeId);
    if (!store) {
      return;
    }
    this.selectedAdvertisementStoreId.set(null);
    this.addAdvertisement(store);
  }

  requestAudit(): FoodsharingExtraAutomationAudit[] {
    return this.requestAutomationAudit();
  }

  addFoodsharingStore(): void {
    const storeId = this.selectedStoreId();
    const store = this.availableStores().find((entry) => entry.storeId === storeId);
    if (!store) {
      return;
    }
    const gapGroup = this.gapStoreGroupOf(store.storeId);
    this.adminApi.saveFoodsharingStoreAutomation({
      bezirkSlug: this.bezirkContext.currentSlug(),
      storeId: store.storeId,
      foodsharingStoreAutomationRequest: {
        storeName: store.storeName,
        enabled: false,
        dryRunEnabled: true,
        gapRuleEnabled: false,
        minimumGapDays: 0,
        gapStores: gapGroup?.stores.filter((entry) => entry.storeId !== store.storeId) ?? [],
        gapStoresMinimumDays: gapGroup?.minimumGapDays,
        cleaningRuleEnabled: false,
        experienceRuleEnabled: false,
        publicHolidayRuleEnabled: false
      }
    }).subscribe({
      next: () => {
        this.selectedStoreId.set(null);
        this.reloadActiveAutomationTab();
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  activeSlotRuleLabel(store: FoodsharingStoreAutomation): string {
    const count = [store.gapRuleEnabled || Boolean(store.gapStores?.length), store.cleaningRuleEnabled, store.experienceRuleEnabled, store.publicHolidayRuleEnabled, this.linkedStoreCount(store) > 0]
      .filter(Boolean).length;
    return this.i18n.t(count === 1 ? 'adminAutomation.activeRulesOne' : 'adminAutomation.activeRulesOther').replace('{count}', String(count));
  }

  requestDistanceLabel(store: FoodsharingStoreAutomation): string {
    const data = store as FoodsharingStoreAutomation & Record<string, unknown>;
    if (!data['requestDistanceRuleEnabled']) {
      return this.i18n.t('adminAutomation.noDistanceRule');
    }
    return this.i18n.t('adminAutomation.maximumDistanceSummary').replace('{km}', String(data['requestMaximumDistanceKm'] ?? 0));
  }

  advertisementStoreEnabled(store: FoodsharingStoreAutomation): boolean {
    const data = store as FoodsharingStoreAutomation & Record<string, unknown>;
    return this.advertisementNumbers(store).some((number) => Boolean(data[`advertEnabled${number}`]));
  }

  /** Dry run counts only for alerts that are switched on; a disabled alert sends nothing either way. */
  advertisementStoreDryRun(store: FoodsharingStoreAutomation): boolean {
    const data = store as FoodsharingStoreAutomation & Record<string, unknown>;
    return this.advertisementNumbers(store)
      .some((number) => Boolean(data[`advertEnabled${number}`]) && Boolean(data[`advertDryRunEnabled${number}`]));
  }

  advertisementHoursLabel(store: FoodsharingStoreAutomation, advertNumber: number): string {
    const data = store as FoodsharingStoreAutomation & Record<string, unknown>;
    return this.i18n.t('automation.alertHoursBeforeShort').replace('{hours}', String(data[`advertHoursBefore${advertNumber}`] ?? '-'));
  }

  advertisementSummaryLabel(store: FoodsharingStoreAutomation): string {
    const data = store as FoodsharingStoreAutomation & Record<string, unknown>;
    const numbers = this.advertisementNumbers(store);
    if (numbers.length === 0) {
      return this.i18n.t('automation.alertSummaryNone');
    }
    const hours = numbers.map((number) => `${data[`advertHoursBefore${number}`] ?? '-'} h`).join(', ');
    return this.i18n.t(numbers.length === 1 ? 'automation.alertSummaryOne' : 'automation.alertSummaryOther')
      .replace('{count}', String(numbers.length))
      .replace('{hours}', hours);
  }

  /** Own links plus bidirectional links other stores have to this one; both count towards the limit. */
  linkedStoreCount(store: FoodsharingStoreAutomation): number {
    return (store.linkedStores?.length ?? 0) + (store.incomingLinkedStores ?? []).filter((entry) => entry.bidirectional).length;
  }

  linkedStoreOptions(store: FoodsharingStoreAutomation, link: FoodsharingStoreAutomationLinkedStore): { label: string; value: number; hasAutomation: boolean }[] {
    const taken = new Set([
      ...(store.linkedStores ?? []).filter((entry) => entry !== link).flatMap((entry) => entry.linkedStores.map((linked) => linked.storeId)),
      ...(store.incomingLinkedStores ?? []).map((entry) => entry.sourceStoreId)
    ]);
    const options = this.managedStores()
      .filter((candidate) => candidate.storeId !== store.storeId && !taken.has(candidate.storeId))
      .map((candidate) => ({ label: candidate.storeName, value: candidate.storeId, hasAutomation: this.hasSlotAutomation(candidate.storeId) }));
    // Keep the same array instance while nothing changed: a new one on every change detection makes the multi-select re-render endlessly.
    const key = JSON.stringify(options);
    const cached = this.linkedStoreOptionCache.get(link);
    if (cached?.key === key) {
      return cached.options;
    }
    this.linkedStoreOptionCache.set(link, { key, options });
    return options;
  }

  private readonly linkedStoreOptionCache = new WeakMap<object, { key: string; options: { label: string; value: number; hasAutomation: boolean }[] }>();
  private readonly linkedStoreIdCache = new WeakMap<object, { source: unknown; ids: number[] }>();

  /** Same array instance while the link's stores are unchanged, so change detection does not rewrite the multi-select every cycle. */
  linkedStoreIds(link: FoodsharingStoreAutomationLinkedStore): number[] {
    const cached = this.linkedStoreIdCache.get(link);
    if (cached && cached.source === link.linkedStores) {
      return cached.ids;
    }
    const ids = (link.linkedStores ?? []).map((entry) => entry.storeId);
    this.linkedStoreIdCache.set(link, { source: link.linkedStores, ids });
    return ids;
  }

  setLinkedStoreIds(link: FoodsharingStoreAutomationLinkedStore, ids: number[] | null): void {
    const known = new Map(this.managedStores().map((entry) => [entry.storeId, entry.storeName]));
    link.linkedStores = (ids ?? []).map((id) => ({
      storeId: id,
      storeName: known.get(id) ?? link.linkedStores.find((entry) => entry.storeId === id)?.storeName ?? String(id)
    }));
  }

  storesWithoutAutomation(link: FoodsharingStoreAutomationLinkedStore): string {
    return (link.linkedStores ?? []).filter((entry) => !this.hasSlotAutomation(entry.storeId)).map((entry) => entry.storeName).join(', ');
  }

  private gapStoreGroupOf(storeId: number): FoodsharingGapStoreGroup | undefined {
    return this.gapStoreGroups().find((group) => group.stores.some((entry) => entry.storeId === storeId));
  }

  isInGapGroup(store: FoodsharingStoreAutomation): boolean {
    return Boolean(this.gapStoreGroupOf(store.storeId));
  }

  gapGroupSize(store: FoodsharingStoreAutomation): string {
    return String((store.gapStores?.length ?? 0) + 1);
  }

  gapStoreOptions(store: FoodsharingStoreAutomation): { label: string; value: number; hasAutomation: boolean }[] {
    const options = this.managedStores()
      .filter((candidate) => candidate.storeId !== store.storeId)
      .map((candidate) => ({ label: candidate.storeName, value: candidate.storeId, hasAutomation: this.hasSlotAutomation(candidate.storeId) }));
    // Same array instance while nothing changed, as for the linked stores: the multi-select re-renders endlessly otherwise.
    const key = JSON.stringify(options);
    const cached = this.linkedStoreOptionCache.get(store);
    if (cached?.key === key) {
      return cached.options;
    }
    this.linkedStoreOptionCache.set(store, { key, options });
    return options;
  }

  gapStoreIds(store: FoodsharingStoreAutomation): number[] {
    const cached = this.linkedStoreIdCache.get(store);
    if (cached && cached.source === store.gapStores) {
      return cached.ids;
    }
    const ids = (store.gapStores ?? []).map((entry) => entry.storeId);
    this.linkedStoreIdCache.set(store, { source: store.gapStores, ids });
    return ids;
  }

  setGapStoreIds(store: FoodsharingStoreAutomation, ids: number[] | null): void {
    const known = new Map(this.managedStores().map((entry) => [entry.storeId, entry.storeName]));
    store.gapStores = (ids ?? []).map((id) => ({ storeId: id, storeName: known.get(id) ?? '' }));
    if (!store.gapStores.length) {
      store.gapStoresMinimumDays = undefined;
    } else if (!store.gapStoresMinimumDays) {
      store.gapStoresMinimumDays = Math.max(store.minimumGapDays ?? 0, 1);
    }
  }

  gapStoresWithoutAutomation(store: FoodsharingStoreAutomation): string {
    return (store.gapStores ?? []).filter((entry) => !this.hasSlotAutomation(entry.storeId)).map((entry) => entry.storeName).join(', ');
  }

  /** Listed stores linked to this store in a way that cannot respect the gap; the server also checks the other stores' links. */
  gapLinkConflict(store: FoodsharingStoreAutomation): string {
    const gap = store.gapStoresMinimumDays ?? 0;
    const fits = (windowMode: string, before: number, after: number) => windowMode === FoodsharingStoreAutomationLinkedStoreWindowModeEnum.LastDays
      && Math.max(before, after) - 1 >= gap;
    const listed = new Set((store.gapStores ?? []).map((entry) => entry.storeId));
    const names = new Set<string>();
    for (const link of store.linkedStores ?? []) {
      if (!fits(link.windowMode, link.windowDaysBefore ?? 0, link.windowDaysAfter ?? 0)) {
        // A link to "B or C" with C outside the list can still be met at C; its bidirectional side links back to this store alone.
        const inList = link.linkedStores.filter((entry) => listed.has(entry.storeId));
        if (inList.length && (inList.length === link.linkedStores.length || link.bidirectional)) {
          inList.forEach((entry) => names.add(entry.storeName));
        }
      }
    }
    for (const link of store.incomingLinkedStores ?? []) {
      // Only the bidirectional side is a link to this store alone; the other side is checked by the server on save.
      if (link.bidirectional && listed.has(link.sourceStoreId) && !fits(link.windowMode, link.windowDaysBefore, link.windowDaysAfter)) {
        names.add(link.sourceStoreName);
      }
    }
    return [...names].join(', ');
  }

  /** Which days around an example pickup two weeks from now the rule blocks, counted like calendarGapDays on the server. */
  gapPreviews(store: FoodsharingStoreAutomation): { title: string; days: { offset: number; label: string; state: string; blocked: boolean }[]; note: string; message: string }[] {
    return [
      ...(store.gapRuleEnabled ? [this.gapPreview(store, store.minimumGapDays ?? 0, [])] : []),
      ...(store.gapStores?.length ? [this.gapPreview(store, store.gapStoresMinimumDays ?? 0, store.gapStores)] : [])
    ];
  }

  private gapPreview(store: FoodsharingStoreAutomation, gap: number, others: FoodsharingManagedStore[]): { title: string; days: { offset: number; label: string; state: string; blocked: boolean }[]; note: string; message: string } {
    const exampleStore = others[0]?.storeName ?? store.storeName;
    const locale = this.i18n.language() === 'en' ? 'en-GB' : 'de-CH';
    const dateAt = (offset: number) => {
      const date = new Date();
      date.setDate(date.getDate() + 14 + offset);
      return date;
    };
    const shortDate = (date: Date) => date.toLocaleDateString(locale, { weekday: 'short', day: 'numeric', month: 'numeric' });
    const days = Array.from({ length: 11 }, (_, index) => {
      const offset = index - 5;
      const blocked = offset !== 0 && Math.max(0, Math.abs(offset) - 1) < gap;
      const state = offset === 0 ? 'gapPreviewPickup' : blocked ? 'gapPreviewBlocked' : 'gapPreviewAllowed';
      return { offset, label: shortDate(dateAt(offset)), state: this.i18n.t(`adminAutomation.${state}`), blocked };
    });
    const dayText = (count: number) => this.i18n.t(count === 1 ? 'adminAutomation.gapDayOne' : 'adminAutomation.gapDayOther').replace('{count}', String(count));
    const names = [store.storeName, ...others.map((entry) => entry.storeName)];
    const exampleGap = gap > 1 ? 1 : 0;
    return {
      title: this.i18n.t('adminAutomation.gapPreviewTitle').replace('{date}', shortDate(dateAt(0))).replace('{store}', exampleStore),
      days,
      note: this.i18n.t(others.length ? 'adminAutomation.gapPreviewNoteStores' : 'adminAutomation.gapPreviewNoteStore')
        .replace('{date}', shortDate(dateAt(gap + 1))),
      message: others.length
        ? this.i18n.t('adminAutomation.gapPreviewMessageStores')
          .replace('{date}', dateAt(0).toLocaleDateString('de-CH'))
          .replace('{store}', exampleStore)
          .replace('{stores}', `${names.slice(0, -1).join(', ')} ${this.i18n.t('adminAutomation.gapPreviewAnd')} ${names[names.length - 1]}`)
          .replace('{gap}', dayText(gap))
          .replace('{actual}', dayText(exampleGap))
        : this.i18n.t('adminAutomation.gapPreviewMessageStore').replace('{gap}', dayText(gap)).replace('{actual}', dayText(exampleGap))
    };
  }

  hasSlotAutomation(storeId: number | null | undefined): boolean {
    return this.foodsharingStores().some((store) => store.storeId === storeId
      && store.enabled
      && Boolean((store as FoodsharingStoreAutomation & Record<string, unknown>)['slotApprovalConfigured']));
  }

  linkedStoreWindowLabel(windowMode: string, daysBefore: number, daysAfter: number): string {
    return windowMode === FoodsharingStoreAutomationLinkedStoreWindowModeEnum.LastDays
      ? this.i18n.t('adminAutomation.linkedStoreLastDaysValue').replace('{before}', String(daysBefore)).replace('{after}', String(daysAfter))
      : this.i18n.t('adminAutomation.linkedStoreSameDay');
  }

  addLinkedStore(store: FoodsharingStoreAutomation): void {
    if (this.linkedStoreCount(store) >= this.maximumLinkedStores) {
      return;
    }
    store.linkedStores = [
      ...(store.linkedStores ?? []),
      {
        linkedStores: [],
        bidirectional: false,
        windowMode: FoodsharingStoreAutomationLinkedStoreWindowModeEnum.SameDay,
        windowDaysBefore: 3,
        windowDaysAfter: 3,
        graceMinutes: 20,
        message: this.linkedStoreDefaultMessage(),
        exemptions: []
      }
    ];
  }

  addLinkedStoreExemption(link: FoodsharingStoreAutomationLinkedStore, idInput: HTMLInputElement, reasonInput: HTMLInputElement): void {
    const foodsharingId = idInput.value.trim();
    const reason = reasonInput.value.trim();
    if (!/^\d{1,18}$/.test(foodsharingId)) {
      this.toastError(this.i18n.t('adminAutomation.linkedStoreExemptionIdInvalid'));
      return;
    }
    if (!reason) {
      this.toastError(this.i18n.t('adminAutomation.linkedStoreExemptionReasonMissing'));
      return;
    }
    if ((link.exemptions ?? []).some((entry) => entry.foodsharingId === foodsharingId)) {
      this.toastError(this.i18n.t('adminAutomation.linkedStoreExemptionDuplicate'));
      return;
    }
    link.exemptions = [...(link.exemptions ?? []), { foodsharingId, reason }];
    idInput.value = '';
    reasonInput.value = '';
    idInput.focus();
  }

  removeLinkedStoreExemption(link: FoodsharingStoreAutomationLinkedStore, foodsharingId: string): void {
    link.exemptions = (link.exemptions ?? []).filter((entry) => entry.foodsharingId !== foodsharingId);
  }

  removeLinkedStore(store: FoodsharingStoreAutomation, index: number): void {
    store.linkedStores = (store.linkedStores ?? []).filter((_, entryIndex) => entryIndex !== index);
  }

  storeOwnerLabel(store: FoodsharingStoreAutomation): string {
    if (!store.ownerName && !store.ownerEmail) {
      return this.i18n.t('adminAutomation.available');
    }
    if (store.ownerName && store.ownerEmail) {
      return `${store.ownerName} (${store.ownerEmail})`;
    }
    return store.ownerName || store.ownerEmail || this.i18n.t('adminAutomation.available');
  }

  /** The request automation has its own owner; the slot automation of the same store may belong to someone else. */
  requestEditable(store: FoodsharingStoreAutomation): boolean {
    const value = (store as FoodsharingStoreAutomation & Record<string, unknown>)['requestEditable'];
    return typeof value === 'boolean' ? value : store.editable;
  }

  advertisementEditable(store: FoodsharingStoreAutomation): boolean {
    const value = (store as FoodsharingStoreAutomation & Record<string, unknown>)['advertEditable'];
    return typeof value === 'boolean' ? value : store.editable;
  }

  requestOwnerLabel(store: FoodsharingStoreAutomation): string {
    return this.ownerLabelOf(store, 'request');
  }

  advertisementOwnerLabel(store: FoodsharingStoreAutomation): string {
    return this.ownerLabelOf(store, 'advert');
  }

  /** Whether the slot automation of this store belongs to the connected account (e.g. to manage its excluded dates). */
  isEditableStore(storeId: number): boolean {
    return this.foodsharingStores().some((store) => store.storeId === storeId && store.editable);
  }

  private ownerLabelOf(store: FoodsharingStoreAutomation, prefix: 'request' | 'advert'): string {
    const entry = store as FoodsharingStoreAutomation & Record<string, unknown>;
    if (typeof entry[`${prefix}Editable`] !== 'boolean') {
      return this.storeOwnerLabel(store);
    }
    return this.storeOwnerLabel({
      ...store,
      ownerName: entry[`${prefix}OwnerName`] as string | undefined,
      ownerEmail: entry[`${prefix}OwnerEmail`] as string | undefined
    });
  }

  statusBannerText(): string {
    const status = this.foodsharingStatus();
    if (!status) {
      return '';
    }
    if (!status.automationEnabled) {
      return this.i18n.t('adminAutomation.modeDisabled');
    }
    return status.automationDryRun
      ? this.i18n.t('adminAutomation.modeDryRun')
      : this.i18n.t('adminAutomation.modeActive');
  }

  statusBannerClass(): string {
    const status = this.foodsharingStatus();
    if (!status) {
      return '';
    }
    if (!status.automationEnabled) {
      return 'automation-mode-banner--disabled';
    }
    return status.automationDryRun ? 'automation-mode-banner--dry-run' : 'automation-mode-banner--active';
  }

  runFoodsharingDryRun(): void {
    this.adminApi.runFoodsharingAutomation({ bezirkSlug: this.bezirkContext.currentSlug(), foodsharingRunRequest: { dryRun: true } }).subscribe({
      next: (result) => {
        this.slotRunResult.set(result);
        this.loadFoodsharingAuditIfActive();
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }


  saveCleaningRuleExemption(): void {
    this.adminApi.saveFoodsharingCleaningRuleExemption({
      bezirkSlug: this.bezirkContext.currentSlug(),
      foodsharingCleaningRuleExemptionRequest: {
        foodsharingId: this.cleaningExemptionFoodsharingId(),
        reason: this.cleaningExemptionReason()
      }
    }).subscribe({
      next: () => {
        this.cleaningExemptionFoodsharingId.set('');
        this.cleaningExemptionReason.set('');
        this.loadCleaningRuleExemptions();
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  deleteCleaningRuleExemption(exemption: FoodsharingCleaningRuleExemption): void {
    this.adminApi.deleteFoodsharingCleaningRuleExemption({ bezirkSlug: this.bezirkContext.currentSlug(), exemptionId: exemption.id }).subscribe({
      next: () => this.loadCleaningRuleExemptions(),
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  saveExcludedDate(): void {
    const storeId = this.excludedDateStoreId();
    const selectedDate = this.excludedDate();
    const excludedDate = selectedDate ? this.formatDateInput(selectedDate) : '';
    if (storeId == null || !excludedDate) {
      return;
    }
    this.adminApi.saveFoodsharingStoreAutomationExcludedDate({
      bezirkSlug: this.bezirkContext.currentSlug(),
      foodsharingStoreAutomationExcludedDateRequest: { storeId, excludedDate }
    }).subscribe({
      next: () => {
        this.excludedDate.set(null);
        this.loadExcludedDates();
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  deleteExcludedDate(excludedDate: FoodsharingStoreAutomationExcludedDate): void {
    if (!excludedDate.id) {
      return;
    }
    const excludedDateId = excludedDate.id;
    this.adminApi.deleteFoodsharingStoreAutomationExcludedDate({
      bezirkSlug: this.bezirkContext.currentSlug(),
      excludedDateId
    }).subscribe({
      next: () => this.loadExcludedDates(),
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  private formatDateInput(date: Date): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  private loadFoodsharingStatus(): void {
    this.adminApi.getFoodsharingStatus().pipe(this.loadTracker.track(), finalize(() => this.statusLoading.set(false))).subscribe({
      next: (status) => {
        this.foodsharingStatus.set(status);
        this.generalSettingsPanel.set(status.connected ? null : 'general-settings');
        if (status.connected) {
          this.loadActiveAutomationTab();
        } else {
          this.foodsharingStores.set([]);
          this.availableStores.set([]);
          this.foodsharingFuturePickupUsers.set([]);
          this.pickupExtraSlotAudit.set([]);
          this.foodsharingAudit.set([]);
          this.requestAutomationAudit.set([]);
          this.advertisementAutomationAudit.set([]);
          this.plannedAdvertisementNotifications.set([]);
          this.telegramChatOptions.set([]);
        }
      },
      error: (error) => this.errorNotifier.notify(error)
    });
  }

  private loadBezirkSettings(): void {
    this.adminApi.getAdminBezirk({ bezirkSlug: this.bezirkContext.currentSlug() }).pipe(takeUntil(this.bezirkChanged)).subscribe({
      next: (bezirk) => {
        this.cleaningStoreConfigured.set(bezirk.cleaningStoreId != null);
        this.userInfoSourceConfigured.set(bezirk.userInfoSourceConfigured);
      },
      error: (error) => {
        this.cleaningStoreConfigured.set(null);
        this.userInfoSourceConfigured.set(null);
        this.errorNotifier.notify(error);
      }
    });
  }

  private loadActiveAutomationTab(): void {
    if (!this.foodsharingStatus()?.connected) {
      return;
    }
    const tab = this.activeAutomationTab();
    switch (tab) {
      case 'applications':
        this.loadStoreAutomation({ loadRequestOverview: true, loadAdvertisementOverview: false });
        this.loadRequestAutomationAuditIfActive();
        break;
      case 'slots':
        this.loadStoreAutomation({ loadRequestOverview: false, loadAdvertisementOverview: false });
        this.loadCleaningRuleExemptions();
        this.loadExcludedDates();
        this.loadFoodsharingAuditIfActive();
        break;
      case 'advertisements':
        this.loadStoreAutomation({ loadRequestOverview: false, loadAdvertisementOverview: true });
        this.loadAdvertisementAutomationAuditIfActive();
        this.loadTelegramChats();
        break;
      case 'storeInfo':
        this.loadStoreInfo();
        this.loadStoreInfoCancellations();
        this.loadTelegramChats();
        break;
      case 'statistics':
        this.loadFoodsharingFuturePickupUsers();
        break;
      case 'metrics':
        this.loadMetrics();
        this.loadPickupExtraSlotAudit();
        break;
    }
  }

  setMetricsDays(days: number | null): void {
    if (!days || days === this.metricsDays()) {
      return;
    }
    this.metricsDays.set(days);
    this.loadMetrics();
  }

  refreshMetrics(): void {
    this.loadMetrics();
    this.loadPickupExtraSlotAudit();
  }

  protected formatDuration(minutes: number | null): string {
    if (minutes == null) {
      return '–';
    }
    if (minutes < 60) {
      return `${minutes} min`;
    }
    const hours = Math.floor(minutes / 60);
    const rest = minutes % 60;
    return rest ? `${hours} h ${rest} min` : `${hours} h`;
  }

  protected formatPercent(value: number | null): string {
    return value == null ? '–' : `${value}%`;
  }

  private metricBars(definitions: [string, string][], hideEmpty: boolean): { label: string; value: number; width: number }[] {
    const counts = this.metricCounts();
    const rows = definitions
      .map(([key, label]) => ({ label: this.i18n.t(label), value: counts.get(key) ?? 0 }))
      .filter((row) => !hideEmpty || row.value > 0);
    if (hideEmpty) {
      rows.sort((left, right) => right.value - left.value);
    }
    const max = Math.max(1, ...rows.map((row) => row.value));
    return rows.map((row) => ({ ...row, width: Math.round((row.value / max) * 100) }));
  }

  private loadMetrics(): void {
    this.metricsLoading.set(true);
    this.adminApi.getFoodsharingMetrics({ bezirkSlug: this.bezirkContext.currentSlug(), days: this.metricsDays() })
      .pipe(finalize(() => this.metricsLoading.set(false)))
      .pipe(this.loadTracker.track(), takeUntil(this.bezirkChanged)).subscribe({
        next: (metrics) => this.metrics.set(metrics),
        error: (error) => {
        this.metrics.set(null);
        this.errorNotifier.notify(error);
      }
      });
  }

  protected canSeeMetrics(): boolean {
    return this.sessionService.hasPermission(UserPermission.CanSeeUserPickupCountGrouping)
      || this.sessionService.hasPermission(UserPermission.CanSeeAllAutomationDecisions);
  }

  private loadPickupExtraSlotAudit(): void {
    this.adminApi.getFoodsharingPickupExtraSlotAudit({ bezirkSlug: this.bezirkContext.currentSlug() }).pipe(this.loadTracker.track(), takeUntil(this.bezirkChanged)).subscribe({
      // Stores only known by id ("#123") have no automation and may belong to another Bezirk.
      next: (audit) => this.pickupExtraSlotAudit.set(newestFirst(
        audit.filter((entry) => !!entry.storeName?.trim() && entry.storeName !== `#${entry.storeId}`),
        (entry) => entry.createdAt)),
      error: (error) => {
        this.pickupExtraSlotAudit.set([]);
        this.errorNotifier.notify(error);
      }
    });
  }

  /** Shows the default text in the editor for links without a custom message. */
  private withLinkedStoreMessages(store: FoodsharingStoreAutomation): FoodsharingStoreAutomation {
    return {
      ...store,
      linkedStores: (store.linkedStores ?? []).map((link) => ({ ...link, message: link.message || this.linkedStoreDefaultMessage() }))
    };
  }

  private reloadActiveAutomationTab(): void {
    this.loadActiveAutomationTab();
  }

  private loadStoreAutomation(options: { loadRequestOverview: boolean; loadAdvertisementOverview: boolean }): void {
    this.adminApi.getFoodsharingStores({ bezirkSlug: this.bezirkContext.currentSlug() }).pipe(this.loadTracker.track(), takeUntil(this.bezirkChanged)).subscribe({
      next: (overview: FoodsharingStoreAutomationOverview) => {
        this.linkedStoreDefaultMessage.set(overview.linkedStoreDefaultMessage);
        this.gapStoreGroups.set(overview.gapStoreGroups);
        // Read-only automations of other accounts may be on stores this account cannot automate.
        const managedStoreIds = new Set(overview.managedStoreIds ?? overview.automations.map((store) => store.storeId));
        this.managedStores.set([
          ...overview.automations
            .filter((store) => managedStoreIds.has(store.storeId))
            .map((store) => ({ storeId: store.storeId, storeName: store.storeName })),
          ...overview.availableStores
        ].sort((left, right) => left.storeName.localeCompare(right.storeName)));
        const stores: FoodsharingStoreAutomation[] = overview.automations
          .map((store) => ({ ...this.withLinkedStoreMessages(store), slotApprovalConfigured: true } as FoodsharingStoreAutomation & Record<string, unknown>));
        if (this.sessionService.hasPermission(UserPermission.CanUseAutomations)) {
          for (const availableStore of overview.availableStores) {
            if (!stores.some((store) => store.storeId === availableStore.storeId)) {
              const gapGroup = this.gapStoreGroupOf(availableStore.storeId);
              stores.push({
                storeId: availableStore.storeId,
                storeName: availableStore.storeName,
                enabled: false,
                dryRunEnabled: true,
                gapRuleEnabled: false,
                minimumGapDays: 0,
                gapStores: gapGroup?.stores.filter((entry) => entry.storeId !== availableStore.storeId) ?? [],
                gapStoresMinimumDays: gapGroup?.minimumGapDays,
                cleaningRuleEnabled: false,
                experienceRuleEnabled: false,
                publicHolidayRuleEnabled: false,
                editable: true,
                incomingLinkedStores: []
              });
            }
          }
        }
        this.foodsharingStores.set(stores);
        this.availableStores.set(overview.availableStores);
        if (options.loadRequestOverview) {
          this.loadRequestAutomationOverview();
        }
        if (options.loadAdvertisementOverview) {
          this.loadAdvertisementAutomationOverview();
        }
      },
      error: (error) => this.errorNotifier.notify(error)
    });
  }

  refreshFoodsharingFuturePickupUsers(): void {
    this.loadFoodsharingFuturePickupUsers(true);
  }

  private loadFoodsharingFuturePickupUsers(forceRefresh = false): void {
    this.refreshingFoodsharingFuturePickupUsers.set(true);
    this.adminApi.getFoodsharingFuturePickupUsers({
      bezirkSlug: this.bezirkContext.currentSlug(),
      forceRefresh
    }).pipe(finalize(() => this.refreshingFoodsharingFuturePickupUsers.set(false))).pipe(this.loadTracker.track(), takeUntil(this.bezirkChanged)).subscribe({
      next: (users) => this.foodsharingFuturePickupUsers.set(users),
      error: (error) => this.errorNotifier.notify(error)
    });
  }


  private loadRequestAutomationOverview(): void {
    this.adminApi.getFoodsharingRequestAutomationOverview({ bezirkSlug: this.bezirkContext.currentSlug() }).pipe(this.loadTracker.track(), takeUntil(this.bezirkChanged)).subscribe({
      next: (overview: FoodsharingRequestAutomationOverview) => {
        const stores = this.foodsharingStores().map((store) => ({ ...store }) as FoodsharingStoreAutomation & Record<string, unknown>);
        for (const requestAutomation of overview.requestAutomations || []) {
          const store = this.storeEntryFor(stores, requestAutomation.storeId, requestAutomation.storeName);
          if (store) {
            store['requestConfigured'] = true;
            store['requestEditable'] = requestAutomation.editable;
            store['requestOwnerName'] = requestAutomation.ownerName;
            store['requestOwnerEmail'] = requestAutomation.ownerEmail;
            store['requestEnabled'] = requestAutomation.enabled;
            store['requestDryRunEnabled'] = requestAutomation.dryRunEnabled;
            store['requestDistanceRuleEnabled'] = requestAutomation.distanceRuleEnabled;
            store['requestMaximumDistanceKm'] = requestAutomation.maximumDistanceKm;
          }
        }
        this.foodsharingStores.set(stores as FoodsharingStoreAutomation[]);
      },
      error: (error) => this.errorNotifier.notify(error)
    });
  }

  private loadAdvertisementAutomationOverview(): void {
    this.adminApi.getFoodsharingOpenSlotAdvertisementOverview({ bezirkSlug: this.bezirkContext.currentSlug() }).pipe(this.loadTracker.track(), takeUntil(this.bezirkChanged)).subscribe({
      next: (overview: FoodsharingOpenSlotAdvertisementOverview) => {
        const stores = this.foodsharingStores().map((store) => ({ ...store }) as FoodsharingStoreAutomation & Record<string, unknown>);
        for (const advert of overview.advertisementAutomations || []) {
          const store = this.storeEntryFor(stores, advert.storeId, advert.storeName);
          if (store) {
            const number = advert.advertNumber;
            store['advertEditable'] = advert.editable;
            store['advertOwnerName'] = advert.ownerName;
            store['advertOwnerEmail'] = advert.ownerEmail;
            store[`advertConfigured${number}`] = true;
            store[`advertEnabled${number}`] = advert.enabled;
            store[`advertDryRunEnabled${number}`] = advert.dryRunEnabled;
            store[`advertHoursBefore${number}`] = advert.triggerHoursBefore;
            store[`advertSendToStoreChat${number}`] = advert.sendToStoreChat;
            store[`advertSendToTelegram${number}`] = advert.sendToTelegram;
            store[`advertTelegramChatId${number}`] = advert.telegramChatId;
            store[`advertStoreMessages${number}`] = (advert.storeMessages || []).join('\n---\n');
            store[`advertTelegramMessages${number}`] = (advert.telegramMessages || []).join('\n---\n');
            const settingsNumber = this.lateCancellationSettingsNumber(store);
            if (settingsNumber === number) {
              store[`advertLateCancellationMessage${number}`] = advert.lateCancellationMessage;
              store[`advertSendLateCancellationMessage${number}`] = advert.sendLateCancellationMessage;
              store[`advertLateCancellationMessageMaximumHoursBeforePickup${number}`] = advert.lateCancellationMessageMaximumHoursBeforePickup;
              store[`advertSendLatestAdvertisementAfterLateCancellation${number}`] = advert.sendLatestAdvertisementAfterLateCancellation;
            }
          }
        }
        this.foodsharingStores.set(stores as FoodsharingStoreAutomation[]);
      },
      error: (error) => this.errorNotifier.notify(error)
    });
  }


  /**
   * The store entry a request/advertisement automation belongs to. Another account's automation may be on a store
   * without a slot automation visible here; it gets a read-only entry of its own.
   */
  private storeEntryFor(stores: (FoodsharingStoreAutomation & Record<string, unknown>)[], storeId: number, storeName: string)
    : (FoodsharingStoreAutomation & Record<string, unknown>) {
    const existing = stores.find((entry) => entry.storeId === storeId);
    if (existing) {
      return existing;
    }
    const placeholder = {
      storeId,
      storeName,
      enabled: false,
      dryRunEnabled: true,
      gapRuleEnabled: false,
      minimumGapDays: 0,
      cleaningRuleEnabled: false,
      experienceRuleEnabled: false,
      publicHolidayRuleEnabled: false,
      editable: false,
      incomingLinkedStores: []
    } as FoodsharingStoreAutomation & Record<string, unknown>;
    stores.push(placeholder);
    return placeholder;
  }

  refreshTelegramChats(): void {
    this.loadTelegramChats();
  }

  private loadRequestAutomationAudit(): void {
    this.adminApi.getFoodsharingRequestAutomationAudit({ bezirkSlug: this.bezirkContext.currentSlug(), onlyMine: this.onlyMyDecisions() }).pipe(this.loadTracker.track(), takeUntil(this.bezirkChanged)).subscribe({
      next: (audit) => this.requestAutomationAudit.set(newestFirst(audit, (entry) => entry.createdAt)),
      error: (error) => this.errorNotifier.notify(error)
    });
  }

  private loadAdvertisementAutomationAudit(): void {
    const onlyMine = this.onlyMyDecisions();
    const bezirkSlug = this.bezirkContext.currentSlug();
    this.adminApi.getFoodsharingOpenSlotAdvertisementSentAudit({ bezirkSlug, onlyMine }).pipe(this.loadTracker.track(), takeUntil(this.bezirkChanged)).subscribe({
      next: (audit) => this.advertisementAutomationAudit.set(newestFirst(audit, (entry) => entry.createdAt)),
      error: (error) => this.errorNotifier.notify(error)
    });
    this.adminApi.getFoodsharingOpenSlotAdvertisementPlannedNotifications({ bezirkSlug, onlyMine }).pipe(this.loadTracker.track(), takeUntil(this.bezirkChanged)).subscribe({
      next: (notifications) => this.plannedAdvertisementNotifications.set(notifications),
      error: (error) => this.errorNotifier.notify(error)
    });
  }

  private loadRequestAutomationAuditIfActive(): void {
    if (this.activeAutomationTab() === 'applications') {
      this.loadRequestAutomationAudit();
    }
  }

  private loadAdvertisementAutomationAuditIfActive(): void {
    if (this.activeAutomationTab() === 'advertisements') {
      this.loadAdvertisementAutomationAudit();
    }
  }

  private loadTelegramChats(): void {
    if (!this.sessionService.hasPermission(UserPermission.CanUseAutomations)) {
      this.telegramChatOptions.set([]);
      return;
    }
    this.adminApi.getFoodsharingTelegramChats().pipe(this.loadTracker.track(), takeUntil(this.bezirkChanged)).subscribe({
      next: (chats) => this.telegramChatOptions.set(chats.map((chat) => ({ label: `${chat.title} (${chat.type || chat.id})`, value: chat.id }))),
      error: (error) => {
        this.telegramChatOptions.set([]);
        this.errorNotifier.notify(error);
      }
    });
  }

  private loadCleaningRuleExemptions(): void {
    if (!this.sessionService.hasPermission(UserPermission.CanUseAutomations)) {
      this.cleaningRuleExemptions.set([]);
      return;
    }
    this.adminApi.getFoodsharingCleaningRuleExemptions({ bezirkSlug: this.bezirkContext.currentSlug() }).pipe(this.loadTracker.track(), takeUntil(this.bezirkChanged)).subscribe({
      next: (exemptions) => this.cleaningRuleExemptions.set(exemptions),
      error: (error) => this.errorNotifier.notify(error)
    });
  }

  private loadExcludedDates(): void {
    if (!this.sessionService.hasPermission(UserPermission.CanUseAutomations)) {
      this.excludedDates.set([]);
      return;
    }
    this.adminApi.getFoodsharingStoreAutomationExcludedDates({ bezirkSlug: this.bezirkContext.currentSlug() }).pipe(this.loadTracker.track(), takeUntil(this.bezirkChanged)).subscribe({
      next: (excludedDates) => this.excludedDates.set(excludedDates),
      error: (error) => this.errorNotifier.notify(error)
    });
  }

  private loadFoodsharingAudit(): void {
    this.adminApi.getFoodsharingAutomationAudit({
      bezirkSlug: this.bezirkContext.currentSlug(),
      onlyMine: this.onlyMyDecisions(),
      excludeConfirmed: this.hideConfirmedDecisions()
    }).pipe(this.loadTracker.track(), takeUntil(this.bezirkChanged)).subscribe({
      next: (audit) => this.foodsharingAudit.set(newestFirst(audit, (entry) => entry.createdAt)),
      error: (error) => this.errorNotifier.notify(error)
    });
  }

  private loadFoodsharingAuditIfActive(): void {
    if (this.activeAutomationTab() === 'slots') {
      this.loadFoodsharingAudit();
    }
  }

  private toRunResult(result: AutomationRunSummary): FoodsharingRunResult {
    return {
      evaluated: result.evaluated,
      confirmed: result.acted,
      declined: result.skipped,
      failed: 0,
      dryRun: result.dryRun,
      messages: result.messages || []
    };
  }

  protected normalizeStoreId(value: number | { value?: number } | null): number | null {
    if (typeof value === 'number') {
      return value;
    }
    return value?.value ?? null;
  }

  storeInfoChatOptions(): { label: string; value: string }[] {
    const options = [...this.telegramChatOptions()];
    for (const chatId of this.storeInfoForm.telegramChatIds) {
      if (!options.some((option) => option.value === chatId)) {
        options.push({ label: chatId, value: chatId });
      }
    }
    return options;
  }

  storeInfoStoreOptions(): { label: string; value: number }[] {
    const settings = this.storeInfo();
    const stores = [...(settings?.availableStores ?? [])];
    for (const store of settings?.stores ?? []) {
      if (!stores.some((candidate) => candidate.storeId === store.storeId)) {
        stores.push(store);
      }
    }
    return stores.map((store) => ({ label: `${store.storeName} (${store.storeId})`, value: store.storeId }));
  }

  addStoreInfoChatId(): void {
    const chatId = this.storeInfoManualChatId.trim();
    if (!chatId) {
      return;
    }
    if (!this.storeInfoForm.telegramChatIds.includes(chatId)) {
      this.storeInfoForm = { ...this.storeInfoForm, telegramChatIds: [...this.storeInfoForm.telegramChatIds, chatId] };
    }
    this.storeInfoManualChatId = '';
  }

  saveStoreInfo(): void {
    const form = this.storeInfoForm;
    if (form.enabled) {
      if (form.telegramChatIds.length === 0) {
        this.toastError(this.i18n.t('automation.storeInfoSelectRecipient'));
        return;
      }
      if (form.storeIds.length === 0) {
        this.toastError(this.i18n.t('automation.storeInfoSelectStore'));
        return;
      }
    }
    const storeOptions = this.storeInfoStoreOptions();
    this.storeInfoSaving.set(true);
    this.adminApi.saveFoodsharingStoreInfoSettings({
      foodsharingStoreInfoSettingsRequest: {
        enabled: form.enabled,
        cutoffEnabled: form.cutoffEnabled,
        maximumHoursBeforePickup: Math.max(1, Number(form.maximumHoursBeforePickup) || 72),
        telegramChatIds: form.telegramChatIds,
        stores: form.storeIds.map((storeId) => {
          const label = storeOptions.find((option) => option.value === storeId)?.label ?? '';
          return { storeId, storeName: label.replace(/ \(\d+\)$/, '') };
        })
      }
    }).pipe(finalize(() => this.storeInfoSaving.set(false))).subscribe({
      next: (settings) => {
        this.applyStoreInfo(settings);
        this.messageService.add({ severity: 'success', summary: this.i18n.t('common.saved') });
      },
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  sendStoreInfoTestMessage(): void {
    if (this.storeInfoForm.telegramChatIds.length === 0) {
      this.toastError(this.i18n.t('automation.storeInfoSelectRecipient'));
      return;
    }
    this.storeInfoTesting.set(true);
    this.adminApi.sendFoodsharingStoreInfoTestMessage({
      foodsharingStoreInfoTestMessageRequest: { telegramChatIds: this.storeInfoForm.telegramChatIds }
    }).pipe(finalize(() => this.storeInfoTesting.set(false))).subscribe({
      next: () => this.messageService.add({ severity: 'success', summary: this.i18n.t('automation.telegramTestSent') }),
      error: (error) => this.toastError(resolveApiError(error, this.i18n))
    });
  }

  /** Readable label for audit codes such as WOULD_CONFIRM or DECLINED; unknown codes fall back to plain words. */
  /** How many users of the statistics list are shown; "Mehr laden" adds more. */
  readonly statsLimit = signal(20);

  codeLabel(code: string | null | undefined): string {
    if (!code) {
      return '-';
    }
    const key = `automation.code.${code}`;
    const label = this.i18n.t(key);
    return label === key ? code.toLowerCase().replace(/_/g, ' ') : label;
  }

  storeInfoStatusLabel(status: string): string {
    switch (status) {
      case 'OPEN':
        return this.i18n.t('automation.storeInfoStatusOpen');
      case 'NOT_REPLACED':
        return this.i18n.t('automation.storeInfoStatusNotReplaced');
      case 'REPLACED':
        return this.i18n.t('automation.storeInfoStatusReplaced');
      case 'REJOINED':
        return this.i18n.t('automation.storeInfoStatusRejoined');
      default:
        return this.i18n.t('automation.storeInfoStatusUnknown');
    }
  }

  storeInfoStatusSeverity(status: string): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
    switch (status) {
      case 'OPEN':
        return 'warn';
      case 'NOT_REPLACED':
        return 'danger';
      case 'REPLACED':
      case 'REJOINED':
        return 'success';
      default:
        return 'secondary';
    }
  }

  private loadStoreInfo(): void {
    this.adminApi.getFoodsharingStoreInfoSettings().pipe(this.loadTracker.track(), takeUntil(this.bezirkChanged)).subscribe({
      next: (settings) => this.applyStoreInfo(settings),
      error: (error) => this.errorNotifier.notify(error)
    });
  }

  private loadStoreInfoCancellations(): void {
    this.adminApi.getFoodsharingStoreInfoCancellations().pipe(this.loadTracker.track(), takeUntil(this.bezirkChanged)).subscribe({
      next: (cancellations) => this.storeInfoCancellations.set(newestFirst(cancellations, (entry) => entry.cancelledAt)),
      error: (error) => {
        this.storeInfoCancellations.set([]);
        this.errorNotifier.notify(error);
      }
    });
  }

  private applyStoreInfo(settings: FoodsharingStoreInfoSettings): void {
    this.storeInfo.set(settings);
    this.storeInfoForm = {
      enabled: settings.enabled,
      cutoffEnabled: settings.cutoffEnabled,
      maximumHoursBeforePickup: settings.maximumHoursBeforePickup,
      telegramChatIds: [...settings.telegramChatIds],
      storeIds: settings.stores.map((store) => store.storeId)
    };
  }

  private toastError(detail: string): void {
    this.messageService.add({ severity: 'error', summary: this.i18n.t('common.error'), detail });
  }
}

/** Returns a copy sorted by the given timestamp, newest first. Entries without a timestamp go last. */
function newestFirst<T>(entries: T[], timestamp: (entry: T) => string | null | undefined): T[] {
  const time = (entry: T) => {
    const value = timestamp(entry);
    const parsed = value ? Date.parse(value) : NaN;
    return Number.isNaN(parsed) ? Number.NEGATIVE_INFINITY : parsed;
  };
  return [...entries].sort((left, right) => time(right) - time(left));
}

const SLOT_RULES = ['GAP', 'EXPERIENCE', 'CLEANING', 'NO_CLEANING_STORE', 'PUBLIC_HOLIDAY', 'MANUAL_DATE', 'HOLIDAY_CALENDAR', 'OTHER'];

/** Whole-number percentage, or null when the denominator is zero. */
function percent(part: number, total: number): number | null {
  return total > 0 ? Math.round((part / total) * 100) : null;
}
