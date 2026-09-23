import { useEffect, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import {
  ApiError,
  fetchVerificationRun,
  requestVerification,
  type GenerateRequest,
  type VerificationRun,
} from '@/lib/api';
import { ProblemDetail } from '@/errors/ProblemDetail';
import { BuildLog } from '@/verify/BuildLog';

/**
 * "Verify this build" (§12, §38).
 *
 * The difference between the two ways this ends is the point. A selection somebody has already
 * verified against this catalog comes back **instantly**, because the server answers 200 with the
 * finished run; a combination nobody has tried starts a container and takes minutes. §38 asks for
 * those to be visibly distinct, and the reason is plain: without it the fast answer looks like a
 * bug and the slow one looks broken.
 */
export function VerifyDialog({
  envelope,
  onClose,
}: {
  envelope: GenerateRequest;
  onClose: () => void;
}) {
  const [run, setRun] = useState<VerificationRun | null>(null);
  const [instant, setInstant] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const cancelled = useRef(false);

  useEffect(() => {
    cancelled.current = false;
    requestVerification(envelope)
      .then((claim) => {
        if (cancelled.current) return;
        setRun(claim.run);
        setInstant(!claim.started);
        if (claim.started) void poll(claim.run.id ?? '');
      })
      .catch((failure: unknown) => {
        if (!cancelled.current) setError(failure instanceof ApiError ? failure : null);
      });

    return () => {
      cancelled.current = true;
    };

    /**
     * The client's own loop.
     *
     * Two seconds, not two hundred milliseconds: a build takes minutes, and a poll fast enough to
     * feel responsive would be thousands of requests for a number that changes twice.
     */
    async function poll(id: string) {
      while (!cancelled.current) {
        await new Promise((resolve) => setTimeout(resolve, 2000));
        if (cancelled.current) return;
        try {
          const latest = await fetchVerificationRun(id);
          if (cancelled.current) return;
          setRun(latest);
          if (latest.status !== 'pending' && latest.status !== 'running') return;
        } catch (failure: unknown) {
          if (!cancelled.current) setError(failure instanceof ApiError ? failure : null);
          return;
        }
      }
    }
  }, [envelope]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
      aria-label="Verify this build"
      data-testid="verify-dialog"
    >
      <div className="flex h-[80vh] w-full max-w-4xl flex-col gap-4 rounded-lg border bg-background p-6">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 className="text-lg font-semibold">Verify this build</h2>
            <Status run={run} instant={instant} error={error} />
          </div>
          <Button variant="outline" onClick={onClose}>
            Close
          </Button>
        </div>

        {error && <Refusal error={error} />}

        {run?.log ? (
          <BuildLog text={run.log} />
        ) : (
          !error && (
            <p className="text-sm text-muted-foreground">
              {run?.status === 'running' || run?.status === 'pending'
                ? 'The log appears when the build finishes.'
                : 'Asking…'}
            </p>
          )
        )}
      </div>
    </div>
  );
}

function Status({
  run,
  instant,
  error,
}: {
  run: VerificationRun | null;
  instant: boolean;
  error: ApiError | null;
}) {
  if (error) return <p className="text-sm text-muted-foreground">Nothing was started.</p>;
  if (!run) return <p className="text-sm text-muted-foreground">Asking…</p>;

  if (run.status === 'pending' || run.status === 'running') {
    return (
      <p role="status" className="text-sm text-muted-foreground">
        {run.status === 'pending' ? 'Waiting for a worker…' : 'Building…'} This takes a few minutes;
        the page can be left open.
      </p>
    );
  }

  const passed = run.status === 'passed';
  return (
    <p
      role="status"
      data-testid="verify-status"
      className={
        passed ? 'text-sm text-emerald-600 dark:text-emerald-500' : 'text-sm text-destructive'
      }
    >
      {passed ? 'This combination builds.' : 'This combination did not build.'}{' '}
      <span className="text-muted-foreground">
        {instant
          ? 'Answered from an earlier run of exactly this selection — nothing was rebuilt.'
          : 'Built just now, in a container.'}
      </span>
    </p>
  );
}

/**
 * A refusal, in the server's own words.
 *
 * §38 asks that a full queue be readable rather than generic, and §14 already puts the next action
 * in the hint — so the work here is to show the hint rather than to invent a sentence. The depth is
 * called out separately because it is the number the decision turns on.
 */
function Refusal({ error }: { error: ApiError }) {
  return (
    <div className="rounded border border-destructive/40 p-3">
      {/* The envelope verbatim (§39), plus the one number that is peculiar to this refusal and
          that a user's decision turns on. */}
      <ProblemDetail error={error} className="text-sm">
        {typeof error.problem.queueDepth === 'number' && (
          <p className="text-muted-foreground">
            {error.problem.queueDepth} {error.problem.queueDepth === 1 ? 'build is' : 'builds are'}{' '}
            waiting ahead of this one.
          </p>
        )}
      </ProblemDetail>
    </div>
  );
}
