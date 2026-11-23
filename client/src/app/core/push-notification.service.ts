import { Injectable, Injector, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { App } from '@capacitor/app';
import { FirebaseMessaging, Importance, Visibility } from '@capacitor-firebase/messaging';
import { MessageService } from 'primeng/api';
import { firstValueFrom, timeout } from 'rxjs';

import { PushPlatform, UserService } from '../api';
import { isNativeApp, nativePlatform } from './native-app';
import { SessionService } from './session.service';

/** 'on' / 'off' as chosen on the profile page. Missing: not decided yet, ask once after sign-in. */
const PREFERENCE_KEY = 'foodsharing.pushNotifications';
/** Android notification channel; the server sends with this channel id. */
const ANDROID_CHANNEL_ID = 'einab';

export type PushState = 'unavailable' | 'on' | 'off' | 'denied';

/**
 * Native push notifications in the store app. Every message the platform sends to the user (Foodsharing message or
 * email) also arrives as a push notification on the phones where it is turned on. Tapping a notification, or an
 * einab.ch link opened from another app (e.g. the sign-in link), opens that page in the app.
 *
 * In the browser this service does nothing.
 */
@Injectable({ providedIn: 'root' })
export class PushNotificationService {
  private readonly injector = inject(Injector);
  private readonly router = inject(Router);
  private readonly userApi = inject(UserService);
  private readonly sessionService = inject(SessionService);
  private readonly messageService = inject(MessageService);

  readonly available = isNativeApp();
  readonly state = signal<PushState>(this.available ? 'off' : 'unavailable');
  readonly busy = signal(false);
  /** Token registered on the server for the signed-in user, so it can be removed again. */
  private registeredToken: string | null = null;

  start(): void {
    if (!this.available) {
      return;
    }
    void App.addListener('appUrlOpen', ({ url }) => this.open(url));
    void App.getLaunchUrl().then((launch) => launch?.url && this.open(launch.url));
    void FirebaseMessaging.addListener('notificationActionPerformed', ({ notification }) =>
      this.open((notification.data as { url?: string } | undefined)?.url));
    void FirebaseMessaging.addListener('tokenReceived', ({ token }) => {
      if (this.state() === 'on') {
        this.register(token);
      }
    });
    // iOS shows notifications in the foreground itself; Android does not, so show them as a toast.
    if (nativePlatform() === 'android') {
      void FirebaseMessaging.addListener('notificationReceived', ({ notification }) =>
        this.messageService.add({ severity: 'info', summary: notification.title, detail: notification.body, life: 8000 }));
    }
    // Register after sign-in (and on every app start while signed in). Session refreshes do not trigger it again.
    let syncedFor: string | null = null;
    effect(() => {
      const foodsharingId = this.sessionService.isAuthenticated() ? this.sessionService.foodsharingId() : null;
      if (foodsharingId === syncedFor) {
        return;
      }
      syncedFor = foodsharingId;
      if (foodsharingId) {
        void this.sync();
      } else {
        this.registeredToken = null;
      }
    }, { injector: this.injector });
  }

  /** Turned on on the profile page: asks the phone for permission if needed. */
  async enable(): Promise<void> {
    this.writePreference('on');
    await this.withBusy(() => this.sync(true));
  }

  /** Turned off on the profile page: this phone gets no more push notifications. */
  async disable(): Promise<void> {
    this.writePreference('off');
    await this.withBusy(() => this.unregister());
    this.state.set('off');
  }

  /** Before signing out: the next person using this phone must not get this user's messages. */
  async unregisterBeforeLogout(): Promise<void> {
    if (this.available) {
      await this.unregister();
    }
  }

  private async sync(askIfNeeded = false): Promise<void> {
    const preference = this.readPreference();
    if (preference === 'off') {
      this.state.set('off');
      return;
    }
    try {
      let permission = (await FirebaseMessaging.checkPermissions()).receive;
      // Ask once right after the first sign-in in the app, later only when turned on on the profile page.
      if (permission !== 'granted' && permission !== 'denied' && (askIfNeeded || preference === null)) {
        permission = (await FirebaseMessaging.requestPermissions()).receive;
      }
      if (permission === 'denied' && (askIfNeeded || preference === null)) {
        this.writePreference('off');
      }
      if (permission !== 'granted') {
        this.state.set(permission === 'denied' ? 'denied' : 'off');
        return;
      }
      this.writePreference('on');
      if (nativePlatform() === 'android') {
        await FirebaseMessaging.createChannel({
          id: ANDROID_CHANNEL_ID,
          name: 'EinAb',
          description: 'Nachrichten von einab.ch',
          importance: Importance.High,
          visibility: Visibility.Private
        });
      }
      const { token } = await FirebaseMessaging.getToken();
      this.register(token);
      this.state.set('on');
    } catch (error) {
      console.warn('Push notifications unavailable', error);
      this.state.set('off');
    }
  }

  private register(token: string): void {
    if (!token || !this.sessionService.isAuthenticated()) {
      return;
    }
    const platform = nativePlatform() === 'ios' ? PushPlatform.Ios : PushPlatform.Android;
    this.userApi.registerMyPushDevice({ pushDeviceRequest: { token, platform } }).subscribe({
      next: () => (this.registeredToken = token),
      error: (error) => console.warn('Could not register for push notifications', error)
    });
  }

  private async unregister(): Promise<void> {
    const token = this.registeredToken ?? (await FirebaseMessaging.getToken().then((result) => result.token).catch(() => null));
    this.registeredToken = null;
    if (!token || !this.sessionService.isAuthenticated()) {
      return;
    }
    try {
      await firstValueFrom(this.userApi.unregisterMyPushDevice({ pushDeviceUnregisterRequest: { token } }).pipe(timeout(5000)));
    } catch (error) {
      console.warn('Could not unregister from push notifications', error);
    }
  }

  /** Opens an einab.ch page (absolute link or app path) inside the app; other links are ignored. */
  private open(url: string | undefined | null): void {
    if (!url) {
      return;
    }
    let path: string;
    try {
      const parsed = new URL(url, window.location.origin);
      if (parsed.origin !== window.location.origin) {
        return;
      }
      path = parsed.pathname + parsed.search + parsed.hash;
    } catch {
      return;
    }
    if (path !== '/' && path !== this.router.url) {
      void this.router.navigateByUrl(path);
    }
  }

  private async withBusy(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    try {
      await action();
    } finally {
      this.busy.set(false);
    }
  }

  private readPreference(): 'on' | 'off' | null {
    try {
      const value = localStorage.getItem(PREFERENCE_KEY);
      return value === 'on' || value === 'off' ? value : null;
    } catch {
      return null;
    }
  }

  private writePreference(value: 'on' | 'off'): void {
    try {
      localStorage.setItem(PREFERENCE_KEY, value);
    } catch {
      // Private mode or storage blocked: the choice applies until the app restarts.
    }
  }
}
