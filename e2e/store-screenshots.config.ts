import { defineConfig } from '@playwright/test';
import base from './playwright.config';

/**
 * Store screenshots (not part of `npm test`): `npx playwright test -c store-screenshots.config.ts`.
 * Phone viewports rendered at 3x, so the PNGs have exactly the pixel sizes the stores accept:
 * - play:     432×768 at 2.5x → 1080×1920 (Google Play phone, 9:16; a large Android phone like the Pixel 9 Pro XL)
 * - appstore: 430×932 → 1290×2796 (App Store iPhone 6.9")
 */
const german = { locale: 'de-CH', timezoneId: 'Europe/Zurich' };
const phone = { isMobile: true, hasTouch: true, deviceScaleFactor: 3, ...german };

export default defineConfig({
  ...base,
  testDir: './store',
  reporter: [['list']],
  projects: [
    { name: 'play', use: { ...phone, deviceScaleFactor: 2.5, viewport: { width: 432, height: 768 } } },
    { name: 'appstore', use: { ...phone, viewport: { width: 430, height: 932 } } },
  ],
});
