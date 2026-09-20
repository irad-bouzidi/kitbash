import { expect, test } from '@playwright/test';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';

/*
 * The whole path, through a real browser against a real server: catalog fetched, stack chosen,
 * selection validated, zip downloaded.
 *
 * This is the one place in the repository that names options the way a user sees them. Everywhere
 * under web/src is forbidden from doing so (§8) and a contract test enforces that; an end-to-end
 * test drives the rendered page, so naming what is on it is the job.
 */

async function chooseAStack(page: import('@playwright/test').Page) {
  await page.getByLabel('Backend').selectOption('backend-spring-java');
  await page.getByLabel('Build tool').selectOption('build-gradle-kts');
  await page.getByLabel('Database').selectOption('db-postgres-flyway');
}

test('choosing a stack and clicking Generate downloads a zip', async ({ page }) => {
  await page.goto('/');

  // The wizard renders from /api/v1/metadata, so the first thing to wait for is the catalog.
  await expect(page.getByLabel('Backend')).toBeVisible();
  await chooseAStack(page);

  await page.getByLabel('Project name').fill('billing-service');
  await page.getByLabel('Group ID').fill('com.acme');
  await page.getByLabel('Package name').fill('com.acme.billing');

  // Debounced validation has to come back before Generate is offered.
  await expect(page.getByRole('button', { name: 'Generate' })).toBeEnabled();

  const download = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'Generate' }).click(),
  ]).then(([event]) => event);

  expect(download.suggestedFilename()).toBe('billing-service.zip');

  const path = await download.path();
  const { size } = await stat(path);
  // Not merely "a download event fired": the file has to be a real, non-empty zip.
  expect(size).toBeGreaterThan(50_000);

  const magic = await new Promise<Buffer>((resolve, reject) => {
    const chunks: Buffer[] = [];
    createReadStream(path, { start: 0, end: 3 })
      .on('data', (chunk) => chunks.push(chunk as Buffer))
      .on('end', () => resolve(Buffer.concat(chunks)))
      .on('error', reject);
  });
  expect(magic.subarray(0, 2).toString('latin1')).toBe('PK');
});

test('the right rail shows what the resolver added, not just what was picked', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByLabel('Backend')).toBeVisible();
  await chooseAStack(page);

  // §9: a user who picked a backend and got a project skeleton should be told.
  const rail = page.getByTestId('stack-summary');
  await expect(rail.getByText('added for you').first()).toBeVisible();
});

test('an invalid project name blocks the download, with the reason on the field', async ({
  page,
}) => {
  await page.goto('/');
  await expect(page.getByLabel('Backend')).toBeVisible();
  await chooseAStack(page);

  await page.getByLabel('Project name').fill('Not A Project');

  await expect(page.getByText(/Does not match/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Generate' })).toBeDisabled();
});

test('the selection is in the URL, so a link captures a configuration', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByLabel('Backend')).toBeVisible();
  await chooseAStack(page);

  await expect(page).toHaveURL(/backend=backend-spring-java/);

  // A reload from that URL restores the same stack: the link is the configuration.
  await page.reload();
  await expect(page.getByLabel('Backend')).toHaveValue('backend-spring-java');
});
