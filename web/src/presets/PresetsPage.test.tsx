import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setAccessToken } from '@/auth/token';
import { PresetsPage } from '@/presets/PresetsPage';

/**
 * The landing page, tested against presets whose recipes this file has never heard of.
 *
 * Same discipline as the wizard's tests (§8): if the page renders invented recipe ids and an
 * invented version policy correctly, it really is rendering what the server sent rather than
 * something it knows.
 */

function aPreset(overrides: Record<string, unknown> = {}) {
  return {
    id: '11111111-2222-3333-4444-555555555555',
    name: 'our standard service',
    description: 'What we start every service from',
    visibility: 'team',
    versionPolicy: 'track_latest',
    pinnedRecipes: {},
    revision: 1,
    mine: true,
    recipeIds: ['contraption-alpha', 'flourish-deluxe'],
    staleReason: null,
    selection: {
      schemaVersion: 1,
      projectName: 'billing',
      options: { contraption: 'contraption-alpha' },
      variables: { tint: 'blue' },
    },
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-01T00:00:00Z',
    ...overrides,
  };
}

function stubApi(presets: unknown[], generate?: () => Promise<Response>) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (String(url).endsWith('/generate')) {
        return (generate ?? (() => Promise.resolve(new Response(new Blob(['zip'])))))();
      }
      return Promise.resolve(
        new Response(JSON.stringify(presets), {
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
        <PresetsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('PresetsPage', () => {
  beforeEach(() => {
    setAccessToken('a-token');
    URL.createObjectURL = vi.fn(() => 'blob:generated');
    URL.revokeObjectURL = vi.fn();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('lists saved stacks, with the version policy shown rather than implied', async () => {
    stubApi([aPreset(), aPreset({ id: 'other', name: 'pinned one', versionPolicy: 'pinned' })]);
    renderPage();

    expect(await screen.findByText('our standard service')).toBeInTheDocument();
    // §7: whether a preset follows the catalog is the decision this page exists to make visible.
    expect(screen.getByText('tracks latest')).toBeInTheDocument();
    expect(screen.getByText('pinned')).toBeInTheDocument();
  });

  it('generates in one click, which is what the list is arranged around', async () => {
    const clicked = vi.fn();
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(clicked);
    stubApi([aPreset()]);
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Generate' }));

    await waitFor(() => expect(clicked).toHaveBeenCalled());
    const generate = vi
      .mocked(fetch)
      .mock.calls.find(([url]) => typeof url === 'string' && url.endsWith('/generate'));
    // Nothing but the id: the selection is resolved now, which is what tracking latest means.
    expect(generate?.[0]).toContain(
      '/api/v1/presets/11111111-2222-3333-4444-555555555555/generate',
    );
  });

  /**
   * §23 asks for staleness to be reported at load rather than during render, and this is what
   * that looks like to a user: the reason on the card, and the button that would fail disabled.
   */
  it('shows why a stale preset cannot be generated, and refuses to try', async () => {
    stubApi([
      aPreset({
        staleReason: 'This preset selects contraption-alpha, which is no longer in the catalog.',
      }),
    ]);
    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('no longer in the catalog');
    expect(screen.getByRole('button', { name: 'Generate' })).toBeDisabled();
  });

  it('offers Delete only on presets the signed-in user owns', async () => {
    stubApi([aPreset({ mine: false })]);
    renderPage();

    await screen.findByText('our standard service');
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
  });

  it('encodes a preset back into a wizard URL, rather than inventing a second format', async () => {
    stubApi([aPreset()]);
    renderPage();

    const link = await screen.findByRole('link', { name: 'Open in the wizard' });
    // §9 makes the URL the configuration; opening a preset is that encoding, nothing more.
    expect(link).toHaveAttribute(
      'href',
      '/new?contraption=contraption-alpha&projectName=billing&tint=blue',
    );
  });

  it('says what to do when there are no presets yet', async () => {
    stubApi([]);
    renderPage();

    expect(await screen.findByText('Nothing saved yet')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Start a new project' })[0]).toBeInTheDocument();
  });

  /**
   * A deployment without a database serves the generator and nothing else (§10, §12). That is a
   * normal state, not a broken one, so the page says so and still offers the thing that works.
   */
  it('stays useful when presets are not reachable at all', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ detail: 'No handler for this request.' }), { status: 404 }),
        ),
      ),
    );
    renderPage();

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    // The generator half needs no database, so the way out is still offered.
    expect(screen.getByRole('link', { name: 'Start a new project' })).toHaveAttribute(
      'href',
      '/new',
    );
  });
});
