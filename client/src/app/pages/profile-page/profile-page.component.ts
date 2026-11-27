import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { InputTextModule } from 'primeng/inputtext';
import { ConfirmationService, MessageService } from 'primeng/api';

import { AccountService, NotificationStatusResponse, UserService } from '../../api';
import { confirmAction } from '../../core/confirm-action';
import { BezirkContextService } from '../../core/bezirk-context.service';
import { resolveApiError } from '../../core/api-error';
import { phoneNumberValidator } from '../../core/phone-number';
import { I18nService } from '../../core/i18n.service';
import { PushNotificationService } from '../../core/push-notification.service';
import { SessionService } from '../../core/session.service';
import { SkeletonComponent, SkeletonRegionDirective } from '../../core/skeleton';

@Component({
  selector: 'app-profile-page',
  standalone: true,
  imports: [SkeletonComponent, SkeletonRegionDirective, CommonModule, RouterLink, ReactiveFormsModule, ButtonModule, CardModule, InputTextModule],
  templateUrl: './profile-page.component.html',
  styleUrl: './profile-page.component.scss'
})
export class ProfilePageComponent implements OnInit {
  readonly i18n = inject(I18nService);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    phoneNumber: ['', [Validators.required, Validators.maxLength(50), phoneNumberValidator]]
  });

  private readonly userApi = inject(UserService);
  private readonly messageService = inject(MessageService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly accountApi = inject(AccountService);
  protected readonly deleting = signal(false);
  protected readonly sessionService = inject(SessionService);
  protected readonly push = inject(PushNotificationService);
  protected readonly bezirkContext = inject(BezirkContextService);
  protected readonly languages = ['gws', 'de', 'en'] as const;
  protected readonly notificationStatus = signal<NotificationStatusResponse | null>(null);
  protected readonly notificationLoading = signal(false);

  ngOnInit(): void {
    this.loadNotificationStatus();
    this.userApi.getMyProfile().subscribe({
      next: (profile) => {
        this.form.patchValue({ phoneNumber: profile.phoneNumber ?? '' });
        this.form.markAsPristine();
        this.loading.set(false);
      },
      error: (error) => {
        this.loading.set(false);
        this.toastError(resolveApiError(error, this.i18n));
      }
    });
  }

  submit(): void {
    const phoneNumber = this.form.getRawValue().phoneNumber.trim();
    if (!phoneNumber || this.form.invalid || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.userApi.updateMyProfile({ updateMyProfileRequest: { phoneNumber } })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (profile) => {
          this.form.patchValue({ phoneNumber: profile.phoneNumber ?? phoneNumber });
          this.form.markAsPristine();
          this.sessionService.markPhoneNumberProvided();
          this.messageService.add({ severity: 'success', summary: this.i18n.t('profile.saveSuccess') });
        },
        error: (error) => this.toastError(resolveApiError(error, this.i18n))
      });
  }

  setLanguage(language: 'gws' | 'de' | 'en'): void {
    void this.i18n.setLanguage(language);
  }

  unsubscribe(): void {
    this.notificationLoading.set(true);
    this.userApi.unsubscribeMyNotifications()
      .pipe(finalize(() => this.notificationLoading.set(false)))
      .subscribe({
        next: () => {
          this.messageService.add({ severity: 'success', summary: this.i18n.t('subscribe.stopped') });
          this.loadNotificationStatus();
        },
        error: (error) => this.toastError(resolveApiError(error, this.i18n))
      });
  }

  async logout(): Promise<void> {
    await this.push.unregisterBeforeLogout();
    this.sessionService.clearSession();
  }

  confirmDeleteAccount(): void {
    confirmAction(this.confirmationService, this.i18n, {
      header: this.i18n.t('profile.deleteAccountConfirmTitle'),
      message: this.i18n.t('profile.deleteAccountConfirm'),
      acceptLabel: this.i18n.t('profile.deleteAccountButton'),
      destructive: true,
      accept: () => void this.deleteAccount()
    });
  }

  private async deleteAccount(): Promise<void> {
    this.deleting.set(true);
    // Remove this phone while still signed in; the server also forgets all phones of the account.
    await this.push.unregisterBeforeLogout();
    this.accountApi.deleteMyAccount()
      .pipe(finalize(() => this.deleting.set(false)))
      .subscribe({
        next: () => {
          this.messageService.add({ severity: 'success', summary: this.i18n.t('profile.deleteAccountDone') });
          this.sessionService.clearSession();
        },
        error: (error) => this.toastError(resolveApiError(error, this.i18n))
      });
  }

  private loadNotificationStatus(): void {
    this.userApi.getMyNotificationStatus().subscribe({
      next: (status) => this.notificationStatus.set(status),
      error: () => this.notificationStatus.set(null)
    });
  }

  private toastError(detail: string): void {
    this.messageService.add({ severity: 'error', summary: this.i18n.t('common.error'), detail });
  }
}
