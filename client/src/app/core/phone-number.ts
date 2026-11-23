import { AbstractControl, ValidationErrors } from '@angular/forms';

const PHONE_CHARACTERS = /^\+?[0-9 ()/.-]+$/;
const MIN_DIGITS = 7;
const MAX_DIGITS = 15;

/** Browsers have no format check for type="tel", so this accepts an optional leading + followed by 7 to 15 digits and common separators. */
export function isValidPhoneNumber(value: string | null | undefined): boolean {
  const trimmed = (value ?? '').trim();
  if (!PHONE_CHARACTERS.test(trimmed)) {
    return false;
  }
  const digits = trimmed.replace(/\D/g, '').length;
  return digits >= MIN_DIGITS && digits <= MAX_DIGITS;
}

export function phoneNumberValidator(control: AbstractControl): ValidationErrors | null {
  const value = String(control.value ?? '');
  return value.trim() === '' || isValidPhoneNumber(value) ? null : { phoneNumber: true };
}
