import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { tap } from 'rxjs';

import { ConnectionStatusService } from './connection-status.service';

/** Status codes a reverse proxy answers with while the backend is down or restarting. */
const SERVER_DOWN_STATUSES = new Set([502, 503, 504]);

export function isConnectionError(error: unknown): boolean {
  if (!(error instanceof HttpErrorResponse)) {
    return false;
  }
  if (error.status === 0) {
    return true;
  }
  // Our own server answers with an error code (e.g. when Foodsharing is down); only a bare 502/503/504 means our server is gone.
  const code = typeof error.error === 'object' && error.error ? (error.error as { code?: unknown }).code : undefined;
  return SERVER_DOWN_STATUSES.has(error.status) && !code;
}

/** Feeds the connection status from every request: any answer means the server is reachable. */
export const connectionInterceptor: HttpInterceptorFn = (request, next) => {
  const connection = inject(ConnectionStatusService);
  return next(request).pipe(
    tap({
      next: (event) => {
        if (event instanceof HttpResponse) {
          connection.markServerReachable();
        }
      },
      error: (error: unknown) => {
        if (isConnectionError(error)) {
          connection.markServerUnreachable();
        } else if (error instanceof HttpErrorResponse) {
          connection.markServerReachable();
        }
      }
    })
  );
};
