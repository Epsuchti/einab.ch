import { Capacitor } from '@capacitor/core';

/**
 * True inside the Play Store / App Store app (the Capacitor shell that loads einab.ch).
 * The shell injects its bridge before the page scripts run, so this is stable from the first line of the app.
 */
export function isNativeApp(): boolean {
  try {
    return Capacitor.isNativePlatform();
  } catch {
    return false;
  }
}

export function nativePlatform(): 'android' | 'ios' | 'web' {
  const platform = Capacitor.getPlatform();
  return platform === 'android' || platform === 'ios' ? platform : 'web';
}
