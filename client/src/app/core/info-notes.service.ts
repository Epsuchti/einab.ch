import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';

import { InfoNoteResponse, InfoNoteService } from '../api';
import { BezirkContextService } from './bezirk-context.service';

const DISMISSED_KEY = 'foodsharing.dismissedInfoNotes';
/** Only the latest dismissals are kept; older notes have long ended by then. */
const MAX_DISMISSED = 100;

/**
 * Info notes from the admins shown as banners below the header: notes for the whole site everywhere, and the
 * notes of the selected Bezirk. Loaded when the page opens and whenever another Bezirk is selected. A dismissed
 * note stays hidden in this browser only (localStorage).
 */
@Injectable({ providedIn: 'root' })
export class InfoNotesService {
  private readonly infoNoteApi = inject(InfoNoteService);
  private readonly bezirkContext = inject(BezirkContextService);

  private readonly notes = signal<InfoNoteResponse[]>([]);
  private readonly dismissed = signal<string[]>(readDismissed());
  private request?: Subscription;
  /** Slug the shown notes belong to; undefined before the first load. */
  private loadedFor?: string | null;

  readonly visible = computed(() => {
    const dismissed = new Set(this.dismissed());
    return this.notes().filter((note) => !dismissed.has(note.id));
  });

  constructor() {
    effect(() => {
      const slug = this.bezirkContext.selectedBezirk()?.slug ?? null;
      if (slug !== this.loadedFor) {
        this.load(slug);
      }
    });
    inject(DestroyRef).onDestroy(() => this.request?.unsubscribe());
  }

  /** Fetches the notes again, e.g. after an admin created or changed one. */
  reload(): void {
    this.load(this.bezirkContext.selectedBezirk()?.slug ?? null);
  }

  dismiss(id: string): void {
    const next = [...this.dismissed().filter((entry) => entry !== id), id].slice(-MAX_DISMISSED);
    this.dismissed.set(next);
    try {
      localStorage.setItem(DISMISSED_KEY, JSON.stringify(next));
    } catch {
      // Storage can be unavailable (private mode); the note then stays hidden until the page is reloaded.
    }
  }

  private load(slug: string | null): void {
    this.loadedFor = slug;
    this.request?.unsubscribe();
    this.request = this.infoNoteApi.getActiveInfoNotes({ bezirkSlug: slug ?? undefined }).subscribe({
      next: (response) => this.notes.set(response.notes),
      // A note is never essential for using the page, so a failed load just shows none.
      error: () => this.notes.set([])
    });
  }
}

function readDismissed(): string[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(DISMISSED_KEY) ?? '[]');
    return Array.isArray(parsed) ? parsed.filter((entry): entry is string => typeof entry === 'string') : [];
  } catch {
    return [];
  }
}
