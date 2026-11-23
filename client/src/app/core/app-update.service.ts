import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';
import { NavigationStart, Router } from '@angular/router';
import { SwUpdate } from '@angular/service-worker';
import { filter } from 'rxjs';

import { isNativeApp } from './native-app';

/** Look for a new release at most this often (when the tab or app comes back to the foreground). */
const CHECK_THROTTLE_MS = 5 * 60_000;
/** And regularly while the page stays open. */
const CHECK_INTERVAL_MS = 30 * 60_000;

/**
 * Keeps open pages on the newest release, so phone users never need a store update for web changes.
 *
 * - Browser: the Angular service worker caches the app and reports a new deployment (VERSION_READY).
 * - Store app: the page is loaded from einab.ch on every start and has no service worker; while it stays open it
 *   compares the server's release number (/actuator/info) with the one it started with.
 *
 * A new release is picked up on the next page change, by loading that page fresh, so nobody loses what they typed.
 */
@Injectable({ providedIn: 'root' })
export class AppUpdateService {
  private readonly swUpdate = inject(SwUpdate);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);
  private updateReady = false;
  private lastCheck = 0;
  private startVersion: string | null = null;

  start(): void {
    if (this.swUpdate.isEnabled) {
      this.swUpdate.versionUpdates
        .pipe(filter((event) => event.type === 'VERSION_READY'))
        .subscribe(() => (this.updateReady = true));
      // The cached app is broken (e.g. files of the old release are gone from the server): load it fresh.
      this.swUpdate.unrecoverable.subscribe(() => this.document.location.reload());
    } else if (isNativeApp()) {
      void this.serverVersion().then((version) => (this.startVersion = version));
    } else {
      return;
    }

    this.document.addEventListener('visibilitychange', () => {
      if (this.document.visibilityState === 'visible') {
        this.check();
      }
    });
    setInterval(() => this.check(true), CHECK_INTERVAL_MS);

    this.router.events
      .pipe(filter((event): event is NavigationStart => event instanceof NavigationStart))
      .subscribe((event) => {
        if (this.updateReady) {
          this.updateReady = false;
          this.document.location.assign(event.url);
        }
      });
  }

  private check(force = false): void {
    if (this.updateReady || (!force && Date.now() - this.lastCheck < CHECK_THROTTLE_MS)) {
      return;
    }
    this.lastCheck = Date.now();
    if (this.swUpdate.isEnabled) {
      this.swUpdate.checkForUpdate().catch(() => undefined);
      return;
    }
    void this.serverVersion().then((version) => {
      if (!this.startVersion) {
        this.startVersion = version;
      } else if (version && version !== this.startVersion) {
        this.updateReady = true;
      }
    });
  }

  /** Release number of the running server (not an application API call; the actuator is outside the OpenAPI spec). */
  private async serverVersion(): Promise<string | null> {
    try {
      const response = await fetch('/actuator/info', { cache: 'no-store' });
      if (!response.ok) {
        return null;
      }
      const info = (await response.json()) as { app?: { version?: unknown } };
      return info.app?.version != null ? String(info.app.version) : null;
    } catch {
      return null;
    }
  }
}
