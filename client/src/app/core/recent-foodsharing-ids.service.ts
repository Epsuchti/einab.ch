import { Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'foodsharing.recentFoodsharingIds';
const MAX_ENTRIES = 10;

/**
 * Remembers the Foodsharing-IDs that were used to request a login link on this device,
 * most recent first. Stored only in this browser: nobody is logged in yet when the ID
 * is typed, so there is no server-side user to attach the history to.
 */
@Injectable({ providedIn: 'root' })
export class RecentFoodsharingIdsService {
  private readonly ids = signal<string[]>(this.load());

  readonly recentIds = this.ids.asReadonly();

  remember(foodsharingId: string): void {
    const id = foodsharingId.trim();
    if (!id) {
      return;
    }
    this.update([id, ...this.ids().filter((existing) => existing !== id)].slice(0, MAX_ENTRIES));
  }

  forget(foodsharingId: string): void {
    this.update(this.ids().filter((existing) => existing !== foodsharingId));
  }

  private update(ids: string[]): void {
    this.ids.set(ids);
    try {
      if (ids.length === 0) {
        localStorage.removeItem(STORAGE_KEY);
      } else {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(ids));
      }
    } catch {
      // Storage can be unavailable (private mode, blocked site data); suggestions then last for this page only.
    }
  }

  private load(): string[] {
    try {
      const parsed: unknown = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]');
      return Array.isArray(parsed)
        ? parsed.filter((id): id is string => typeof id === 'string' && id.trim() !== '').slice(0, MAX_ENTRIES)
        : [];
    } catch {
      return [];
    }
  }
}
