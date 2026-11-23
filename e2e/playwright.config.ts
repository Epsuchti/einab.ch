import { defineConfig, devices } from '@playwright/test';

const port = Number(process.env.E2E_SERVER_PORT ?? 8181);
const baseURL = process.env.E2E_BASE_URL ?? `http://localhost:${port}`;

/** German UI, Swiss locale and time zone, like the people who use the app. */
const german = { locale: 'de-CH', timezoneId: 'Europe/Zurich' };

export default defineConfig({
  testDir: './tests',
  // Journeys create demo accounts and book shared demo slots, so they run one after the other.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 120_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI
    ? [['list'], ['html', { open: 'never' }], ['github']]
    : [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL,
    ...german,
    actionTimeout: 15_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], ...german, viewport: { width: 1280, height: 800 } },
    },
    {
      name: 'mobile',
      // iPhone-sized viewport in Chromium, so CI only needs one browser.
      use: { ...devices['Pixel 7'], ...german, viewport: { width: 390, height: 844 } },
    },
  ],
  // Without E2E_BASE_URL the tests start their own server: fresh in-memory database, demo login on, no outbound calls.
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: './scripts/start-server.sh',
        url: `${baseURL}/api/public/demo-login`,
        reuseExistingServer: !process.env.CI,
        timeout: 10 * 60_000,
        stdout: 'pipe',
        stderr: 'pipe',
      },
});
