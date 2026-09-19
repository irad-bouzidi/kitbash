import { expect, test } from '@playwright/test';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';

test('filling the form and clicking Generate downloads a zip', async ({ page }) => {
  await page.goto('/');

  await page.getByLabel('Project name').fill('billing-service');
  await page.getByLabel('Group ID').fill('com.acme');
  await page.getByLabel('Package name').fill('com.acme.billing');

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

test('an invalid project name is rejected in the browser, with no download', async ({ page }) => {
  await page.goto('/');

  await page.getByLabel('Project name').fill('Not A Project');

  let downloaded = false;
  page.on('download', () => {
    downloaded = true;
  });
  await page.getByRole('button', { name: 'Generate' }).click();

  await expect(page.getByText(/Lowercase letters, digits and hyphens only/)).toBeVisible();
  expect(downloaded).toBe(false);
});

test('both themes render', async ({ page }) => {
  await page.goto('/');

  const html = page.locator('html');
  const before = await html.getAttribute('class');
  await page.getByRole('button', { name: /Switch to (light|dark) theme/ }).click();

  await expect(html).not.toHaveClass(before ?? '');
});
