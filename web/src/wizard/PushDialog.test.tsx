import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setAccessToken } from '@/auth/token';
import { PushDialog } from '@/wizard/PushDialog';

/**
 * §46's two claims, checked from the client side.
 *
 * The commit is checked on the server, where it can be — `PushedCommitTest` pushes to a real
 * repository and compares the sha. What can only be checked here is what this dialog does with a
 * credential, and what it says when half of a push worked.
 */

const ENVELOPE = {
  schemaVersion: 1,
  projectName: 'billing',
  options: { contraption: 'contraption-alpha' },
  variables: {},
};

function renderDialog(onClose = () => {}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <PushDialog envelope={ENVELOPE} onClose={onClose} />
    </QueryClientProvider>,
  );
}

function answerWith(body: unknown, status = 200) {
  // The parameters are declared so `mock.calls` is typed: the body of the request is the
  // assertion this file exists for.
  const fetch = vi.fn<(path: string, init: RequestInit) => Promise<Response>>(() =>
    Promise.resolve(
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  );
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

async function fillIn(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Group'), 'acme/platform');
  await user.type(screen.getByLabelText('Access token'), 'glpat-secret');
}

describe('PushDialog', () => {
  beforeEach(() => {
    setAccessToken('a-token');
    localStorage.clear();
    sessionStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('defaults the project name to the project name', () => {
    answerWith({});
    renderDialog();
    expect(screen.getByLabelText('Project name')).toHaveValue('billing');
  });

  it('will not push until it has a group, a name and a token', async () => {
    const user = userEvent.setup();
    answerWith({});
    renderDialog();

    const button = screen.getByRole('button', { name: 'Create and push' });
    expect(button).toBeDisabled();

    await user.type(screen.getByLabelText('Group'), 'acme/platform');
    expect(button).toBeDisabled();

    await user.type(screen.getByLabelText('Access token'), 'glpat-secret');
    expect(button).toBeEnabled();
  });

  /**
   * The one that matters. A token in `localStorage` is a token that outlives the tab, survives a
   * shared machine and is readable by anything that gets script execution on this origin — and
   * this one creates repositories.
   */
  it('sends the token and keeps no copy of it', async () => {
    const user = userEvent.setup();
    const fetch = answerWith({
      projectUrl: 'https://gitlab.example.com/acme/platform/billing',
      path: 'acme/platform/billing',
      commitId: '4f9a2c1d8e7b6a5f4e3d2c1b0a9f8e7d6c5b4a39',
      selectionHash: 'sha256:deadbeef',
      partial: false,
      detail: null,
    });
    renderDialog();

    await fillIn(user);
    await user.click(screen.getByRole('button', { name: 'Create and push' }));
    await screen.findByTestId('push-result');

    expect(fetch.mock.calls).toHaveLength(1);
    const init = fetch.mock.calls[0]![1];
    expect(JSON.parse(init.body as string)).toMatchObject({
      group: 'acme/platform',
      projectName: 'billing',
      token: 'glpat-secret',
      selection: { projectName: 'billing' },
    });

    expect(JSON.stringify(localStorage)).not.toContain('glpat-secret');
    expect(JSON.stringify(sessionStorage)).not.toContain('glpat-secret');
    // Nor left in the field for the next person at this desk.
    expect(document.body.innerHTML).not.toContain('glpat-secret');
  });

  it('shows where it went, and the commit that says it is the same build', async () => {
    const user = userEvent.setup();
    answerWith({
      projectUrl: 'https://gitlab.example.com/acme/platform/billing',
      path: 'acme/platform/billing',
      commitId: '4f9a2c1d8e7b6a5f4e3d2c1b0a9f8e7d6c5b4a39',
      selectionHash: 'sha256:deadbeef',
      partial: false,
      detail: null,
    });
    renderDialog();

    await fillIn(user);
    await user.click(screen.getByRole('button', { name: 'Create and push' }));

    const link = await screen.findByTestId('push-project-link');
    expect(link).toHaveTextContent('acme/platform/billing');
    expect(link).toHaveAttribute('href', 'https://gitlab.example.com/acme/platform/billing');
    expect(await screen.findByText('4f9a2c1d8e7b')).toBeInTheDocument();
  });

  /**
   * §46: a partial push is reported as exactly that, with the created project named. A generic
   * failure would leave somebody with an empty repository and no idea where it came from — and the
   * obvious response to a generic failure, pressing the button again, fails with PROJECT_EXISTS.
   */
  it('names the project a partial push left behind', async () => {
    const user = userEvent.setup();
    answerWith(
      {
        title: 'Created, but not pushed',
        status: 502,
        error: 'PUSH_PARTIAL',
        projectUrl: 'https://gitlab.example.com/acme/platform/billing',
        path: 'acme/platform/billing',
        detail: 'The project was created and the code did not reach it: push rejected.',
        hint: 'The project is at https://gitlab.example.com/acme/platform/billing and is empty.',
      },
      502,
    );
    renderDialog();

    await fillIn(user);
    await user.click(screen.getByRole('button', { name: 'Create and push' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The project was created and the code did not reach it');
    const link = within(alert).getByRole('link', { name: 'acme/platform/billing' });
    expect(link).toHaveAttribute('href', 'https://gitlab.example.com/acme/platform/billing');
  });

  it('renders a refusal verbatim, hint and all', async () => {
    const user = userEvent.setup();
    answerWith(
      {
        title: 'GitLab refused',
        status: 422,
        error: 'PROJECT_EXISTS',
        detail: "A project called 'billing' already exists in that group.",
        hint: 'Pick another name, or push to the existing project yourself.',
      },
      422,
    );
    renderDialog();

    await fillIn(user);
    await user.click(screen.getByRole('button', { name: 'Create and push' }));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(
        "A project called 'billing' already exists in that group.",
      );
    });
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Pick another name, or push to the existing project yourself.',
    );
  });
});
