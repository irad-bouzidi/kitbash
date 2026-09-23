import { act, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { VerifyDialog } from '@/verify/VerifyDialog';
import { BuildLog } from '@/verify/BuildLog';
import { setAccessToken } from '@/auth/token';

/**
 * The three things §38 asks the dialog to make legible.
 *
 * An instant answer must not look like a bug, a slow one must not look broken, and a full queue
 * must say how full rather than "something went wrong". All three are about a user's model of what
 * the machine is doing, which is why they are asserted rather than left to the styling.
 */

const ENVELOPE = { schemaVersion: 1, projectName: 'thing', options: {}, variables: {} };

function json(body: unknown, status = 200) {
  return Promise.resolve(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
}

describe('VerifyDialog', () => {
  beforeEach(() => setAccessToken(null));

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('says so when the answer came from an earlier run rather than a fresh build', async () => {
    // 200, not 202: the server already had this exact selection against this catalog.
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        json({
          id: 'run-1',
          status: 'passed',
          log: 'all green',
          startedAt: null,
          finishedAt: null,
        }),
      ),
    );

    act(() => {
      render(<VerifyDialog envelope={ENVELOPE} onClose={() => {}} />);
    });

    await waitFor(() => expect(screen.getByTestId('verify-status')).toBeInTheDocument());
    expect(screen.getByTestId('verify-status')).toHaveTextContent('nothing was rebuilt');
    expect(screen.getByTestId('build-log')).toHaveTextContent('all green');
  });

  it('polls a started run until it finishes, then shows the log', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let polls = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) => {
        if (init?.method === 'POST') {
          return Promise.resolve(
            new Response(JSON.stringify({ id: 'run-2', status: 'pending' }), {
              // 202 — a container is starting, so the client has to poll.
              status: 202,
              headers: { 'Content-Type': 'application/json' },
            }),
          );
        }
        polls += 1;
        return json({
          id: 'run-2',
          status: polls > 1 ? 'failed' : 'running',
          log: polls > 1 ? 'BUILD FAILED\nsomething broke' : null,
        });
      }),
    );

    act(() => {
      render(<VerifyDialog envelope={ENVELOPE} onClose={() => {}} />);
    });

    // Inside act, because the poll resolves into a state update and React warns about one
    // that happens outside it — a warning that would go on to mask a real one.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    await waitFor(() => expect(screen.getByTestId('verify-status')).toBeInTheDocument());

    expect(screen.getByTestId('verify-status')).toHaveTextContent('did not build');
    expect(screen.getByTestId('verify-status')).toHaveTextContent('Built just now');
    expect(screen.getByTestId('build-log')).toHaveTextContent('BUILD FAILED');
    vi.useRealTimers();
  });

  it('renders a full queue with the depth, not a generic error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        json(
          {
            detail: 'Every verification worker is busy and the queue is full.',
            error: 'VERIFY_QUEUE_FULL',
            queueDepth: 7,
            hint: 'Try again in a few minutes.',
          },
          429,
        ),
      ),
    );

    act(() => {
      render(<VerifyDialog envelope={ENVELOPE} onClose={() => {}} />);
    });

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('7 builds are waiting');
    expect(alert).toHaveTextContent('Try again in a few minutes.');
  });
});

describe('BuildLog', () => {
  it('renders the end of a long log, because that is where the answer is', () => {
    const lines = Array.from({ length: 5000 }, (_, index) => `line ${index}`);
    render(<BuildLog text={lines.join('\n')} />);

    const log = screen.getByTestId('build-log');
    expect(log).toHaveTextContent('line 4999');
    // A failing Gradle log is tens of thousands of lines and rendering it wholesale janks the tab.
    expect(log).not.toHaveTextContent('line 10');
    expect(screen.getByText(/earlier lines not shown/)).toBeInTheDocument();
  });
});
