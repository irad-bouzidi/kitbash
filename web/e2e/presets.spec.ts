import { expect, test } from '@playwright/test';

/*
 * §23's done-when, through a browser: save a preset, reload cold, regenerate in one click.
 *
 * The list is the landing page (§9), so "cold" here means exactly what it means for a returning
 * user — open the application and the stack is there.
 */

async function buildAStack(page: import('@playwright/test').Page, projectName: string) {
  await page.goto('/new');
  await expect(page.getByLabel('Backend')).toBeVisible({ timeout: 30_000 });
  await page.getByLabel('Backend').selectOption('backend-spring-java');
  await page.getByLabel('Build tool').selectOption('build-gradle-kts');
  await page.getByLabel('Project name').fill(projectName);
  await expect(page.getByRole('button', { name: 'Generate' })).toBeEnabled();
}

test('a preset is saved from the wizard and generates in one click from a cold load', async ({
  page,
}) => {
  const name = `house-stack-${Date.now()}`;
  await buildAStack(page, 'billing-service');

  await page.getByRole('button', { name: 'Save as preset' }).click();
  const dialog = page.getByTestId('save-preset');
  await expect(dialog).toBeVisible();
  await dialog.getByLabel('Name').fill(name);
  await dialog.getByRole('button', { name: 'Save preset' }).click();
  await expect(dialog).toBeHidden();

  // Cold: a fresh navigation to the landing page, nothing carried over in the URL.
  await page.goto('/');
  const card = page.getByTestId('preset-card').filter({ hasText: name });
  await expect(card).toBeVisible();
  // §7: saved as a selection, so it follows the catalog rather than freezing on it.
  await expect(card).toContainText('tracks latest');

  const download = await Promise.all([
    page.waitForEvent('download'),
    card.getByRole('button', { name: 'Generate' }).click(),
  ]).then(([event]) => event);

  expect(download.suggestedFilename()).toBe('billing-service.zip');
});

test('the detail page says what the preset selects and on what terms', async ({ page }) => {
  const name = `detail-stack-${Date.now()}`;
  await buildAStack(page, 'detail-service');

  await page.getByRole('button', { name: 'Save as preset' }).click();
  await page.getByTestId('save-preset').getByLabel('Name').fill(name);
  await page.getByTestId('save-preset').getByRole('button', { name: 'Save preset' }).click();

  await page.goto('/');
  await page.getByRole('link', { name }).click();

  await expect(page.getByText('Tracks the latest catalog')).toBeVisible();
  // The recipes it selects, with the version each will resolve to.
  await expect(page.getByTestId('preset-recipes')).toContainText('backend-spring-java');
  await expect(page.getByTestId('preset-recipes')).toContainText('latest');
});

test('opening a preset in the wizard restores the selection through the URL', async ({ page }) => {
  const name = `reopen-stack-${Date.now()}`;
  await buildAStack(page, 'reopen-service');

  await page.getByRole('button', { name: 'Save as preset' }).click();
  await page.getByTestId('save-preset').getByLabel('Name').fill(name);
  await page.getByTestId('save-preset').getByRole('button', { name: 'Save preset' }).click();

  await page.goto('/');
  await page
    .getByTestId('preset-card')
    .filter({ hasText: name })
    .getByRole('link', { name: 'Open in the wizard' })
    .click();

  // §9: the URL is the configuration, so a preset opens by being encoded into one.
  await expect(page).toHaveURL(/backend=backend-spring-java/);
  await expect(page.getByLabel('Backend')).toHaveValue('backend-spring-java');
  await expect(page.getByLabel('Project name')).toHaveValue('reopen-service');
});
