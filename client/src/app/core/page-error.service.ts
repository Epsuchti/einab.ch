import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { NavigationEnd, NavigationStart, Router } from '@angular/router';

export interface PageError {
  /** The URL the user tried to open, so "try again" goes back there. */
  url: string;
  error: unknown;
}

/**
 * Holds the error of a page that could not be opened (a route guard or page load failed).
 * While set, the app shell shows a full-page error instead of an empty page.
 */
@Injectable({ providedIn: 'root' })
export class PageErrorService {
  private readonly router = inject(Router);
  readonly current = signal<PageError | null>(null);

  constructor() {
    this.router.events.subscribe((event) => {
      // A new successful navigation replaces the error page.
      if (event instanceof NavigationEnd) {
        this.current.set(null);
      }
      if (event instanceof NavigationStart && this.current()?.url !== event.url) {
        this.current.set(null);
      }
    });
  }

  show(url: string, error: unknown): void {
    this.current.set({ url, error });
  }

  /** True when the error is already shown as the error page, so no toast is needed on top. */
  isShown(error: unknown): boolean {
    const current = this.current()?.error;
    return current !== undefined && (current === error || (error instanceof HttpErrorResponse && current instanceof HttpErrorResponse && current.url === error.url && current.status === error.status));
  }

  retry(): void {
    const url = this.current()?.url ?? this.router.url;
    void this.router.navigateByUrl(url, { onSameUrlNavigation: 'reload' });
  }
}
