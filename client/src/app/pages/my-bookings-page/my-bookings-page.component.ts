import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';

import { BookingDetailResponse, BookingListResponse, SlotStatus, UserService } from '../../api';
import { resolveApiError } from '../../core/api-error';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { downloadBookingIcs } from '../../core/booking-ics';
import { confirmAction } from '../../core/confirm-action';
import { I18nService } from '../../core/i18n.service';
import { LoadingIndicatorComponent } from '../../core/loading-indicator.component';
import { ZurichDateTimePipe } from '../../core/zurich-date-time.pipe';
import { CardModule } from 'primeng/card';
import { ButtonModule } from 'primeng/button';
import { PaginatorModule } from 'primeng/paginator';
import { TagModule } from 'primeng/tag';
import { ConfirmationService, MessageService } from 'primeng/api';
import { NewcomerProgressCardComponent } from './newcomer-progress-card.component';

@Component({
  selector: 'app-my-bookings-page',
  standalone: true,
  imports: [
    LoadingIndicatorComponent,
    CommonModule,
    RouterLink,
    ZurichDateTimePipe,
    CardModule,
    ButtonModule,
    PaginatorModule,
    TagModule,
    NewcomerProgressCardComponent,
  ],
  templateUrl: './my-bookings-page.component.html',
  styleUrl: './my-bookings-page.component.scss',
})
export class MyBookingsPageComponent implements OnInit {
  readonly i18n = inject(I18nService);
  readonly SlotStatus = SlotStatus;
  protected readonly bookings = signal<BookingDetailResponse[]>([]);
  protected readonly bookingsPage = signal<BookingListResponse | null>(null);
  protected readonly loading = signal(true);
  protected readonly pageSize = 20;
  protected readonly expanded = signal<ReadonlySet<string>>(new Set());
  /** Upcoming bookings, soonest first; the first one shows its details right away. */
  protected readonly upcoming = computed(() => this.bookings()
    .filter((booking) => !this.isPast(booking.startDateTime) && booking.status !== SlotStatus.Done)
    .sort((a, b) => a.startDateTime.localeCompare(b.startDateTime)));
  /** Past bookings, newest first, folded away below. */
  protected readonly past = computed(() => this.bookings()
    .filter((booking) => !this.upcoming().includes(booking))
    .sort((a, b) => b.startDateTime.localeCompare(a.startDateTime)));
  private readonly progressCard = viewChild(NewcomerProgressCardComponent);

  private readonly userApi = inject(UserService);
  protected readonly bezirkContext = inject(BezirkContextService);
  private readonly messageService = inject(MessageService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly dateTime = new ZurichDateTimePipe();

  ngOnInit(): void {
    this.reload();
  }

  confirmCancel(booking: BookingDetailResponse): void {
    const slotId = booking.slotId;
    const when = this.dateTime.transform(booking.startDateTime, 'medium');
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('confirmDialog.cancelBooking.title'),
      acceptLabel: this.i18n.t('confirmDialog.cancelBooking.accept'),
      rejectLabel: this.i18n.t('common.keep'),
      destructive: true,
      message: this.i18n.t('confirm.cancelBookingOn').replace('{0}', when),
      accept: () => {
        this.userApi
          .cancelMyBooking({ bezirkSlug: this.bezirkContext.currentSlug(), slotId })
          .subscribe({
            next: () => {
              this.reload();
              this.progressCard()?.reload();
            },
            error: (error) =>
              this.messageService.add({
                severity: 'error',
                summary: resolveApiError(error, this.i18n),
              }),
          });
      },
    });
  }

  protected dateParts(startDateTime: string): { day: string; month: string; weekday: string; time: string } {
    const date = new Date(startDateTime);
    const locale = this.i18n.language() === 'en' ? 'en-CH' : 'de-CH';
    const part = (options: Intl.DateTimeFormatOptions) =>
      new Intl.DateTimeFormat(locale, { ...options, timeZone: 'Europe/Zurich' }).format(date);
    return {
      day: part({ day: 'numeric' }),
      month: part({ month: 'short' }).replace('.', ''),
      weekday: part({ weekday: 'short' }).replace('.', ''),
      time: part({ hour: '2-digit', minute: '2-digit' }),
    };
  }

  protected mapsLink(address: string): string {
    return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(address)}`;
  }

  /** Calendar file for one booking, built in the browser (assumes a 90-minute EinAb). */
  protected downloadIcs(booking: BookingDetailResponse): void {
    downloadBookingIcs(booking, this.i18n);
  }

  protected isExpanded(slotId: string): boolean {
    return this.expanded().has(slotId);
  }

  protected toggleDetails(slotId: string): void {
    const next = new Set(this.expanded());
    if (!next.delete(slotId)) {
      next.add(slotId);
    }
    this.expanded.set(next);
  }

  onPageChange(event: { page?: number }): void {
    this.loadPage(event.page ?? 0);
  }

  protected isPast(startDateTime: string): boolean {
    return new Date(startDateTime).getTime() <= Date.now();
  }

  private reload(): void {
    this.loadPage(this.bookingsPage()?.page ?? 0);
  }

  private loadPage(page: number): void {
    this.userApi
      .getMyBookings({ bezirkSlug: this.bezirkContext.currentSlug(), page, size: this.pageSize })
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (response) => {
          this.bookings.set(response.bookings);
          this.bookingsPage.set(response);
        },
        error: (error) =>
          this.messageService.add({
            severity: 'error',
            summary: resolveApiError(error, this.i18n),
          }),
      });
  }
}
