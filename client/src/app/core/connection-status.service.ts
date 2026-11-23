import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';

export type ConnectionState = 'online' | 'offline' | 'server-unreachable';

/**
 * Tracks whether the app can reach the backend: the browser's own online/offline
 * events plus what the connection interceptor sees (no answer, or a 502/503/504 from
 * the proxy while the server restarts). The app shell shows a banner while it is not online.
 */
@Injectable({ providedIn: 'root' })
export class ConnectionStatusService {
  private readonly browserOnline = signal(typeof navigator === 'undefined' ? true : navigator.onLine);
  private readonly serverReachable = signal(true);
  /** Set when the connection came back, so the shell can show a short "back online" note. */
  readonly recovered = signal(false);

  readonly state = computed<ConnectionState>(() => {
    if (!this.browserOnline()) {
      return 'offline';
    }
    return this.serverReachable() ? 'online' : 'server-unreachable';
  });
  readonly isOnline = computed(() => this.state() === 'online');

  private recoveredTimer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    if (typeof window === 'undefined') {
      return;
    }
    const onOnline = () => this.setBrowserOnline(true);
    const onOffline = () => this.setBrowserOnline(false);
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    inject(DestroyRef).onDestroy(() => {
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
    });
  }

  /** A request got an answer from the server. */
  markServerReachable(): void {
    if (!this.serverReachable()) {
      this.serverReachable.set(true);
      this.flagRecovered();
    }
  }

  /** A request got no answer (or the proxy said the server is down). */
  markServerUnreachable(): void {
    // While the browser itself is offline, the offline banner already says enough.
    if (this.browserOnline()) {
      this.serverReachable.set(false);
    }
  }

  private setBrowserOnline(online: boolean): void {
    const wasOffline = !this.browserOnline();
    this.browserOnline.set(online);
    if (online && wasOffline) {
      // Assume the server is fine again until a request says otherwise.
      this.serverReachable.set(true);
      this.flagRecovered();
    }
  }

  private flagRecovered(): void {
    this.recovered.set(true);
    clearTimeout(this.recoveredTimer);
    this.recoveredTimer = setTimeout(() => this.recovered.set(false), 4000);
  }
}
