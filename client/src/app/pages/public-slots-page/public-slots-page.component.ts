import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subject, debounceTime, finalize } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import {
  AvailableSlotResponse,
  AvailableSlotListResponse,
  BookSlotRequest,
  BookingDetailResponse,
  EinAbCategory,
  NotificationChannel,
  NotificationStatusResponse,
  PublicService,
  UserPermission,
  UserService
} from '../../api';
import { resolveApiError } from '../../core/api-error';
import { downloadBookingIcs } from '../../core/booking-ics';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { I18nService } from '../../core/i18n.service';
import { SessionService } from '../../core/session.service';
import { ZurichDateTimePipe, zurichDayKey } from '../../core/zurich-date-time.pipe';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { CheckboxModule } from 'primeng/checkbox';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { PaginatorModule } from 'primeng/paginator';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { ToastModule } from 'primeng/toast';
import { MessageService } from 'primeng/api';
import { SkeletonComponent, SkeletonRegionDirective } from '../../core/skeleton';

interface SlotGroup {
  key: string;
  slots: AvailableSlotResponse[];
}

interface SlotDay {
  key: string;
  date: string;
  groups: SlotGroup[];
}

@Component({
  selector: 'app-public-slots-page',
  standalone: true,
  imports: [SkeletonComponent, SkeletonRegionDirective,
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    RouterLink,
    ZurichDateTimePipe,
    TableModule,
    CardModule,
    ButtonModule,
    DialogModule,
    InputTextModule,
    CheckboxModule,
    SelectModule,
    PaginatorModule,
    TagModule,
    ToastModule
  ],
  templateUrl: './public-slots-page.component.html',
  styleUrl: './public-slots-page.component.scss'
})
export class PublicSlotsPageComponent implements OnInit {
  readonly i18n = inject(I18nService);
  protected readonly bezirkContext = inject(BezirkContextService);

  protected readonly categoryOptions = computed(() => this.bezirkContext.enabledCategories(this.bezirkContext.selectedBezirk()?.slug).map((value) => ({
    value,
    label: this.i18n.categoryLabel(value)
  })));
  protected readonly canBookEinAbs = computed(() => !this.sessionService.hasPermission(UserPermission.CanGiveEinAbs));
  protected readonly slots = signal<AvailableSlotResponse[]>([]);
  protected readonly slotsPage = signal<AvailableSlotListResponse | null>(null);
  protected readonly loading = signal(false);
  protected readonly bookingLoading = signal(false);
  protected readonly subscribeLoading = signal(false);
  protected slotDetailsVisible = false;
  protected selectedSlot: AvailableSlotResponse | null = null;
  /** Set after a successful booking: the dialog then shows the confirmation with all details. */
  protected readonly bookedDetail = signal<BookingDetailResponse | null>(null);
  /** How many free places the opened EinAb has (merged slots of the same EinAb). */
  protected selectedFreeCount = 1;
  /** Slots grouped by Zurich day, and slots of the same EinAb merged into one row. */
  protected readonly slotDays = computed<SlotDay[]>(() => {
    const days: SlotDay[] = [];
    for (const slot of this.slots()) {
      const dayKey = zurichDayKey(slot.startDateTime);
      let day = days.find((candidate) => candidate.key === dayKey);
      if (!day) {
        day = { key: dayKey, date: slot.startDateTime, groups: [] };
        days.push(day);
      }
      const groupKey = [slot.startDateTime, slot.category, slot.teacherId ?? slot.teacherName, slot.publicLocation ?? slot.location ?? '', slot.visitFairteiler].join('|');
      const group = day.groups.find((candidate) => candidate.key === groupKey);
      if (group) {
        group.slots.push(slot);
      } else {
        day.groups.push({ key: groupKey, slots: [slot] });
      }
    }
    return days;
  });
  private readonly searchChanges = new Subject<string>();
  /** Slot to open once the list is loaded (back from the login page). */
  private pendingSlotId: string | null = null;
  protected search = '';
  protected fairteilerOnly = false;
  protected selectedCategory?: EinAbCategory;
  protected readonly pageSize = 20;
  protected readonly subscriptionForm = inject(FormBuilder).nonNullable.group({
    channel: [NotificationChannel.Foodsharing as NotificationChannel, Validators.required],
    email: ['', [Validators.email]]
  });
  protected readonly channelOptions = computed(() => [
    { value: NotificationChannel.Foodsharing, label: this.i18n.t('slots.channel.FOODSHARING') },
    { value: NotificationChannel.Email, label: this.i18n.t('slots.channel.EMAIL') }
  ]);
  protected readonly notificationStatus = signal<NotificationStatusResponse | null>(null);
  protected readonly isAuthenticated = computed(() => this.sessionService.isAuthenticated());

  private readonly publicApi = inject(PublicService);
  private readonly userApi = inject(UserService);
  private readonly route = inject(ActivatedRoute);
  private readonly sessionService = inject(SessionService);
  private readonly messageService = inject(MessageService);

  constructor() {
    this.searchChanges.pipe(debounceTime(300), takeUntilDestroyed()).subscribe(() => this.reloadFromFirstPage());
  }

  ngOnInit(): void {
    const bezirkRoute = this.route.parent;
    if (!bezirkRoute) {
      return;
    }
    // The newcomer progress card links here with ?fairteiler=1 to show only EinAbs with a Fairteiler visit.
    this.fairteilerOnly = this.route.snapshot.queryParamMap.get('fairteiler') === '1';
    this.pendingSlotId = this.route.snapshot.queryParamMap.get('slot');
    bezirkRoute.paramMap.subscribe(() => {
      this.slotsPage.set(null);
      this.loadSlots();
      this.loadNotificationStatus();
    });
  }

  loadSlots(): void {
    this.loading.set(true);
    this.publicApi.getAvailableSlots({
      bezirkSlug: this.bezirkContext.currentSlug(),
      search: this.search || undefined,
      category: this.selectedCategory,
      visitFairteiler: this.fairteilerOnly ? true : undefined,
      page: this.slotsPage()?.page ?? 0,
      size: this.pageSize
    }).pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (response) => {
          this.slots.set(response.slots);
          this.slotsPage.set(response);
          this.openPendingSlot();
        },
        error: (error) => this.toastError(resolveApiError(error, this.i18n))
      });
  }

  onSlotsPageChange(event: { page?: number }): void {
    this.slotsPage.update((current) => current ? { ...current, page: event.page ?? 0 } : current);
    this.loadSlots();
  }

  onSearchChange(value: string): void {
    this.search = value;
    this.searchChanges.next(value);
  }

  selectCategory(category: EinAbCategory | undefined): void {
    this.selectedCategory = this.selectedCategory === category ? undefined : category;
    this.reloadFromFirstPage();
  }

  toggleFairteiler(): void {
    this.fairteilerOnly = !this.fairteilerOnly;
    this.reloadFromFirstPage();
  }

  resetFilters(): void {
    this.search = '';
    this.selectedCategory = undefined;
    this.fairteilerOnly = false;
    this.reloadFromFirstPage();
  }

  protected hasFilters(): boolean {
    return !!this.search.trim() || !!this.selectedCategory || this.fairteilerOnly;
  }

  /** Banner on top: hidden for verified users, who do not need it any more. */
  protected showNotificationBanner(): boolean {
    if (!this.canBookEinAbs()) {
      return false;
    }
    if (!this.isAuthenticated()) {
      return true;
    }
    const status = this.notificationStatus();
    return !!status && !status.verified;
  }

  protected freeLabel(count: number, category?: EinAbCategory): string {
    if (this.isOnline(category)) {
      return this.i18n.t('common.unlimited');
    }
    return this.i18n.t(count === 1 ? 'slots.freeOne' : 'slots.freeMany').replace('{0}', String(count));
  }

  protected mapsLink(address: string): string {
    return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(address)}`;
  }

  openSlotDetails(slot: AvailableSlotResponse, freeCount = 1): void {
    this.bookedDetail.set(null);
    this.selectedSlot = slot;
    this.selectedFreeCount = freeCount;
    this.slotDetailsVisible = true;
  }

  performBooking(): void {
    if (!this.selectedSlot || !this.isAuthenticated() || this.bookingLoading()) {
      return;
    }
    this.bookingLoading.set(true);
    const bookSlotRequest: BookSlotRequest = {
      language: this.i18n.apiLanguage()
    };
    this.userApi.bookSlot({
      bezirkSlug: this.bezirkContext.currentSlug(),
      slotId: this.selectedSlot.slotId,
      bookSlotRequest
    }).pipe(finalize(() => this.bookingLoading.set(false)))
      .subscribe({
        next: (booking) => {
          this.bookedDetail.set(booking);
          this.loadSlots();
        },
        error: (error) => this.toastError(resolveApiError(error, this.i18n))
      });
  }

  protected downloadIcs(booking: BookingDetailResponse): void {
    downloadBookingIcs(booking, this.i18n);
  }

  /** Back to this list after login; with a slot id the booking dialog opens again. */
  protected loginReturnUrl(slotId?: string): string {
    const base = `/bezirke/${this.bezirkContext.currentSlug()}`;
    return slotId ? `${base}?slot=${encodeURIComponent(slotId)}` : base;
  }

  private reloadFromFirstPage(): void {
    this.slotsPage.update((current) => current ? { ...current, page: 0 } : current);
    this.loadSlots();
  }

  private openPendingSlot(): void {
    if (!this.pendingSlotId) {
      return;
    }
    const slot = this.slots().find((candidate) => candidate.slotId === this.pendingSlotId);
    this.pendingSlotId = null;
    if (slot) {
      const group = this.slotDays().flatMap((day) => day.groups).find((candidate) => candidate.slots.includes(slot));
      this.openSlotDetails(slot, group?.slots.length ?? 1);
    }
  }

  subscribe(): void {
    const { channel, email } = this.subscriptionForm.getRawValue();
    if (channel === NotificationChannel.Email && !email.trim()) {
      this.toastError(this.i18n.t('slots.subscribeEmailRequired'));
      return;
    }
    this.subscribeLoading.set(true);
    this.userApi.subscribeMyNotifications({
      bezirkSlug: this.bezirkContext.currentSlug(),
      notificationSubscribeRequest: {
        language: this.i18n.apiLanguage(),
        channel,
        email: channel === NotificationChannel.Email ? email.trim() : undefined
      }
    })
      .pipe(finalize(() => this.subscribeLoading.set(false)))
      .subscribe({
        next: () => {
          this.messageService.add({ severity: 'success', summary: this.i18n.t('subscribe.success') });
          this.loadNotificationStatus();
        },
        error: (error) => this.toastError(resolveApiError(error, this.i18n))
      });
  }

  unsubscribe(): void {
    this.subscribeLoading.set(true);
    this.userApi.unsubscribeMyNotifications()
      .pipe(finalize(() => this.subscribeLoading.set(false)))
      .subscribe({
        next: () => {
          this.messageService.add({ severity: 'success', summary: this.i18n.t('subscribe.stopped') });
          this.loadNotificationStatus();
        },
        error: (error) => this.toastError(resolveApiError(error, this.i18n))
      });
  }

  private loadNotificationStatus(): void {
    if (!this.sessionService.isAuthenticated()) {
      this.notificationStatus.set(null);
      return;
    }
    this.userApi.getMyNotificationStatus().subscribe({
      next: (status) => this.notificationStatus.set(status),
      error: () => this.notificationStatus.set(null)
    });
  }

  private toastError(detail: string): void {
    this.messageService.add({ severity: 'error', summary: this.i18n.t('common.error'), detail });
  }

  protected displayPublicLocation(slot: AvailableSlotResponse | null | undefined): string {
    if (slot?.category === EinAbCategory.Online) {
      return this.i18n.t('category.online');
    }
    return slot?.publicLocation ?? slot?.location ?? '-';
  }

  protected isOnline(category: EinAbCategory | undefined): boolean {
    return category === EinAbCategory.Online;
  }
}
