import { Injectable, inject } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

/** Remembers the page before the current one, so a support request can say where the user came from. */
@Injectable({ providedIn: 'root' })
export class NavigationHistoryService {
  private current: string | null = null;
  private previous: string | null = null;

  constructor() {
    inject(Router).events
      .pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd))
      .subscribe((event) => {
        if (event.urlAfterRedirects !== this.current) {
          this.previous = this.current;
          this.current = event.urlAfterRedirects;
        }
      });
  }

  previousUrl(): string | null {
    return this.previous;
  }
}
