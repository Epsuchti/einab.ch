import { Injectable, computed, signal } from '@angular/core';
import { Router } from '@angular/router';

import { AuthResponse, SessionInfoResponse, UserPermission } from '../api';
import { BezirkContextService } from './bezirk-context.service';
import { safeReturnUrl } from './return-url';

type SessionState = AuthResponse | null;

@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly storageKey = 'foodsharing.session';
  readonly session = signal<SessionState>(this.loadSession());
  readonly isAuthenticated = computed(() => {
    const session = this.session();
    return Boolean(session && new Date(session.expiresAt).getTime() > Date.now());
  });
  /**
   * Signed in, but the privacy consent or the Bezirk info step is still open: the app shows only the onboarding page
   * (and surveys) until both are done.
   */
  readonly onboardingRequired = computed(() => {
    const session = this.session();
    return this.isAuthenticated() && (session?.privacyConsentRequired === true || session?.bezirkInfoRequired === true);
  });

  constructor(
    private readonly router: Router,
    private readonly bezirkContext: BezirkContextService
  ) {}

  setSession(session: AuthResponse): void {
    this.session.set(session);
    localStorage.setItem(this.storageKey, JSON.stringify(session));
  }

  markPhoneNumberProvided(): void {
    const session = this.session();
    if (session) {
      this.setSession({ ...session, phoneNumberRequired: false });
    }
  }

  /** Takes over the current permissions and account flags from the server. Returns true when the permissions changed. */
  applySessionInfo(info: SessionInfoResponse): boolean {
    const session = this.session();
    if (!session || session.foodsharingId.toLowerCase() !== info.foodsharingId.toLowerCase()) {
      return false;
    }
    const permissionsChanged = [...session.permissions].sort().join(',') !== [...info.permissions].sort().join(',');
    this.setSession({
      ...session,
      displayName: info.displayName ?? session.displayName,
      bezirk: info.bezirk ?? session.bezirk,
      permissions: info.permissions,
      privacyConsentRequired: info.privacyConsentRequired,
      phoneNumberRequired: info.phoneNumberRequired,
      bezirkInfoRequired: info.bezirkInfoRequired ?? false
    });
    return permissionsChanged;
  }

  clearSession(): void {
    this.session.set(null);
    localStorage.removeItem(this.storageKey);
    void this.router.navigate(this.bezirkContext.route());
  }

  /** The server rejected the token (revoked/deactivated): sign out and go to the login page, remembering the current page. */
  handleExpiredSession(): void {
    const returnUrl = safeReturnUrl(this.router.url);
    this.session.set(null);
    localStorage.removeItem(this.storageKey);
    void this.router.navigate(this.bezirkContext.route('login'), returnUrl ? { queryParams: { returnUrl } } : {});
  }

  token(): string | null {
    return this.isAuthenticated() ? this.session()?.authToken ?? null : null;
  }


  permissions(): UserPermission[] {
    return this.session()?.permissions ?? [];
  }

  hasPermission(permission: UserPermission): boolean {
    return this.permissions().includes(permission);
  }

  primaryRoute(): string {
    const bezirkSlug = this.bezirkContext.selectedBezirk()?.slug ?? this.session()?.bezirk?.slug;
    const prefix = bezirkSlug ? `/bezirke/${bezirkSlug}` : '';
    if (this.hasPermission(UserPermission.CanManageUsers)) {
      return `${prefix}/admin`;
    }
    if (this.hasPermission(UserPermission.CanUseAutomations) || this.hasPermission(UserPermission.CanSeeAllAutomationDecisions) || this.hasPermission(UserPermission.CanSeeUserPickupCountGrouping)) {
      return `${prefix}/admin/foodsharing-automation`;
    }
    if (this.hasPermission(UserPermission.CanGiveEinAbs)) {
      return `${prefix}/teacher`;
    }
    if (this.hasPermission(UserPermission.CanManageSurveys)) {
      return `${prefix}/admin/surveys`;
    }
    if (this.isAuthenticated()) {
      return `${prefix}/my-bookings`;
    }
    return prefix || '/';
  }

  /** Slug of the Bezirk the logged-in user belongs to, or null when logged out / not connected. */
  homeBezirkSlug(): string | null {
    return this.isAuthenticated() ? this.session()?.bezirk?.slug ?? null : null;
  }

  foodsharingId(): string | null {
    return this.session()?.foodsharingId ?? null;
  }

  private loadSession(): SessionState {
    const raw = localStorage.getItem(this.storageKey);
    if (!raw) {
      return null;
    }
    try {
      return JSON.parse(raw) as AuthResponse;
    } catch {
      localStorage.removeItem(this.storageKey);
      return null;
    }
  }
}
