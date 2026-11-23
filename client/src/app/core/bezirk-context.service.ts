import { Injectable, inject, signal } from '@angular/core';
import { Router, UrlTree } from '@angular/router';
import { Observable, map, of, tap } from 'rxjs';

import { BezirkResponse, EinAbCategory, PublicService } from '../api';

@Injectable({ providedIn: 'root' })
export class BezirkContextService {
  readonly bezirke = signal<BezirkResponse[]>([]);
  readonly selectedBezirk = signal<BezirkResponse | null>(null);

  private readonly publicApi = inject(PublicService);
  private readonly router = inject(Router);

  loadBezirke(): Observable<BezirkResponse[]> {
    const current = this.bezirke();
    if (current.length) {
      return of(current);
    }
    return this.publicApi.getBezirke().pipe(
      map((response) => response.bezirke),
      tap((bezirke) => this.bezirke.set(bezirke))
    );
  }

  /** Fetches the list again, e.g. after an admin created or disabled a Bezirk. */
  reloadBezirke(): Observable<BezirkResponse[]> {
    return this.publicApi.getBezirke().pipe(
      map((response) => response.bezirke),
      tap((bezirke) => this.bezirke.set(bezirke))
    );
  }

  selectBezirk(slug: string | null): Observable<true | UrlTree> {
    if (!slug) {
      this.selectedBezirk.set(null);
      return of(this.router.createUrlTree(['/']));
    }
    return this.loadBezirke().pipe(
      map((bezirke) => {
        const bezirk = bezirke.find((entry) => entry.slug === slug);
        if (!bezirk) {
          this.selectedBezirk.set(null);
          return this.router.createUrlTree(['/']);
        }
        this.selectedBezirk.set(bezirk);
        rememberBezirk(slug);
        return true as const;
      })
    );
  }

  /**
   * Categories offered for selection in the given Bezirk. `keep` stays in the list even when disabled, so an
   * existing EinAb in a since-disabled category can still be shown and saved unchanged.
   */
  enabledCategories(slug: string | null | undefined, keep?: EinAbCategory | null): EinAbCategory[] {
    const disabled = this.bezirke().find((entry) => entry.slug === slug)?.disabledCategories ?? [];
    return Object.values(EinAbCategory).filter((category) => category === keep || !disabled.includes(category));
  }

  setDisabledCategories(slug: string, disabledCategories: EinAbCategory[]): void {
    this.patchBezirk(slug, { disabledCategories });
  }

  /** After the Bezirk settings were saved: the menu and the help page offer the info page only when there is one. */
  setInfoAvailable(slug: string, infoAvailable: boolean): void {
    this.patchBezirk(slug, { infoAvailable });
  }

  private patchBezirk(slug: string, patch: Partial<BezirkResponse>): void {
    const update = (bezirk: BezirkResponse) => bezirk.slug === slug ? { ...bezirk, ...patch } : bezirk;
    this.bezirke.update((bezirke) => bezirke.map(update));
    const selected = this.selectedBezirk();
    if (selected) {
      this.selectedBezirk.set(update(selected));
    }
  }

  currentSlug(): string {
    const slug = this.selectedBezirk()?.slug;
    if (!slug) {
      throw new Error('No Bezirk is selected.');
    }
    return slug;
  }

  /** The Bezirk this browser opened last, so the landing page can offer it first. */
  lastBezirk(): BezirkResponse | null {
    let slug: string | null = null;
    try {
      slug = localStorage.getItem(LAST_BEZIRK_KEY);
    } catch {
      return null;
    }
    return this.bezirke().find((entry) => entry.slug === slug) ?? null;
  }

  route(...segments: string[]): string[] {
    const slug = this.selectedBezirk()?.slug;
    return slug ? ['/bezirke', slug, ...segments] : ['/'];
  }
}

const LAST_BEZIRK_KEY = 'foodsharing.lastBezirk';

function rememberBezirk(slug: string): void {
  try {
    localStorage.setItem(LAST_BEZIRK_KEY, slug);
  } catch {
    // Storage can be unavailable (private mode); the landing page then just shows the full list.
  }
}
