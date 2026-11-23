import { Injectable, effect, inject, signal } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

import { SupportRequestStatus, SupportService, UserPermission } from '../api';
import { SessionService } from './session.service';

/** Look again at most this often while admins move around the app. */
const REFRESH_MS = 60_000;

/** Number of open support requests, shown as a small badge on "Admin" and on its "Support" tab. */
@Injectable({ providedIn: 'root' })
export class SupportBadgeService {
  private readonly supportApi = inject(SupportService);
  private readonly session = inject(SessionService);
  readonly openCount = signal(0);
  private lastRefresh = 0;

  constructor() {
    inject(Router).events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe(() => this.refresh());
    // Signing in or out (or getting the permission) updates the badge right away.
    effect(() => {
      this.session.session();
      this.refresh(true);
    });
  }

  refresh(force = false): void {
    if (!this.session.isAuthenticated() || !this.session.hasPermission(UserPermission.CanManageUsers)) {
      this.openCount.set(0);
      return;
    }
    if (!force && Date.now() - this.lastRefresh < REFRESH_MS) {
      return;
    }
    this.lastRefresh = Date.now();
    this.supportApi.listSupportRequests({ status: SupportRequestStatus.Open, page: 0, size: 1 }).subscribe({
      next: (response) => this.openCount.set(response.openCount),
      error: () => { /* the badge just stays as it was */ }
    });
  }

  /** The support tab already knows the exact number after loading or changing a request. */
  set(count: number): void {
    this.openCount.set(Math.max(0, count));
    this.lastRefresh = Date.now();
  }
}
