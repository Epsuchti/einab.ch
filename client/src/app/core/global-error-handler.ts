import { HttpErrorResponse } from '@angular/common/http';
import { ErrorHandler, Injectable, Injector, NgZone, inject } from '@angular/core';

import { ConnectionStatusService } from './connection-status.service';
import { ErrorNotifierService } from './error-notifier.service';
import { PageErrorService } from './page-error.service';

/**
 * Catches every error no page handled. Backend errors become a translated toast;
 * a lazy page that cannot be loaded while offline shows the offline banner instead.
 * Everything is still logged to the console for debugging.
 */
@Injectable()
export class GlobalErrorHandler implements ErrorHandler {
  // Resolved on first use: the error handler is created very early, before HTTP and i18n are ready.
  private readonly injector = inject(Injector);
  private readonly zone = inject(NgZone);

  handleError(error: unknown): void {
    console.error(error);
    try {
      this.report(unwrap(error));
    } catch (reportError) {
      // The error handler must never throw itself.
      console.error(reportError);
    }
  }

  private report(cause: unknown): void {
    if (cause instanceof HttpErrorResponse) {
      // Already explained by the full-page error.
      if (this.injector.get(PageErrorService).isShown(cause)) {
        return;
      }
      this.zone.run(() => this.injector.get(ErrorNotifierService).notify(cause));
      return;
    }
    if (isChunkLoadError(cause)) {
      this.zone.run(() => this.injector.get(ConnectionStatusService).markServerUnreachable());
    }
  }
}

/** Promise rejections and wrapped errors carry the real error one level down. */
function unwrap(error: unknown): unknown {
  let current = error;
  for (let depth = 0; depth < 3 && current && typeof current === 'object'; depth++) {
    const inner = (current as { rejection?: unknown; ngOriginalError?: unknown }).rejection
      ?? (current as { ngOriginalError?: unknown }).ngOriginalError;
    if (!inner) {
      break;
    }
    current = inner;
  }
  return current;
}

function isChunkLoadError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : '';
  return /Failed to fetch dynamically imported module|Loading chunk [\w-]+ failed|Importing a module script failed/i.test(message);
}
