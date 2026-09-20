import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setAccessToken } from '@/auth/token';
import { HistoryPage } from '@/history/HistoryPage';

/**
 * The history page, against generations of a stack this file has never heard of.
 *
 * What is worth testing here is the part §24 says must not blur: the two replay modes answer
 * different questions, and the page has to keep asking the right one and reporting which ran.
 */

function aGeneration(overrides: Record<string, unknown> = {}) {
  return {
    id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
    projectName: 'billing',
    selection: {
      schemaVersion: 1,
      projectName: 'billing',
      options: { contraption: 'contraption-alpha' },
      variables: {},
    },
    lock: { 'contraption-alpha': '1.4.0', base: '1.0.0' },
    catalogDigest: 'sha256:abc123',
    selectionHash: 'sha256:def456',
    status: 'succeeded',
    durationMillis: 412,
    sizeBytes: 163029,
    kept: false,
    exactlyReproducible: true,
    createdAt: '2026-09-01T10:00:00Z',
    expiresAt: '2026-10-01T10:00:00Z',
    ...overrides,
  };
}

let replayResponse: unknown = {
  mode: 'exact',
  originalCatalogDigest: 'sha256:abc123',
  currentCatalogDigest: 'sha256:abc123',
  catalogMoved: false,
  summary: 'no change',
  changes: [],
};

function stubApi(generations: unknown[]) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (String(url).includes('/replay')) {
        return Promise.resolve(
          new Response(JSON.stringify(replayResponse), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        );
      }
      if (String(url).includes('/download')) {
        return Promise.resolve(new Response(new Blob(['zip'])));
      }
      return Promise.resolve(
        new Response(JSON.stringify(generations), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    }),
  );
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <HistoryPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** The URLs fetch was called with. `fetch` accepts more than a string; this application sends one. */
function calls(): string[] {
  return vi
    .mocked(fetch)
    .mock.calls.map(([url]) => url)
    .filter((url): url is string => typeof url === 'string');
}

describe('HistoryPage', () => {
  beforeEach(() => {
    setAccessToken('a-token');
    URL.createObjectURL = vi.fn(() => 'blob:generated');
    URL.revokeObjectURL = vi.fn();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('shows the lock, because that is the part worth keeping (§7)', async () => {
    stubApi([aGeneration()]);
    renderPage();

    expect(await screen.findByText('billing')).toBeInTheDocument();
    expect(screen.getByTestId('generation-lock')).toHaveTextContent('contraption-alpha');
    expect(screen.getByTestId('generation-lock')).toHaveTextContent('1.4.0');
  });

  /**
   * §24 keeps the modes apart on purpose: exact asks "what did I ship?", current asks "what would
   * this give me today?". A page that sent one and reported the other would make a reproduction
   * that reproduces nothing.
   */
  it('asks for the mode the button names, and reports which one ran', async () => {
    stubApi([aGeneration()]);
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Replay exactly' }));

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Replayed exact'));
    expect(calls().some((url) => url.includes('/replay?mode=exact'))).toBe(true);
  });

  it('reports what moved when replaying on today’s catalog', async () => {
    replayResponse = {
      mode: 'current',
      originalCatalogDigest: 'sha256:old',
      currentCatalogDigest: 'sha256:new',
      catalogMoved: true,
      summary: 'contraption-alpha 1.4.0→2.0.0',
      changes: [{ recipeId: 'contraption-alpha', before: '1.4.0', after: '2.0.0' }],
    };
    stubApi([aGeneration()]);
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Replay on today’s catalog' }));

    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent('contraption-alpha 1.4.0→2.0.0'),
    );
  });

  it('says up front when a row can no longer be reproduced exactly', async () => {
    stubApi([aGeneration({ exactlyReproducible: false })]);
    renderPage();

    expect(await screen.findByText('catalog moved')).toBeInTheDocument();
  });

  /**
   * §24 asks for failures to be recorded, and a history that shows them has to make clear there
   * is nothing to download — the row exists to answer "why did this break", not to be replayed.
   */
  it('shows a failed generation without offering to download it', async () => {
    stubApi([aGeneration({ status: 'failed', lock: {}, sizeBytes: null })]);
    renderPage();

    expect(await screen.findByText('failed')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Download again' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Replay exactly' })).toBeDisabled();
  });

  /** §10: saving a generation as a preset copies its lock forward. */
  it('saves a generation as a preset with its lock pinned, not as today’s versions', async () => {
    stubApi([aGeneration()]);
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Save as preset' }));

    await waitFor(() => expect(calls().some((url) => url.endsWith('/api/v1/presets'))).toBe(true));
    const save = vi
      .mocked(fetch)
      .mock.calls.find(([url]) => typeof url === 'string' && url.endsWith('/api/v1/presets'));
    const sent = save?.[1]?.body;
    const body = JSON.parse(typeof sent === 'string' ? sent : '{}') as Record<string, unknown>;
    expect(body.versionPolicy).toBe('pinned');
    expect(body.pinnedRecipes).toEqual({ 'contraption-alpha': '1.4.0', base: '1.0.0' });
  });

  it('keeps a row, which is how §10 exempts it from the sweep', async () => {
    stubApi([aGeneration()]);
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Keep' }));

    await waitFor(() => expect(calls().some((url) => url.includes('/keep'))).toBe(true));
  });
});
