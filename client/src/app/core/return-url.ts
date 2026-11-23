/**
 * Accepts only pages of this app (e.g. "/bezirke/zuerich/pickups") as target after signing in, so a
 * manipulated link can never forward to another site. The login pages themselves are excluded.
 */
export function safeReturnUrl(value: string | null | undefined): string | null {
  if (!value) {
    return null;
  }
  const url = value.trim();
  if (!url.startsWith('/bezirke/') || url.includes('//') || url.includes('\\') || url.length > 500) {
    return null;
  }
  const page = url.split(/[?#]/)[0];
  if (/^\/bezirke\/[^/]+\/(login|verify-login)(\/|$)/.test(page)) {
    return null;
  }
  return url;
}

const SURVEY_PAGE = /^\/bezirke\/[^/]+\/survey\/[^/?#]+(?:[?#]|$)/;

/** A survey fill-out page. Signing in through a survey link skips the privacy consent and phone number steps until normal app use. */
export function isSurveyPageUrl(url: string | null | undefined): boolean {
  return !!url && SURVEY_PAGE.test(url);
}
