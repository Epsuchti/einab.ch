import { HttpErrorResponse, HttpEvent, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, Observable, throwError } from 'rxjs';

import { AuthFacadeService } from './auth-facade.service';
import { SessionService } from './session.service';

export const authInterceptor: HttpInterceptorFn = (request, next): Observable<HttpEvent<unknown>> => {
  const sessionService = inject(SessionService);
  const authFacade = inject(AuthFacadeService);
  const token = sessionService.token();
  if (!token) {
    return next(request).pipe(handleUnauthorized(sessionService, authFacade)) as Observable<HttpEvent<unknown>>;
  }
  return next(request.clone({
    setHeaders: {
      Authorization: `Bearer ${token}`
    }
  })).pipe(handleUnauthorized(sessionService, authFacade)) as Observable<HttpEvent<unknown>>;
};

function handleUnauthorized(sessionService: SessionService, authFacade: AuthFacadeService) {
  return catchError((error: unknown): Observable<HttpEvent<unknown>> => {
    if (error instanceof HttpErrorResponse && error.status === 401 && sessionService.isAuthenticated()) {
      sessionService.handleExpiredSession();
    }
    // A permission may have been taken away since login: reload the permissions so the menu and pages follow.
    if (error instanceof HttpErrorResponse && error.status === 403 && sessionService.isAuthenticated()) {
      authFacade.refreshSession(true);
    }
    return throwError(() => error);
  });
}
