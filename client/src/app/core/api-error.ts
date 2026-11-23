import { HttpErrorResponse } from '@angular/common/http';

import { ErrorResponse } from '../api';
import { I18nService } from './i18n.service';

/** Error keys used when the server answered without one of its own error codes (e.g. a proxy or a plain status). */
function statusErrorKey(httpError: HttpErrorResponse): string {
  if (httpError.status === 0) {
    return typeof navigator !== 'undefined' && !navigator.onLine ? 'error.OFFLINE' : 'error.NETWORK_ERROR';
  }
  switch (httpError.status) {
    case 400:
    case 422:
      return 'error.VALIDATION_FAILED';
    case 401:
      return 'error.AUTHENTICATION_REQUIRED';
    case 403:
      return 'error.ACCESS_DENIED';
    case 404:
      return 'error.RESOURCE_NOT_FOUND';
    case 408:
    case 504:
      return 'error.TIMEOUT';
    case 409:
      return 'error.CONFLICT';
    case 413:
      return 'error.PAYLOAD_TOO_LARGE';
    case 429:
      return 'error.TOO_MANY_REQUESTS';
    case 502:
    case 503:
      return 'error.SERVER_UNAVAILABLE';
    default:
      return 'error.UNEXPECTED_ERROR';
  }
}

/** Turns any error into a message for the user, in the current language whenever possible. */
export function resolveApiError(error: unknown, i18n?: I18nService): string {
  const httpError = error instanceof HttpErrorResponse ? error : null;
  const apiError = httpError && typeof httpError.error === 'object' ? httpError.error as ErrorResponse | null : null;
  if (apiError?.details?.length) {
    return apiError.details.map((detail) => i18n?.fieldLabel(detail) ?? detail).join(', ');
  }
  if (apiError?.code && i18n) {
    const key = `error.${apiError.code}`;
    const label = i18n.t(key);
    // A code the client does not know yet still gets a readable message.
    return label === key ? i18n.t(statusErrorKey(httpError!)) : label;
  }
  if (httpError) {
    if (i18n) {
      return i18n.t(statusErrorKey(httpError));
    }
    return apiError?.message || httpError.message;
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return i18n?.t('error.UNEXPECTED_ERROR') ?? 'Unexpected error';
}
