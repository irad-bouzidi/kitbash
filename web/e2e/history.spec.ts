import { expect, test } from '@playwright/test';

/*
 * §24's done-when, through a browser: a generation becomes a receipt, and the receipt carries the
 * lock that makes it reproducible.
 */

test('generating leaves a receipt with its lock, and it replays exactly', async ({ page }) => {
  await page.goto('/new');
  await expect(page.getByLabel('Backend')).toBeVisible({ timeout: 30_000 });
  await page.getByLabel('Backend').selectOption('backend-spring-java');
  await page.getByLabel('Build tool').selectOption('build-gradle-kts');
  await page.getByLabel('Project name').fill('receipt-service');
  await expect(page.getByRole('button', { name: 'Generate' })).toBeEnabled();

  await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'Generate' }).click(),
  ]);

  await page.goto('/history');
  const row = page.getByTestId('generation-card').filter({ hasText: 'receipt-service' }).first();
  await expect(row).toBeVisible();
  // §7: the lock is the valuable part, so it is on the receipt rather than implied by it.
  await expect(row).toContainText('reproducible');
  await row.getByText('The lock').click();
  await expect(row.getByTestId('generation-lock')).toContainText('backend-spring-java');

  await row.getByRole('button', { name: 'Replay exactly' }).click();
  // §24: the response states which mode ran and what changed if anything did.
  await expect(row.getByRole('status')).toContainText('Replayed exact');
  await expect(row.getByRole('status')).toContainText('nothing has changed since');

  const download = await Promise.all([
    page.waitForEvent('download'),
    row.getByRole('button', { name: 'Download again' }).click(),
  ]).then(([event]) => event);
  expect(download.suggestedFilename()).toBe('receipt-service.zip');
});
