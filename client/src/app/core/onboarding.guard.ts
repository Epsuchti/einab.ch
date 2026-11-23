import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router, RouterStateSnapshot } from '@angular/router';

import { SessionService } from './session.service';

/**
 * Pages a signed-in user may open while the onboarding (privacy consent, Bezirk info step) is still open: the
 * onboarding page itself, signing in again, filling in a survey (asked before the consent on purpose), and
 * unsubscribing from a link. The privacy policy and account deletion live outside /bezirke and stay open too.
 */
const OPEN_DURING_ONBOARDING = new Set(['privacy-consent', 'login', 'verify-login', 'survey/:surveyId', 'unsubscribe']);

/**
 * Until the onboarding is done, every other page leads back to it: the menus are hidden too, but a typed URL, a
 * bookmark or the browser history must not get around it either. The server refuses bookings in that state as well.
 */
export const onboardingGuard: CanActivateFn = (route: ActivatedRouteSnapshot, state: RouterStateSnapshot) => {
  const sessionService = inject(SessionService);
  if (!sessionService.onboardingRequired() || OPEN_DURING_ONBOARDING.has(route.routeConfig?.path ?? '')) {
    return true;
  }
  const slug = sessionService.homeBezirkSlug() ?? findBezirkSlug(route);
  if (!slug) {
    return true;
  }
  return inject(Router).createUrlTree(['/bezirke', slug, 'privacy-consent'], { queryParams: { returnUrl: state.url } });
};

function findBezirkSlug(route: ActivatedRouteSnapshot | null): string | null {
  for (let current = route; current; current = current.parent) {
    const slug = current.paramMap.get('bezirkSlug');
    if (slug) {
      return slug;
    }
  }
  return null;
}
