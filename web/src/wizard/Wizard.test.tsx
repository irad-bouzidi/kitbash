import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Wizard } from '@/wizard/Wizard';
import { useSelectionStore } from '@/wizard/useSelection';

/**
 * The wizard is tested against a catalog it has never seen.
 *
 * That is the point of §8 and the whole reason this page exists in this shape: if these tests
 * pass with an invented catalog full of invented option ids, the page really does render itself
 * from the document, and adding a recipe really is a backend-only change.
 */

const METADATA = {
  schemaVersion: 1,
  catalogDigest: 'sha256:abc123',
  recipeCount: 2,
  groups: [
    {
      id: 'made-up-group',
      label: 'Made-up group',
      help: 'Nothing in the real catalog is called this.',
      order: 1,
      options: [
        {
          id: 'contraption',
          type: 'enum',
          label: 'Contraption',
          help: 'Which contraption to use.',
          required: false,
          defaultValue: null,
          availableWhen: null,
          choices: [
            {
              value: 'contraption-alpha',
              label: 'Alpha',
              recipeId: 'contraption-alpha',
              recipeVersion: '1.0.0',
              frameworkVersion: '9.9',
              provides: [],
              requires: [],
              conflictsWith: [],
              verification: null,
            },
          ],
        },
        {
          id: 'flourish',
          type: 'boolean',
          label: 'Flourish',
          help: 'Adds a flourish.',
          required: false,
          defaultValue: true,
          availableWhen: null,
          choices: [],
        },
        {
          id: 'tint',
          type: 'enum',
          label: 'Tint',
          help: 'Only applies to Alpha.',
          required: false,
          defaultValue: 'pale',
          availableWhen: 'contraption-alpha',
          choices: [{ value: 'pale', label: 'pale' }],
        },
      ],
    },
  ],
  variables: [
    {
      id: 'thingName',
      label: 'Thing name',
      help: 'What the thing is called.',
      pattern: '^[a-z-]+$',
      defaultValue: 'a-thing',
      scope: 'envelope',
      requiredBy: [],
    },
  ],
  recipes: [],
};

function emptyResolution(overrides: Record<string, unknown> = {}) {
  return {
    valid: true,
    catalogDigest: 'sha256:abc123',
    selectionHash: null,
    recipes: [],
    capabilities: [],
    effectiveOptions: {},
    conflicts: [],
    warnings: [],
    ...overrides,
  };
}

let validateBody: unknown;

function stubApi(resolution: unknown = emptyResolution()) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (String(url).endsWith('/metadata')) {
        return Promise.resolve(
          new Response(JSON.stringify(METADATA), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        );
      }
      validateBody = JSON.parse((init?.body as string) ?? '{}');
      return Promise.resolve(
        new Response(JSON.stringify(resolution), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
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

describe('Wizard', () => {
  beforeEach(() => {
    useSelectionStore.setState({ values: {}, ready: false });
    window.history.replaceState(null, '', '/');
    validateBody = undefined;
    stubApi();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('renders a catalog it has never seen, groups and all', async () => {
    renderWizard();

    expect(await screen.findByText('Made-up group')).toBeInTheDocument();
    expect(screen.getByLabelText('Contraption')).toBeInTheDocument();
    expect(screen.getByLabelText('Flourish')).toBeInTheDocument();
    expect(screen.getByLabelText('Thing name')).toBeInTheDocument();
    // Every human-readable string comes from the document (§8).
    expect(screen.getByText('Which contraption to use.')).toBeInTheDocument();
  });

  it('renders one control per option type, chosen by type and not by id', async () => {
    renderWizard();

    expect(await screen.findByLabelText('Contraption')).toHaveProperty('tagName', 'SELECT');
    expect(screen.getByLabelText('Flourish')).toHaveProperty('type', 'checkbox');
    expect(screen.getByLabelText('Thing name')).toHaveProperty('type', 'text');
  });

  it('applies the catalog defaults, including a toggle that starts on', async () => {
    renderWizard();

    expect(await screen.findByLabelText('Flourish')).toBeChecked();
    expect(screen.getByLabelText('Thing name')).toHaveValue('a-thing');
  });

  it('keeps an option that does not apply visible and disabled, with the reason', async () => {
    // §9: hiding it makes the catalog feel arbitrary — a user who cannot see an option cannot
    // tell whether it exists.
    renderWizard();

    const tint = await screen.findByLabelText('Tint');
    expect(tint).toBeDisabled();
    expect(tint).toHaveAttribute('title', expect.stringContaining('Applies when'));
  });

  it('syncs the selection to the URL, so a link captures a configuration', async () => {
    const user = userEvent.setup();
    renderWizard();

    await user.selectOptions(await screen.findByLabelText('Contraption'), 'contraption-alpha');

    await waitFor(() => expect(window.location.search).toContain('contraption=contraption-alpha'));
  });

  it('reads the selection back out of the URL on load', async () => {
    window.history.replaceState(null, '', '/?contraption=contraption-alpha&flourish=false');

    renderWizard();

    expect(await screen.findByLabelText('Contraption')).toHaveValue('contraption-alpha');
    expect(screen.getByLabelText('Flourish')).not.toBeChecked();
  });

  it('posts the §7 envelope to /validate, with the envelope-scoped variable in its own field', async () => {
    const user = userEvent.setup();
    renderWizard();

    await user.selectOptions(await screen.findByLabelText('Contraption'), 'contraption-alpha');

    await waitFor(() => expect(validateBody).toBeDefined());
    expect(validateBody).toMatchObject({
      schemaVersion: 1,
      projectName: 'a-thing',
      options: { contraption: 'contraption-alpha', flourish: true },
    });
  });

  it('renders a conflict inline on the field that caused it, never as a banner', async () => {
    stubApi(
      emptyResolution({
        valid: false,
        conflicts: [
          {
            code: 'CAPABILITY_UNSATISFIED',
            stage: 'resolve',
            optionId: 'contraption',
            recipe: null,
            message: 'Needs a contraption.',
            hint: 'Choose one.',
          },
        ],
      }),
    );
    renderWizard();
    await screen.findByLabelText('Contraption');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Needs a contraption.');
    expect(alert).toHaveTextContent('Choose one.');
    // The message sits next to its control, not at the top of the page.
    expect(screen.getByLabelText('Contraption').closest('div')).toContainElement(alert);
  });

  it('blocks Generate until something is actually selected', async () => {
    // An empty selection produces an empty zip, which looks like the generator worked. The
    // server refuses it too (§14); this stops the user getting that far.
    renderWizard();

    const generate = await screen.findByRole('button', { name: 'Generate' });
    await waitFor(() => expect(generate).toBeDisabled());
    expect(generate).toHaveAttribute('title', expect.stringContaining('at least one'));
  });

  it('blocks Generate on a value that breaks the rule the catalog ships', async () => {
    const user = userEvent.setup();
    stubApi(
      emptyResolution({
        recipes: [
          {
            id: 'contraption-alpha',
            label: 'Alpha',
            kind: 'gadget',
            recipeVersion: '1.0.0',
            frameworkVersion: '9.9',
            implied: false,
          },
        ],
      }),
    );
    renderWizard();

    const name = await screen.findByLabelText('Thing name');
    await user.clear(name);
    await user.type(name, 'Not A Thing');

    // The pattern comes from the catalog, so a new variable arrives validated without an edit.
    expect(await screen.findByText(/Does not match/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Generate' })).toBeDisabled());
  });

  it('blocks Generate while a conflict is outstanding', async () => {
    stubApi(
      emptyResolution({
        valid: false,
        conflicts: [
          {
            code: 'CONFLICT',
            stage: 'resolve',
            optionId: 'contraption',
            recipe: null,
            message: 'No.',
            hint: 'Pick another.',
          },
        ],
      }),
    );
    renderWizard();

    await waitFor(() => expect(screen.getByRole('button', { name: 'Generate' })).toBeDisabled());
  });

  it('shows the resolved stack, marking what the resolver added', async () => {
    stubApi(
      emptyResolution({
        recipes: [
          {
            id: 'contraption-alpha',
            label: 'Alpha',
            kind: 'gadget',
            recipeVersion: '1.0.0',
            frameworkVersion: '9.9',
            implied: false,
          },
          {
            id: 'scaffold',
            label: 'Scaffold',
            kind: 'gizmo',
            recipeVersion: '1.0.0',
            frameworkVersion: null,
            implied: true,
          },
        ],
      }),
    );
    renderWizard();

    const rail = await screen.findByTestId('stack-summary');
    // The rail fills when the debounced /validate answers, not when the catalog arrives.
    expect(await within(rail).findByText('Alpha')).toBeInTheDocument();
    // §9: the right rail answers "what am I getting?", not "what did I click?".
    expect(within(rail).getByText('added for you')).toBeInTheDocument();
  });

  it('downloads through a real form submission, so the browser shows native progress', async () => {
    renderWizard();
    await screen.findByLabelText('Contraption');

    const form = screen.getByTestId('download-form');
    expect(form).toHaveProperty('method', 'post');
    expect(form.getAttribute('action')).toBe('/api/v1/generate');
    expect(form.querySelector('input[name="selection"]')).not.toBeNull();
  });

  it('shows the catalog digest, which is what makes a bug report actionable', async () => {
    renderWizard();

    expect(await screen.findByText(/sha256:abc123/)).toBeInTheDocument();
  });
});
