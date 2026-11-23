import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { SessionService } from './session.service';

/** Logged-in users connected to a Bezirk never see the Bezirk picker. */
export const landingGuard: CanActivateFn = () => {
  const slug = inject(SessionService).homeBezirkSlug();
  return slug ? inject(Router).createUrlTree(['/bezirke', slug]) : true;
};
