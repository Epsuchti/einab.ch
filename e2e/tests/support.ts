import { expect, Page, test, TestInfo } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import path from 'node:path';

/** The Bezirk the server seeds the demo EinAb-Geber:innen and their EinAbs into. */
export const BEZIRK = 'zuerich';
/** Test server only: typing this Foodsharing ID on the login page offers the demo accounts. */
export const DEMO_LOGIN_ID = '1308';

export type DemoRole = 'Neuling' | 'EinAb-Geber:in';

export const isMobile = (testInfo: TestInfo) => testInfo.project.name === 'mobile';

/** Every page opens in German, whatever the browser prefers. */
export async function useGerman(page: Page): Promise<void> {
  await page.addInitScript(() => localStorage.setItem('foodsharing.language', 'de'));
}

/**
 * Screenshots of every step land in screenshots/<project>/<journey>/NN-<step>.png and in the HTML report.
 * Numbered per journey, so the folder reads like the journey itself.
 */
export class Screenshots {
  private counter = 0;
  private readonly dir: string;

  constructor(private readonly page: Page, private readonly testInfo: TestInfo, journey: string) {
    this.dir = path.join(__dirname, '..', 'screenshots', testInfo.project.name, journey);
    mkdirSync(this.dir, { recursive: true });
  }

  async take(name: string): Promise<void> {
    await settle(this.page);
    const file = path.join(this.dir, `${String(++this.counter).padStart(2, '0')}-${name}.png`);
    // With a dialog open, the visible screen is what the person sees; otherwise the whole page.
    const dialogOpen = await this.page.locator('.p-dialog-mask').first().isVisible().catch(() => false);
    if (dialogOpen) {
      await this.page.screenshot({ path: file, animations: 'disabled', caret: 'hide' });
    } else {
      await this.wholePage(file);
    }
    await this.testInfo.attach(name, { path: file, contentType: 'image/png' });
  }

  /**
   * Grows the viewport to the page height for the shot instead of Playwright's fullPage stitching, so the fixed
   * header, tab bar and toasts stay where the person sees them (top and bottom of the screen).
   */
  private async wholePage(file: string): Promise<void> {
    const viewport = this.page.viewportSize();
    if (!viewport) {
      await this.page.screenshot({ path: file, fullPage: true, animations: 'disabled', caret: 'hide' });
      return;
    }
    const height = await this.page.evaluate(() => Math.max(document.documentElement.scrollHeight, document.body.scrollHeight));
    try {
      await this.page.setViewportSize({ width: viewport.width, height: Math.max(viewport.height, Math.min(height, 12_000)) });
      await this.page.waitForTimeout(200);
      await this.page.screenshot({ path: file, animations: 'disabled', caret: 'hide' });
    } finally {
      await this.page.setViewportSize(viewport);
    }
  }
}

/** Waits until requests, the loading bar and toasts' entry animations are done. */
export async function settle(page: Page): Promise<void> {
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(300);
}

/** Runs a journey step: shows up in the report and takes a screenshot at its end. */
export async function step(shots: Screenshots, name: string, body: () => Promise<void>): Promise<void> {
  await test.step(name, async () => {
    await body();
    await shots.take(name);
  });
}

/** Opens a main navigation entry: top bar on desktop, the bottom tab bar on the phone. */
export async function openNav(page: Page, label: string): Promise<void> {
  const tabbar = page.locator('nav.tabbar');
  if (await tabbar.isVisible()) {
    await tabbar.getByRole('link', { name: label }).click();
  } else {
    await page.locator('header').getByRole('link', { name: label, exact: true }).first().click();
  }
}

/** Signs in through the login page with the demo login and accepts the privacy policy. */
export async function signInAsDemo(page: Page, role: DemoRole, shots?: Screenshots): Promise<void> {
  await page.goto(`/bezirke/${BEZIRK}/login`);
  await page.getByRole('textbox', { name: 'Foodsharing-ID' }).fill(DEMO_LOGIN_ID);
  await page.getByRole('button', { name: 'Login-Link senden' }).click();
  await expect(page.getByRole('heading', { name: 'Test-Zugang' })).toBeVisible();
  await shots?.take('test-zugang-waehlen');
  await page.getByRole('button', { name: `Testen als ${role}` }).click();
  await expect(page).toHaveURL(/privacy-consent/);
  await page.getByRole('checkbox').check();
  await shots?.take('datenschutz-zustimmen');
  await page.getByRole('button', { name: 'Zustimmen und weiter' }).click();
  await expect(page).not.toHaveURL(/privacy-consent/);
}

/** Opens the first EinAb in the list of free EinAbs (the list scrolls under the sticky header, so start at the top). */
export async function openFirstSlot(page: Page): Promise<void> {
  await page.evaluate(() => window.scrollTo(0, 0));
  const row = page.locator('button.slot-row').first();
  await row.scrollIntoViewIfNeeded();
  await row.click();
  await expect(page.locator('.slot-details-dialog')).toBeVisible();
}
