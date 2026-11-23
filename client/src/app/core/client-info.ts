import { SupportClientPlatform } from '../api';
import { isNativeApp, nativePlatform } from './native-app';

/** Set at build time from server/deploy/version.txt (see scripts/build.mjs); missing in `ng serve`. */
declare const EINAB_RELEASE: string | undefined;

/** Release number this page was built with, shown in the footer and sent with support requests. */
export const CLIENT_RELEASE: string = typeof EINAB_RELEASE === 'string' && EINAB_RELEASE ? EINAB_RELEASE : 'dev';

/** Store app, website installed to the home screen, or a normal browser tab. */
export function clientPlatform(): SupportClientPlatform {
  if (isNativeApp()) {
    return nativePlatform() === 'ios' ? SupportClientPlatform.IosApp : SupportClientPlatform.AndroidApp;
  }
  try {
    const standalone = window.matchMedia?.('(display-mode: standalone)').matches || (navigator as Navigator & { standalone?: boolean }).standalone === true;
    return standalone ? SupportClientPlatform.Pwa : SupportClientPlatform.Web;
  } catch {
    return SupportClientPlatform.Web;
  }
}
