import { expect, test } from '@playwright/test';

/*
 * §26's done-when: a full-stack preview renders the tree in well under a second, and a clicked
 * file shows its real rendered content with correct highlighting.
 *
 * The value this earns is trust, so the assertion that matters is that the content is the
 * project's — not the template's.
 */

test('the preview shows the real rendered tree, and a clicked file its real content', async ({
  page,
}) => {
  await page.goto('/new');
  await expect(page.getByLabel('Backend')).toBeVisible({ timeout: 30_000 });
  await page.getByLabel('Backend').selectOption('backend-spring-java');
  await page.getByLabel('Build tool').selectOption('build-gradle-kts');
  await page.getByLabel('Frontend').selectOption('frontend-react-vite');
  await page.getByLabel('Project name').fill('preview-service');
  await page.getByLabel('Package name').fill('com.acme.preview');
  await expect(page.getByRole('button', { name: 'Generate' })).toBeEnabled();

  const started = Date.now();
  await page.getByRole('button', { name: 'Preview' }).click();
  const dialog = page.getByTestId('preview-dialog');
  await expect(dialog.getByTestId('preview-tree')).toBeVisible();
  // The tree is the whole project, so it is worth knowing it arrived quickly.
  expect(Date.now() - started).toBeLessThan(5_000);

  await expect(
    dialog.getByText('Choose a file to see what it will actually contain.'),
  ).toBeVisible();

  await dialog.getByRole('button', { name: /PreviewServiceApplication\.java/ }).click();
  const content = dialog.getByTestId('preview-content');
  // The rendered output: the package the user chose, not the placeholder that produced it.
  await expect(content).toContainText('package com.acme.preview;');
  await expect(content).not.toContainText('{{');
  // Highlighted, which is what makes it readable rather than a wall.
  await expect(content.locator('.hljs-keyword').first()).toBeVisible();
});

test('a binary file is named as binary rather than rendered as text', async ({ page }) => {
  await page.goto('/new');
  await expect(page.getByLabel('Backend')).toBeVisible({ timeout: 30_000 });
  await page.getByLabel('Backend').selectOption('backend-spring-java');
  await page.getByLabel('Build tool').selectOption('build-gradle-kts');
  await expect(page.getByRole('button', { name: 'Generate' })).toBeEnabled();

  await page.getByRole('button', { name: 'Preview' }).click();
  const dialog = page.getByTestId('preview-dialog');
  await dialog.getByRole('button', { name: /gradle-wrapper\.jar/ }).click();

  await expect(dialog.getByText(/is binary/)).toBeVisible();
});
