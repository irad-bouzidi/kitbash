import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setAccessToken } from '@/auth/token';
import { ShareDialog } from '@/wizard/ShareDialog';

/**
 * §8's ordering, which is the whole of this dialog: the URL is the mechanism, the token is the
 * fallback. A dialog that offered the short link first would quietly make every shared
 * configuration depend on a row that expires.
 */

const ENVELOPE = {
  schemaVersion: 1,
  projectName: 'billing',
  options: { contraption: 'contraption-alpha' },
  variables: {},
};

function renderDialog() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ShareDialog envelope={ENVELOPE} onClose={() => {}} />
    </QueryClientProvider>,
  );
}

function setUrl(url: string) {
  window.history.replaceState(null, '', url);
}

describe('ShareDialog', () => {
  /** Held here rather than read back off `navigator`, which lints as an unbound method. */
  let writeText: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    setAccessToken('a-token');
    writeText = vi.fn(() => Promise.resolve());
    Object.assign(navigator, { clipboard: { writeText } });
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({ token: 'abcdefghjkm', expiresAt: '2026-10-20T00:00:00Z' }),
            {
              status: 201,
              headers: { 'Content-Type': 'application/json' },
            },
          ),
        ),
      ),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    setUrl('/');
  });

  it('offers the URL, which carries the selection and needs nothing stored', async () => {
    setUrl('/new?contraption=contraption-alpha');
    renderDialog();

    expect(screen.getByLabelText('Link')).toHaveValue(
      `${window.location.origin}/new?contraption=contraption-alpha`,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Copy' }));
    expect(writeText).toHaveBeenCalledWith(
      `${window.location.origin}/new?contraption=contraption-alpha`,
    );
  });

  /**
   * §8: tokens exist for when the URL gets unwieldy. Offering one for a short URL would trade a
   * link that never expires for one that does, in exchange for nothing.
   */
  it('does not offer a short link when the URL is already short', () => {
    setUrl('/new?contraption=contraption-alpha');
    renderDialog();

    expect(screen.queryByRole('button', { name: 'Make a short link' })).not.toBeInTheDocument();
    expect(vi.mocked(fetch)).not.toHaveBeenCalled();
  });

  it('offers one when the URL is long, and says what the trade is', async () => {
    setUrl(`/new?${'contraption=contraption-alpha&'.repeat(10)}tint=blue`);
    renderDialog();

    expect(screen.getByText(/expires in 30 days/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Make a short link' }));

    await waitFor(() =>
      expect(screen.getByLabelText('Short link')).toHaveValue(
        `${window.location.origin}/s/abcdefghjkm`,
      ),
    );
  });

  it('nothing is stored until the short link is actually asked for', async () => {
    setUrl(`/new?${'contraption=contraption-alpha&'.repeat(10)}tint=blue`);
    renderDialog();

    expect(vi.mocked(fetch)).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: 'Make a short link' }));

    await waitFor(() => expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1));
    const [requested] = vi.mocked(fetch).mock.calls[0] ?? [];
    expect(typeof requested === 'string' ? requested : '').toBe('/api/v1/share');
  });
});
