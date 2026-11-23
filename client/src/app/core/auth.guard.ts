import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { UserPermission } from '../api';
import { BezirkContextService } from './bezirk-context.service';
import { SessionService } from './session.service';

export const authGuard: CanActivateFn = (route, state) => {
  const sessionService = inject(SessionService);
  const bezirkContext = inject(BezirkContextService);
  const router = inject(Router);
  if (!sessionService.isAuthenticated()) {
    // Remember the requested page; the login link sent via Foodsharing brings the user back to it.
    return router.createUrlTree(bezirkContext.route('login'), { queryParams: { returnUrl: state.url } });
  }
  // The open onboarding (privacy consent, Bezirk info step) is handled by onboardingGuard on every page.

  const requiredPermissions = route.data['permissions'] as UserPermission[] | undefined;
  if (!requiredPermissions || requiredPermissions.some((permission) => sessionService.hasPermission(permission))) {
    return true;
  }
  return router.createUrlTree([sessionService.primaryRoute()]);
};
