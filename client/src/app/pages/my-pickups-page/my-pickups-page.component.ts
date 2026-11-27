import { CommonModule } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';

import { MyPickupListResponse, MyPickupResponse, MyPickupStoreResponse, UserService } from '../../api';
import { resolveApiError } from '../../core/api-error';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { confirmAction } from '../../core/confirm-action';
import { I18nService } from '../../core/i18n.service';
import { ZurichDateTimePipe } from '../../core/zurich-date-time.pipe';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { TagModule } from 'primeng/tag';
import { ConfirmationService, MessageService } from 'primeng/api';
import { SkeletonComponent, SkeletonRegionDirective } from '../../core/skeleton';

@Component({
  selector: 'app-my-pickups-page',
  standalone: true,
  imports: [SkeletonComponent, SkeletonRegionDirective, CommonModule, ZurichDateTimePipe, ButtonModule, CardModule, TagModule],
  templateUrl: './my-pickups-page.component.html',
  styleUrl: './my-pickups-page.component.scss',
})
export class MyPickupsPageComponent implements OnInit {
  readonly i18n = inject(I18nService);
  protected readonly stores = signal<MyPickupStoreResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly savingKey = signal<string | null>(null);

  private readonly userApi = inject(UserService);
  private readonly bezirkContext = inject(BezirkContextService);
  private readonly messageService = inject(MessageService);
  private readonly confirmationService = inject(ConfirmationService);

  ngOnInit(): void {
    this.userApi.getMyPickups({ bezirkSlug: this.bezirkContext.currentSlug() }).subscribe({
      next: (response) => this.apply(response),
      error: (error) => {
        this.loading.set(false);
        this.messageService.add({ severity: 'error', summary: resolveApiError(error, this.i18n) });
      },
    });
  }

  protected key(store: MyPickupStoreResponse, pickup: MyPickupResponse): string {
    return `${store.storeId}|${pickup.pickupDate}`;
  }

  protected isPast(pickup: MyPickupResponse): boolean {
    return new Date(pickup.pickupDate).getTime() <= Date.now();
  }

  confirmAddSlot(store: MyPickupStoreResponse, pickup: MyPickupResponse): void {
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('pickups.addSlot'),
      acceptLabel: this.i18n.t('confirmDialog.addSlot.accept'),
      message: this.i18n.t('pickups.addSlotConfirm'),
      accept: () => this.addSlot(store, pickup),
    });
  }

  private addSlot(store: MyPickupStoreResponse, pickup: MyPickupResponse): void {
    this.savingKey.set(this.key(store, pickup));
    this.userApi
      .addMyPickupExtraSlot({
        bezirkSlug: this.bezirkContext.currentSlug(),
        addPickupExtraSlotRequest: { storeId: store.storeId, pickupDate: pickup.pickupDate },
      })
      .subscribe({
        next: (response) => {
          this.savingKey.set(null);
          this.apply(response);
          this.messageService.add({ severity: 'success', summary: this.i18n.t('pickups.addSlotSuccess') });
        },
        error: (error) => {
          this.savingKey.set(null);
          this.messageService.add({ severity: 'error', summary: this.i18n.t('common.error'), detail: resolveApiError(error, this.i18n) });
        },
      });
  }

  private apply(response: MyPickupListResponse): void {
    this.stores.set(response.stores);
    this.loading.set(false);
  }
}
