import { ProblemDetail } from '@/errors/ProblemDetail';
import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ApiError, createShareLink, type GenerateRequest } from '@/lib/api';

/**
 * Share this configuration (§8, §9).
 *
 * The URL comes first and the token second, and that ordering is the whole design rather than a
 * layout choice. A URL carries the selection itself, so it works in a chat message somebody opens
 * next year, in a commit message, in a ticket — and it works when this service is down. A token is
 * a row, and a row expires.
 *
 * So the short link is offered only when the URL is long enough to be awkward, which is the case
 * §8 says tokens exist for.
 */
export function ShareDialog({
  envelope,
  onClose,
}: {
  envelope: GenerateRequest;
  onClose: () => void;
}) {
  const url = window.location.href;
  /** Long enough that pasting it into a chat message starts to hurt, judged by eye rather than spec. */
  const unwieldy = url.length > 180;
  const [copied, setCopied] = useState<'url' | 'token' | null>(null);

  const mint = useMutation({
    mutationFn: () => createShareLink(envelope),
  });

  const tokenUrl = mint.data?.token ? `${window.location.origin}/s/${mint.data.token}` : null;
  const failure = mint.error instanceof ApiError ? mint.error : null;

  async function copy(value: string, which: 'url' | 'token') {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(which);
    } catch {
      // A browser that refuses clipboard access still shows the value in a field to select.
      setCopied(null);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
      aria-label="Share this configuration"
      data-testid="share-dialog"
    >
      <div className="flex w-full max-w-lg flex-col gap-4 rounded-lg border bg-background p-6">
        <div>
          <h2 className="text-lg font-semibold">Share this configuration</h2>
          <p className="text-sm text-muted-foreground">
            The link carries the selection itself, so it keeps working whatever happens here.
          </p>
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="share-url">Link</Label>
          <div className="flex items-center gap-2">
            <Input id="share-url" readOnly value={url} onFocus={(event) => event.target.select()} />
            <Button variant="outline" onClick={() => void copy(url, 'url')}>
              {copied === 'url' ? 'Copied' : 'Copy'}
            </Button>
          </div>
        </div>

        {unwieldy && (
          <div className="flex flex-col gap-1.5 border-t pt-4">
            <Label htmlFor="share-token">Short link</Label>
            <p className="text-xs text-muted-foreground">
              That URL is long. A short link is stored here and expires in 30 days — the URL above
              does not.
            </p>
            {tokenUrl ? (
              <div className="flex items-center gap-2">
                <Input
                  id="share-token"
                  readOnly
                  value={tokenUrl}
                  onFocus={(event) => event.target.select()}
                />
                <Button variant="outline" onClick={() => void copy(tokenUrl, 'token')}>
                  {copied === 'token' ? 'Copied' : 'Copy'}
                </Button>
              </div>
            ) : (
              <Button
                variant="outline"
                disabled={mint.isPending}
                onClick={() => mint.mutate()}
                className="self-start"
              >
                {mint.isPending ? 'Making a short link…' : 'Make a short link'}
              </Button>
            )}
          </div>
        )}

        {failure && <ProblemDetail error={failure} className="text-sm" />}

        <div className="flex justify-end">
          <Button variant="outline" onClick={onClose}>
            Done
          </Button>
        </div>
      </div>
    </div>
  );
}
