import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { MessageService } from 'primeng/api';

import { resolveApiError } from './api-error';
import { isConnectionError } from './connection.interceptor';
import { I18nService } from './i18n.service';

/** How long the same message is not repeated, so a page that loads several things does not stack toasts. */
const DUPLICATE_WINDOW_MS = 4000;

/**
 * Shows a translated error toast for a failed backend call. Pages use it for loads
 * that used to fail silently; the global error handler uses it for errors nobody caught.
 */
@Injectable({ providedIn: 'root' })
export class ErrorNotifierService {
  private readonly i18n = inject(I18nService);
  private readonly messageService = inject(MessageService);
  private readonly recent = new Map<string, number>();

  notify(error: unknown): void {
    // No connection: the banner in the app shell already tells the user.
    if (isConnectionError(error)) {
      return;
    }
    // An expired session sends the user to the login page; a toast on top would only confuse.
    if (error instanceof HttpErrorResponse && error.status === 401) {
      return;
    }
    this.show(resolveApiError(error, this.i18n));
  }

  private show(detail: string): void {
    const now = Date.now();
    const last = this.recent.get(detail);
    if (last !== undefined && now - last < DUPLICATE_WINDOW_MS) {
      return;
    }
    this.recent.set(detail, now);
    this.messageService.add({ severity: 'error', summary: this.i18n.t('common.error'), detail });
  }
}
