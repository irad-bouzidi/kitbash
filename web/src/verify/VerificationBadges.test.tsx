import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Wizard } from '@/wizard/Wizard';
import { setAccessToken } from '@/auth/token';
import { useSelectionStore } from '@/wizard/useSelection';

/**
 * Badges, against a catalog the wizard has never seen.
 *
 * Invented option ids on purpose, like every other wizard test: if a badge lands on the right
 * control here, it does so because the matrix said `gizmo=brass & finish=matte` and not because
 * anything in React knows what a gizmo is. §9's rule survives the feature or the feature is wrong.
 */

const METADATA = {
  schemaVersion: 1,
  catalogDigest: 'sha256:abc123',
  recipeCount: 2,
  groups: [
    {
      id: 'parts',
      label: 'Parts',
      help: null,
      order: 1,
      options: [
        {
          id: 'gizmo',
          type: 'enum',
          label: 'Gizmo',
          help: 'Which gizmo.',
          required: false,
          defaultValue: null,
          availableWhen: [],
          choices: [
            { value: 'brass', label: 'Brass' },
            { value: 'steel', label: 'Steel' },
          ],
        },
        {
          id: 'finish',
          type: 'enum',
          label: 'Finish',
          help: 'How it is finished.',
          required: false,
          defaultValue: null,
          availableWhen: [],
          choices: [
            { value: 'matte', label: 'Matte' },
            { value: 'gloss', label: 'Gloss' },
          ],
        },
      ],
    },
  ],
  variables: [],
  recipes: [],
};

/** Brass with a matte finish is red; each half on its own is green in some other combination. */
const VERIFICATION = {
  schemaVersion: 1,
  catalogDigest: 'sha256:abc123',
  generatedAt: '2026-09-23T02:00:00Z',
  staleFor: null,
  choices: [
    {
      key: 'gizmo=brass',
      status: 'failed',
      passed: 1,
      failed: 1,
      cell: 'red',
      failedStep: 'pnpm typecheck',
    },
    {
      key: 'finish=matte',
      status: 'failed',
      passed: 1,
      failed: 1,
      cell: 'red',
      failedStep: 'pnpm typecheck',
    },
    { key: 'gizmo=steel', status: 'passed', passed: 2, failed: 0, cell: null, failedStep: null },
    { key: 'finish=gloss', status: 'passed', passed: 2, failed: 0, cell: null, failedStep: null },
  ],
  pairs: [
    {
      key: 'finish=matte & gizmo=brass',
      status: 'failed',
      passed: 0,
      failed: 1,
      cell: 'red-cell',
      failedStep: 'pnpm typecheck',
    },
    {
      key: 'finish=gloss & gizmo=brass',
      status: 'passed',
      passed: 1,
      failed: 0,
      cell: null,
      failedStep: null,
    },
  ],
};

const RESOLUTION = {
  valid: true,
  catalogDigest: 'sha256:abc123',
  selectionHash: null,
  recipes: [],
  capabilities: [],
  effectiveOptions: {},
  conflicts: [],
  warnings: [],
};

function json(body: unknown, status = 200) {
  return Promise.resolve(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
}

function stubApi(verification: unknown = VERIFICATION) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      const path = String(url);
      if (path.endsWith('/metadata')) return json(METADATA);
      if (path.endsWith('/verification')) return json(verification);
      return json(RESOLUTION);
    }),
  );
}

function renderWizard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <Wizard />
    </QueryClientProvider>,
  );
}

describe('verification badges', () => {
  beforeEach(() => {
    useSelectionStore.setState({ values: {}, ready: false });
    window.history.replaceState(null, '', '/');
    setAccessToken(null);
    stubApi();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('warns at the pairing, and only once both halves of it are chosen', async () => {
    renderWizard();
    const user = userEvent.setup();

    await user.selectOptions(await screen.findByLabelText('Gizmo'), 'brass');

    // One half of a red pairing is not a red choice. §36's lesson in miniature: Kotlin was not
    // broken, Kotlin *with the typed client* was, and warning on the first dropdown would be a
    // warning the user cannot act on and that is not true yet.
    expect(screen.queryByTestId('pairing-warning')).toBeNull();

    await user.selectOptions(await screen.findByLabelText('Finish'), 'matte');

    await waitFor(() => {
      expect(screen.getAllByTestId('pairing-warning').length).toBeGreaterThan(0);
    });
    expect(screen.getAllByTestId('pairing-warning')[0]).toHaveTextContent('pnpm typecheck');
  });

  it('does not warn about a pairing the matrix built', async () => {
    renderWizard();
    const user = userEvent.setup();

    await user.selectOptions(await screen.findByLabelText('Gizmo'), 'brass');
    await user.selectOptions(await screen.findByLabelText('Finish'), 'gloss');

    await waitFor(() => {
      expect(screen.getAllByTestId('verified-line').length).toBeGreaterThan(0);
    });
    expect(screen.queryByTestId('pairing-warning')).toBeNull();
  });

  it('a green badge carries the date it was verified, because one without is a claim', async () => {
    renderWizard();

    await userEvent.setup().selectOptions(await screen.findByLabelText('Gizmo'), 'steel');

    await waitFor(() => {
      expect(screen.getAllByTestId('verified-line')[0]).toHaveTextContent(/Verified/);
    });
  });

  it('shows "not verified" rather than nothing when the matrix has never built a choice', async () => {
    stubApi({
      schemaVersion: 1,
      catalogDigest: 'sha256:abc123',
      generatedAt: null,
      staleFor: null,
      choices: [],
      pairs: [],
    });
    renderWizard();

    await userEvent.setup().selectOptions(await screen.findByLabelText('Gizmo'), 'brass');

    await waitFor(() => {
      expect(screen.getAllByTestId('verified-line')[0]).toHaveTextContent('Not verified');
    });
  });

  it('offers the failing cell log, and fetches it only when asked', async () => {
    const fetches: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        const path = String(url);
        fetches.push(path);
        if (path.endsWith('/metadata')) return json(METADATA);
        if (path.endsWith('/verification')) return json(VERIFICATION);
        if (path.includes('/verification/cells/')) {
          return Promise.resolve(
            new Response('BUILD FAILED\nthe line that matters', { status: 200 }),
          );
        }
        return json(RESOLUTION);
      }),
    );

    renderWizard();
    const user = userEvent.setup();
    await user.selectOptions(await screen.findByLabelText('Gizmo'), 'brass');
    await user.selectOptions(await screen.findByLabelText('Finish'), 'matte');

    // Two links, because a pairing has two ends and the warning sits on both controls: whichever
    // dropdown the user is looking at when they set the second half, the warning is there.
    const links = await screen.findAllByTestId('cell-log-link');
    expect(links).toHaveLength(2);
    const link = links[0]!;
    // A build log is the largest thing this API serves, and three red pairings would be three of
    // them — so nothing is fetched until somebody asks.
    expect(fetches.some((path) => path.includes('/verification/cells/'))).toBe(false);

    await user.click(link);

    await waitFor(() => {
      expect(screen.getByTestId('build-log')).toHaveTextContent('the line that matters');
    });
  });

  it('renders without badges when the verification document is unavailable', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        const path = String(url);
        if (path.endsWith('/metadata')) return json(METADATA);
        if (path.endsWith('/verification')) return json({ detail: 'nope' }, 500);
        return json(RESOLUTION);
      }),
    );
    renderWizard();

    // Badges are information about choices, not the choices themselves. A wizard that refused to
    // render because the nightly had not published would be worse than one with no badges.
    expect(await screen.findByLabelText('Gizmo')).toBeInTheDocument();
  });
});
