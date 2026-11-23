import { expect, Page, test, TestInfo } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { BEZIRK, openFirstSlot, openNav, settle, signInAsDemo, useGerman } from '../tests/support';

/** One screen (exactly the viewport, no page stitching) per store screenshot: screenshots/store/<project>/NN-name.png */
async function shot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  await settle(page);
  const dir = path.join(__dirname, '..', 'screenshots', 'store', testInfo.project.name);
  mkdirSync(dir, { recursive: true });
  await page.screenshot({ path: path.join(dir, `${name}.png`), animations: 'disabled', caret: 'hide' });
}

test('Neuling', async ({ page }, testInfo) => {
  await useGerman(page);

  await page.goto(`/bezirke/${BEZIRK}`);
  await expect(page.locator('button.slot-row').first()).toBeVisible();
  await shot(page, testInfo, '01-freie-einabs');

  await signInAsDemo(page, 'Neuling');
  await openNav(page, 'Freie EinAbs');
  await openFirstSlot(page);
  await expect(page.getByRole('dialog').getByRole('button', { name: 'Verbindlich buchen' })).toBeVisible();
  await shot(page, testInfo, '02-einab-details');

  await page.getByRole('dialog').getByRole('button', { name: 'Verbindlich buchen' }).click();
  await expect(page.getByRole('dialog').getByText('Du bist dabei!')).toBeVisible();
  await shot(page, testInfo, '03-gebucht');

  await page.getByRole('dialog').getByRole('link', { name: 'Zu meinen Buchungen' }).click();
  await expect(page.getByRole('button', { name: 'Buchung stornieren' }).first()).toBeVisible();
  await page.evaluate(() => window.scrollTo(0, 0));
  await shot(page, testInfo, '04-meine-buchungen');
});

test('EinAb-Geber:in', async ({ page }, testInfo) => {
  await useGerman(page);
  await signInAsDemo(page, 'EinAb-Geber:in');
  await page.goto(`/bezirke/${BEZIRK}/teacher`);
  await page.evaluate(() => window.scrollTo(0, 0));
  await shot(page, testInfo, '05-meine-einabs');
});
