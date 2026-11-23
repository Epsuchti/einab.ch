/// <reference types="@capacitor-firebase/messaging" />
import type { CapacitorConfig } from '@capacitor/cli';

/**
 * The Play Store / App Store app is a thin native shell around the live website: it always loads
 * https://einab.ch, so every web release reaches app users immediately without a store update.
 * A new store build is only needed when native parts change (plugins, icons, permissions, this file).
 *
 * native-shell/ holds the small page shown when einab.ch cannot be reached (no network).
 *
 * Until both stores have approved the app, it loads the TEST server (test-foodsharing.it4user.ch), also in
 * store builds. Switch to production for the release: change DEFAULT_APP_URL to https://einab.ch, run
 * `npx cap sync`, then build and upload new store versions. EINAB_APP_URL=... npx cap sync overrides it for one build.
 * App Links / Universal Links are set up for both domains (AndroidManifest.xml, App.entitlements).
 */
const DEFAULT_APP_URL = 'https://test-foodsharing.it4user.ch';
const appUrl = process.env['EINAB_APP_URL']?.trim() || DEFAULT_APP_URL;
if (!/^https:\/\/(einab\.ch|test-foodsharing\.it4user\.ch)$/.test(appUrl)) {
  throw new Error(`EINAB_APP_URL must be https://einab.ch or https://test-foodsharing.it4user.ch, got ${appUrl}`);
}
const config: CapacitorConfig = {
  appId: 'ch.einab.app',
  appName: 'EinAb',
  webDir: 'native-shell',
  // The web app checks this marker to turn on native push and to skip the service worker inside the app.
  appendUserAgent: 'EinAbApp',
  backgroundColor: '#f5f8f4',
  server: {
    url: appUrl,
    // Shown by the native shell when the site cannot be loaded.
    errorPath: 'index.html',
    // Only einab.ch stays inside the app; links to other sites (foodsharing.network, maps, ...) open in the browser.
    allowNavigation: []
  },
  ios: {
    // Keeps the page below the status bar / notch; the site does not use viewport-fit=cover.
    contentInset: 'automatic'
  },
  plugins: {
    FirebaseMessaging: {
      presentationOptions: ['alert', 'badge', 'sound']
    }
  },
  experimental: {
    ios: {
      spm: {
        packageOptions: {
          '@capacitor-firebase/messaging': { symlink: true }
        }
      }
    }
  }
};

export default config;
