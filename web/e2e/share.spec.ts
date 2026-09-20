import { expect, test } from '@playwright/test';

/*
 * §25's done-when: copy the URL from a configured wizard, open it in a clean browser profile, and
 * get the same selection and the same validation result — and the same for a token link.
 *
 * "Clean profile" is why these use a fresh context rather than a fresh page: §22 keeps the token
 * in session storage, so a new context is a genuinely cold visitor who has to sign in first.
 */

async function configure(page: import('@playwright/test').Page) {
  await page.goto('/new');
  await expect(page.getByLabel('Backend')).toBeVisible({ timeout: 30_000 });
  await page.getByLabel('Backend').selectOption('backend-spring-java');
  await page.getByLabel('Build tool').selectOption('build-gradle-kts');
  await page.getByLabel('Project name').fill('shared-service');
  await expect(page.getByRole('button', { name: 'Generate' })).toBeEnabled();
}

test('the URL is the share mechanism: a clean profile opens the same selection', async ({
  page,
  browser,
}) => {
  await configure(page);
  await page.getByRole('button', { name: 'Share' }).click();
  const url = await page.getByTestId('share-dialog').getByLabel('Link').inputValue();

  // A different context: no session, no storage, nothing but the link.
  const clean = await browser.newContext();
  const visitor = await clean.newPage();
  await visitor.goto(url);

  await expect(visitor.getByLabel('Backend')).toHaveValue('backend-spring-java', {
    timeout: 30_000,
  });
  await expect(visitor.getByLabel('Project name')).toHaveValue('shared-service');
  // Re-validated on arrival (§25), not at Generate: the rail is filled before anything is clicked.
  await expect(visitor.getByTestId('stack-summary')).toContainText('Spring Boot');
  await expect(visitor.getByRole('button', { name: 'Generate' })).toBeEnabled();
  await clean.close();
});

test('an invalid shared selection says so on arrival, not at Generate', async ({ browser }) => {
  const clean = await browser.newContext();
  const visitor = await clean.newPage();

  // A link somebody made before the rule tightened, or by hand. §25 wants the answer on arrival.
  await visitor.goto('/new?projectName=Not%20A%20Project&backend=backend-spring-java');
  await expect(visitor.getByLabel('Backend')).toBeVisible({ timeout: 30_000 });

  await expect(visitor.getByText(/Does not match/)).toBeVisible();
  await expect(visitor.getByRole('button', { name: 'Generate' })).toBeDisabled();
  await clean.close();
});

test('and a short link opens the same selection, when the URL is long enough to want one', async ({
  page,
  browser,
}) => {
  await configure(page);
  // Filling the optional variables makes the URL long enough that the dialog offers a token —
  // which is exactly the case §8 says tokens exist for, rather than a contrivance.
  await page.getByLabel('Database').selectOption('db-postgres-flyway');
  // Long, and legal: no segment may be a Java or Kotlin keyword (kitbash-20), which rules out
  // the obvious filler words.
  await page.getByLabel('Group ID').fill('com.acme.platform.services.identity.provisioning');
  await page
    .getByLabel('Package name')
    .fill('com.acme.platform.services.identity.provisioning.shared');
  await expect(page.getByRole('button', { name: 'Generate' })).toBeEnabled();

  await page.getByRole('button', { name: 'Share' }).click();
  const dialog = page.getByTestId('share-dialog');
  await dialog.getByRole('button', { name: 'Make a short link' }).click();
  const shortLink = await dialog.getByLabel('Short link').inputValue();
  expect(shortLink).toMatch(/\/s\/[0-9a-hjkmnp-tv-z]{11}$/);

  const clean = await browser.newContext();
  const visitor = await clean.newPage();
  await visitor.goto(shortLink);

  // The token redirects into the wizard's own URL form: one encoding, so both kinds of link
  // arrive at the same place and re-validate through the same path.
  await expect(visitor.getByLabel('Backend')).toHaveValue('backend-spring-java', {
    timeout: 30_000,
  });
  await expect(visitor.getByLabel('Project name')).toHaveValue('shared-service');
  await expect(visitor).toHaveURL(/backend=backend-spring-java/);
  await expect(visitor.getByRole('button', { name: 'Generate' })).toBeEnabled();
  await clean.close();
});
