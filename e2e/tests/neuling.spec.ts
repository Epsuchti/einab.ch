import { expect, test } from '@playwright/test';
import { BEZIRK, DEMO_LOGIN_ID, openFirstSlot, openNav, Screenshots, step, useGerman } from './support';

/**
 * A Neuling (newcomer) finds the app, signs in with the demo login, books an EinAb, sees it in
 * "Meine Buchungen", cancels it again and looks at the profile.
 */
test('Neuling: EinAb finden, buchen und stornieren', async ({ page }, testInfo) => {
  await useGerman(page);
  const shots = new Screenshots(page, testInfo, 'neuling');

  await step(shots, 'bezirk-waehlen', async () => {
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Wähle deinen Bezirk' })).toBeVisible();
  });

  await step(shots, 'freie-einabs-ohne-login', async () => {
    await page.getByRole('link', { name: 'Zürich' }).click();
    await expect(page).toHaveURL(new RegExp(`/bezirke/${BEZIRK}$`));
    await expect(page.locator('button.slot-row').first()).toBeVisible();
  });

  await step(shots, 'einab-details-ohne-login', async () => {
    await openFirstSlot(page);
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('link', { name: 'Zum Buchen einloggen' })).toBeVisible();
  });

  await step(shots, 'login', async () => {
    await page.getByRole('dialog').getByRole('link', { name: 'Zum Buchen einloggen' }).click();
    await expect(page.getByRole('textbox', { name: 'Foodsharing-ID' })).toBeVisible();
  });

  // Typed by hand here (instead of signInAsDemo), because the Neuling came from an EinAb they want to book.
  await step(shots, 'test-zugang-waehlen', async () => {
    await page.getByRole('textbox', { name: 'Foodsharing-ID' }).fill(DEMO_LOGIN_ID);
    await page.getByRole('button', { name: 'Login-Link senden' }).click();
    await expect(page.getByRole('heading', { name: 'Test-Zugang' })).toBeVisible();
  });

  await step(shots, 'datenschutz-zustimmen', async () => {
    await page.getByRole('button', { name: 'Testen als Neuling' }).click();
    await expect(page).toHaveURL(/privacy-consent/);
    await page.getByRole('checkbox').check();
  });

  // After the login the app brings the Neuling back to the EinAb they wanted to book.
  await step(shots, 'einab-buchen-nach-login', async () => {
    await page.getByRole('button', { name: 'Zustimmen und weiter' }).click();
    await expect(page).not.toHaveURL(/privacy-consent/);
    await expect(page.getByRole('dialog').getByRole('button', { name: 'Verbindlich buchen' })).toBeVisible();
  });

  await step(shots, 'einab-gebucht', async () => {
    await page.getByRole('dialog').getByRole('button', { name: 'Verbindlich buchen' }).click();
    await expect(page.getByRole('dialog').getByText('Du bist dabei!')).toBeVisible();
  });

  await step(shots, 'meine-buchungen', async () => {
    await page.getByRole('dialog').getByRole('link', { name: 'Zu meinen Buchungen' }).click();
    await expect(page).toHaveURL(/my-bookings/);
    await expect(page.getByRole('button', { name: 'Buchung stornieren' }).first()).toBeVisible();
  });

  await step(shots, 'buchung-stornieren-bestaetigen', async () => {
    await page.getByRole('button', { name: 'Buchung stornieren' }).first().click();
    await expect(page.getByRole('alertdialog', { name: 'Buchung stornieren?' })).toBeVisible();
  });

  await step(shots, 'buchung-storniert', async () => {
    const confirm = page.getByRole('alertdialog', { name: 'Buchung stornieren?' });
    await confirm.getByRole('button', { name: 'Stornieren', exact: true }).click();
    await expect(page.getByText('Du hast keine kommenden EinAbs gebucht.')).toBeVisible();
  });

  await step(shots, 'freie-einabs-als-neuling', async () => {
    await openNav(page, 'Freie EinAbs');
    await expect(page.getByText('Du siehst nur EinAbs, die du mit deiner Erfahrung buchen kannst.')).toBeVisible();
  });

  await step(shots, 'einab-details-als-neuling', async () => {
    await openFirstSlot(page);
    await expect(page.getByRole('dialog').getByRole('button', { name: 'Verbindlich buchen' })).toBeVisible();
    await page.getByRole('dialog').getByRole('button', { name: 'Schliessen' }).last().click();
  });

  await step(shots, 'hilfe', async () => {
    await page.goto(`/bezirke/${BEZIRK}/help`);
    await expect(page.getByRole('heading').first()).toBeVisible();
  });

  await step(shots, 'profil', async () => {
    await page.goto(`/bezirke/${BEZIRK}/profile`);
    await expect(page.getByRole('heading', { name: 'Profil' })).toBeVisible();
    await expect(page.getByRole('main').getByText(/Test-Neuling/)).toBeVisible();
  });

  await step(shots, 'abgemeldet', async () => {
    await page.getByRole('button', { name: 'Logout' }).click();
    await expect(page.getByRole('link', { name: 'Meine Buchungen' })).toHaveCount(0);
  });
});
