import { computed, signal } from '@angular/core';
import { defer, finalize, MonoTypeOperatorFunction } from 'rxjs';

/**
 * Counts running requests so a view with several parallel loads can show one spinner until all have finished.
 * The count is released on completion, error and unsubscribe, so the spinner never hangs.
 */
export class LoadingTracker {
  private readonly pending = signal(0);
  readonly active = computed(() => this.pending() > 0);

  track<T>(): MonoTypeOperatorFunction<T> {
    return (source) => defer(() => {
      this.pending.update((count) => count + 1);
      return source.pipe(finalize(() => this.pending.update((count) => count - 1)));
    });
  }
}
