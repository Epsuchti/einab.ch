import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';
import { ActivatedRouteSnapshot, Router } from '@angular/router';
import { Observable, map, tap } from 'rxjs';

import { AuthResponse, AuthService, DemoAccountRequestRoleEnum, LoginRequest, MessageResponse, PrivacyConsentRequest, PublicService, UserPermission, VerifyTokenRequest } from '../api';
import { SessionService } from './session.service';

/** Tab switches within this time do not ask the server again. */
const REFRESH_THROTTLE_MS = 60_000;

@Injectable({ providedIn: 'root' })
export class AuthFacadeService {
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);
  private lastRefresh = 0;
  private refreshing = false;

  constructor(
    private readonly authApi: AuthService,
    private readonly publicApi: PublicService,
    private readonly sessionService: SessionService
  ) {}

  requestLogin(bezirkSlug: string, foodsharingId: string, returnUrl?: string | null): Observable<MessageResponse> {
    const loginRequest: LoginRequest = returnUrl ? { foodsharingId, returnUrl } : { foodsharingId };
    return this.authApi.requestLogin({ bezirkSlug, loginRequest });
  }

  verifyToken(token: string): Observable<AuthResponse> {
    const verifyTokenRequest: VerifyTokenRequest = { token };
    return this.authApi.verifyLoginToken({ verifyTokenRequest }).pipe(
      tap((response) => this.sessionService.setSession(response))
    );
  }

  /** The shared tester Foodsharing ID if this server hands out demo accounts (test server only), otherwise null. */
  demoSharedFoodsharingId(): Observable<string | null> {
    return this.publicApi.getDemoLoginStatus().pipe(
      map((status) => (status.enabled && status.sharedFoodsharingId ? status.sharedFoodsharingId : null))
    );
  }

  /** Test server only: creates a fresh demo account for this tester and signs it in. */
  createDemoAccount(sharedFoodsharingId: string, role: DemoAccountRequestRoleEnum): Observable<AuthResponse> {
    return this.authApi.createDemoAccount({ demoAccountRequest: { foodsharingId: sharedFoodsharingId, role } }).pipe(
      tap((response) => this.sessionService.setSession(response))
    );
  }

  acceptPrivacyConsent(version: string): Observable<AuthResponse> {
    const privacyConsentRequest: PrivacyConsentRequest = { version };
    return this.authApi.acceptPrivacyConsent({ privacyConsentRequest }).pipe(
      tap((response) => this.sessionService.setSession(response))
    );
  }

  /**
   * Permissions are stored with the session at login. Refresh them from the server when the app starts and when the
   * tab becomes visible again, so a change by an admin applies without logging out and in again.
   */
  startSessionRefresh(): void {
    this.refreshSession(true);
    this.document.addEventListener('visibilitychange', () => {
      if (this.document.visibilityState === 'visible') {
        this.refreshSession();
      }
    });
  }

  /** Loads the current permissions; force skips the throttle (e.g. after the server answered 403). */
  refreshSession(force = false): void {
    if (!this.sessionService.isAuthenticated() || this.refreshing
        || (!force && Date.now() - this.lastRefresh < REFRESH_THROTTLE_MS)) {
      return;
    }
    this.refreshing = true;
    this.lastRefresh = Date.now();
    this.authApi.getCurrentSession().subscribe({
      next: (info) => {
        this.refreshing = false;
        if (this.sessionService.applySessionInfo(info)) {
          this.leavePageIfNoLongerAllowed();
        }
      },
      // A 401 is handled by the auth interceptor; anything else keeps the stored session.
      error: () => {
        this.refreshing = false;
      }
    });
  }

  private leavePageIfNoLongerAllowed(): void {
    let route: ActivatedRouteSnapshot | null = this.router.routerState.snapshot.root;
    while (route) {
      const required = route.data['permissions'] as UserPermission[] | undefined;
      if (required && !required.some((permission) => this.sessionService.hasPermission(permission))) {
        void this.router.navigateByUrl(this.sessionService.primaryRoute());
        return;
      }
      route = route.firstChild;
    }
  }
}
