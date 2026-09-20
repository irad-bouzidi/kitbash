import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setAccessToken } from '@/auth/token';
import { PreviewDialog } from '@/preview/PreviewDialog';
import { languageFor } from '@/preview/highlight';

/**
 * The viewer, against a tree of files this file has never heard of.
 *
 * The behaviour worth protecting is laziness: §9 asks for a file to be fetched only when it is
 * clicked, and the failure mode is quiet — a dialog that eagerly fetched everything would look
 * identical and turn a 12 KB response into a 400 KB one.
 */

const ENVELOPE = {
  schemaVersion: 1,
  projectName: 'billing',
  options: { contraption: 'contraption-alpha' },
  variables: {},
};

const TREE = {
  projectName: 'billing',
  catalogDigest: 'sha256:abc123',
  selectionHash: 'sha256:def456',
  fileCount: 3,
  totalBytes: 2048,
  files: [
    { path: 'README.md', bytes: 120, binary: false },
    { path: 'src/main/java/com/acme/billing/Thing.java', bytes: 512, binary: false },
    { path: 'gradle/wrapper/gradle-wrapper.jar', bytes: 43000, binary: true },
  ],
};

function stubApi() {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (String(url).includes('/preview/file')) {
        const path = decodeURIComponent(String(url).split('path=')[1] ?? '');
        const binary = path.endsWith('.jar');
        return Promise.resolve(
          new Response(
            JSON.stringify({
              path,
              bytes: binary ? 43000 : 512,
              binary,
              content: binary ? null : 'package com.acme.billing;\n\nclass Thing {}\n',
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        );
      }
      return Promise.resolve(
        new Response(JSON.stringify(TREE), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    }),
  );
}

function renderDialog() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <PreviewDialog envelope={ENVELOPE} onClose={() => {}} />
    </QueryClientProvider>,
  );
}

function calls(): string[] {
  return vi
    .mocked(fetch)
    .mock.calls.map(([url]) => url)
    .filter((url): url is string => typeof url === 'string');
}

describe('PreviewDialog', () => {
  beforeEach(() => {
    setAccessToken('a-token');
    stubApi();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('shows the tree with sizes, and how much the whole project weighs', async () => {
    renderDialog();

    expect(await screen.findByText('README.md')).toBeInTheDocument();
    expect(screen.getByText('3 files · 2 KB')).toBeInTheDocument();
  });

  /** §9: a file is fetched only when it is clicked. */
  it('fetches nothing but the tree until a file is clicked', async () => {
    renderDialog();
    await screen.findByText('README.md');

    expect(calls().filter((url) => url.includes('/preview/file'))).toHaveLength(0);

    await userEvent.click(screen.getByRole('button', { name: /Thing\.java/ }));

    await waitFor(() =>
      expect(calls().filter((url) => url.includes('/preview/file'))).toHaveLength(1),
    );
  });

  /** The whole point of the feature: the rendered output, not the template. */
  it('shows the rendered content of the file that was clicked', async () => {
    renderDialog();
    await screen.findByText('README.md');

    await userEvent.click(screen.getByRole('button', { name: /Thing\.java/ }));

    const shown = await screen.findByTestId('preview-content');
    expect(shown).toHaveTextContent('package com.acme.billing;');
    expect(shown.textContent).not.toContain('{{');
  });

  /** §26: binary files are reported as binary with their size, never streamed as text. */
  it('says a binary file is binary rather than rendering its bytes', async () => {
    renderDialog();
    await screen.findByText('README.md');

    await userEvent.click(screen.getByRole('button', { name: /gradle-wrapper\.jar/ }));

    expect(await screen.findByText(/is binary · 43000 bytes/)).toBeInTheDocument();
    expect(screen.queryByTestId('preview-content')).not.toBeInTheDocument();
  });

  it('says what to do before a file is chosen', async () => {
    renderDialog();

    expect(
      await screen.findByText('Choose a file to see what it will actually contain.'),
    ).toBeInTheDocument();
  });
});

/**
 * §26: an unknown extension must render as plain text, not fail. The catalog grows — a recipe
 * added next year will bring file types this list has never heard of, and a preview that broke on
 * them would make the generator look broken.
 */
describe('languageFor', () => {
  it('knows the extensions this catalog produces', () => {
    expect(languageFor('src/Main.java')).toBe('java');
    expect(languageFor('build.gradle.kts')).toBe('kotlin');
    expect(languageFor('compose.yaml')).toBe('yaml');
    expect(languageFor('src/App.tsx')).toBe('typescript');
    expect(languageFor('V1__init.sql')).toBe('sql');
  });

  it('knows the files that have no extension to go on', () => {
    expect(languageFor('Dockerfile')).toBe('dockerfile');
    expect(languageFor('.editorconfig')).toBe('ini');
    expect(languageFor('gradlew')).toBe('bash');
  });

  it('falls back to plain text rather than failing', () => {
    expect(languageFor('mystery.zzz')).toBe('plaintext');
    expect(languageFor('no-extension-at-all')).toBe('plaintext');
    expect(languageFor('')).toBe('plaintext');
  });
});
