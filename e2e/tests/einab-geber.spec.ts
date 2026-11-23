import { Browser, expect, Page, test, TestInfo } from '@playwright/test';
import { BEZIRK, isMobile, openNav, Screenshots, signInAsDemo, step, useGerman } from './support';

/**
 * An EinAb-Geber:in (teacher) signs in with the demo login, publishes a new EinAb, sees a Neuling book it and
 * walks through the tools on the dashboard.
 */
test('EinAb-Geber:in: EinAb anbieten und Buchung sehen', async ({ page, browser }, testInfo) => {
  await useGerman(page);
  const shots = new Screenshots(page, testInfo, 'einab-geber');
  // Unique per run, so the steps find this EinAb even when the server is reused.
  const tag = Date.now().toString(36).slice(-4).toUpperCase();
  const businessName = `Testladen ${tag}`;
  const publicLocation = `Zürich Kreis 4 (${tag})`;

  await step(shots, 'meine-einabs', async () => {
    await signInAsDemo(page, 'EinAb-Geber:in', shots);
    await expect(page).toHaveURL(new RegExp(`/bezirke/${BEZIRK}/teacher$`));
    await expect(page.getByText('Du hast keine kommenden EinAbs.')).toBeVisible();
  });

  await step(shots, 'neue-einab-formular', async () => {
    await page.getByRole('button', { name: 'Neue EinAb' }).first().click();
    const dialog = page.getByRole('dialog', { name: 'Neue EinAb' });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('combobox', { name: 'Kategorie wählen' }).click();
    await page.getByRole('option', { name: 'Supermarkt' }).click();
    await dialog.locator('input[formcontrolname="businessName"]').fill(businessName);
    await dialog.locator('input[formcontrolname="privateAddress"]').fill('Testweg 1, 8000 Zürich');
    await dialog.locator('input[formcontrolname="publicGeneralLocation"]').fill(publicLocation);
    await dialog.locator('textarea[formcontrolname="publicInfo"]').fill('Bitte eine Tasche mitbringen.');
  });

  await step(shots, 'einab-veroeffentlicht', async () => {
    await page.getByRole('dialog', { name: 'Neue EinAb' }).getByRole('button', { name: 'Speichern' }).click();
    await expect(page.getByRole('dialog', { name: 'Neue EinAb' })).toBeHidden();
    await expect(page.getByText(businessName).filter({ visible: true }).first()).toBeVisible();
  });

  await test.step('ein Neuling bucht die neue EinAb', async () => {
    await bookAsNewcomer(browser, testInfo, publicLocation);
  });

  await step(shots, 'buchung-sehen', async () => {
    await page.reload();
    if (!isMobile(testInfo)) {
      // On the desktop the slots sit in an expandable table row.
      await page.getByRole('row', { name: new RegExp(businessName) }).getByRole('button', { name: 'Slots anzeigen' }).click();
    }
    await expect(page.locator('.slot-entry__who strong', { hasText: 'Test-Neuling' }).first()).toBeVisible();
  });

  await step(shots, 'einab-aktionen', async () => {
    const actions = isMobile(testInfo)
      ? page.locator('article.einab-card', { hasText: businessName }).getByRole('button', { name: 'Aktionen' })
      : page.getByRole('row', { name: new RegExp(businessName) }).getByRole('button', { name: 'Aktionen' });
    await actions.click();
    await expect(page.getByRole('menu')).toBeVisible();
  });

  await step(shots, 'kalendereinstellungen', async () => {
    await page.keyboard.press('Escape');
    await page.getByText('Kalendereinstellungen').click();
    await expect(page.getByRole('button', { name: 'Kalender verbinden', exact: true })).toBeVisible();
  });

  await step(shots, 'spontane-einab-nachtragen', async () => {
    await page.getByText('Spontane EinAb nachtragen').click();
    await expect(page.locator('app-past-einab-form')).toBeVisible();
  });

  await step(shots, 'anleitung', async () => {
    await page.getByRole('link', { name: /Anleitung: Erklärvideo und Spickzettel/ }).click();
    await expect(page).toHaveURL(/\/help$/);
  });

  await step(shots, 'freie-einabs', async () => {
    await openNav(page, 'Freie EinAbs');
    await expect(page.locator('button.slot-row').first()).toBeVisible();
  });

  await step(shots, 'profil', async () => {
    await page.goto(`/bezirke/${BEZIRK}/profile`);
    await expect(page.getByRole('main').getByText(/Test-EinAb-Geber:in/)).toBeVisible();
  });
});

/**
 * A second person, in their own browser, signs in as Neuling and books the new EinAb. Neulinge only see the public
 * location before booking, so they search for that.
 */
async function bookAsNewcomer(browser: Browser, testInfo: TestInfo, publicLocation: string): Promise<void> {
  const context = await browser.newContext(testInfo.project.use);
  const page: Page = await context.newPage();
  try {
    await useGerman(page);
    const shots = new Screenshots(page, testInfo, 'einab-geber-neuling-bucht');
    await signInAsDemo(page, 'Neuling');
    await step(shots, 'neue-einab-finden', async () => {
      await openNav(page, 'Freie EinAbs');
      await page.getByRole('searchbox', { name: 'Suche' }).fill(publicLocation);
      await expect(page.locator('button.slot-row')).toHaveCount(1);
    });
    await step(shots, 'neue-einab-buchen', async () => {
      await page.locator('button.slot-row').click();
      await page.getByRole('dialog').getByRole('button', { name: 'Verbindlich buchen' }).click();
      await expect(page.getByRole('dialog').getByText('Du bist dabei!')).toBeVisible();
    });
  } finally {
    await context.close();
  }
}
