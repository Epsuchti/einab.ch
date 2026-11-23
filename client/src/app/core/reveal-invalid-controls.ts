import { AbstractControl } from '@angular/forms';

/**
 * Called when a reactive form is submitted while invalid: marks every control touched and dirty, so the
 * PrimeNG fields show their red invalid state, then scrolls the first invalid field into view and focuses it.
 */
export function revealInvalidControls(form: AbstractControl, container: HTMLElement | null | undefined): void {
  form.markAllAsTouched();
  form.markAllAsDirty();
  scrollToFirstInvalidField(container, '[formcontrolname].ng-invalid');
}

/**
 * Scrolls the first element in `container` that matches `selector` into view and focuses its input.
 * Runs after change detection, so invalid classes or attributes set in the same tick are already applied.
 */
export function scrollToFirstInvalidField(container: HTMLElement | null | undefined, selector: string): void {
  if (!container) {
    return;
  }
  setTimeout(() => {
    const invalidField = container.querySelector<HTMLElement>(selector);
    if (!invalidField) {
      return;
    }
    invalidField.scrollIntoView({ behavior: 'smooth', block: 'center' });
    // Focusing the date picker's input would pop its calendar open, so it is only scrolled to.
    if (invalidField.matches('p-datepicker') || invalidField.querySelector('p-datepicker')) {
      return;
    }
    const focusTarget = invalidField.matches('input, textarea, select')
      ? invalidField
      : invalidField.querySelector<HTMLElement>('input, textarea, select, [tabindex]:not([tabindex="-1"])');
    focusTarget?.focus({ preventScroll: true });
  });
}
