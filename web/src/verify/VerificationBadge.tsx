import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchCellLog, type VerificationBadge as Badge } from '@/lib/api';
import { BuildLog } from '@/verify/BuildLog';

/**
 * The warning §9 puts **at the option pairing**.
 *
 * Not a page banner and not a footnote: the pairing is where the decision is made, and a warning
 * anywhere else asks the user to work out which of their nine choices it is about. §36 is the
 * worked example — Kotlin with the typed client was red in twenty-four combinations while Kotlin
 * with everything else stayed green, and a badge on "Kotlin" would have been alarming and untrue.
 *
 * This component knows nothing about what it decorates. It is handed badges and labels; which
 * pairings exist, and what they mean, comes from the catalog and the matrix.
 */
export function PairingWarning({
  badges,
  labelFor,
}: {
  badges: Badge[];
  /** Turns a badge key into something a person reads. Supplied by the caller, not derived here. */
  labelFor: (key: string) => string;
}) {
  if (badges.length === 0) return null;

  return (
    <>
      {badges.map((badge) => (
        <div key={badge.key}>
          <p
            role="status"
            data-testid="pairing-warning"
            className="text-xs text-amber-600 dark:text-amber-500"
            title={badge.cell ? `Failing cell: ${badge.cell}` : undefined}
          >
            ⚠ {labelFor(badge.key ?? '')} last failed at{' '}
            <code className="font-mono">{badge.failedStep ?? 'an unnamed step'}</code>
            {typeof badge.passed === 'number' &&
            badge.passed > 0 &&
            typeof badge.failed === 'number'
              ? ` — ${badge.failed} of ${badge.passed + badge.failed} verified combinations`
              : ''}
            .{' '}
            {/* §38 asks for the reason *and* a link to the log: "pnpm typecheck failed" says
                which step and not which line, and the line is what a person needs. */}
            {badge.cell && <CellLogLink cell={badge.cell} />}
          </p>
        </div>
      ))}
    </>
  );
}

/**
 * When this choice was last built, and against which catalog.
 *
 * §38: *a green badge with no date is a claim, not evidence.* So there is no green badge without
 * one, and a choice nothing has ever built says "not verified" rather than staying silent —
 * absence of data is never shown as success.
 */
export function VerifiedLine({
  badge,
  generatedAt,
  catalogDigest,
}: {
  badge: Badge | undefined;
  generatedAt: string | undefined | null;
  catalogDigest: string | undefined | null;
}) {
  if (!badge || badge.status === 'unverified' || !generatedAt) {
    return (
      <p data-testid="verified-line" className="text-xs text-muted-foreground">
        Not verified.
      </p>
    );
  }
  if (badge.status === 'failed') return null;

  return (
    <p
      data-testid="verified-line"
      className="text-xs text-muted-foreground"
      title={catalogDigest ? `catalog ${catalogDigest}` : undefined}
    >
      Verified {new Date(generatedAt).toLocaleDateString()} in {badge.passed} combination
      {badge.passed === 1 ? '' : 's'}.
    </p>
  );
}

/**
 * The failing cell's log, fetched only when asked for.
 *
 * Never on render: a wizard with three red pairings would otherwise pull three build logs nobody
 * has looked at, and a build log is the largest thing this API serves.
 */
function CellLogLink({ cell }: { cell: string }) {
  const [open, setOpen] = useState(false);

  return (
    <>
      <button
        type="button"
        className="underline underline-offset-2"
        onClick={() => setOpen(true)}
        data-testid="cell-log-link"
      >
        See the log
      </button>
      {open && <CellLogDialog cell={cell} onClose={() => setOpen(false)} />}
    </>
  );
}

function CellLogDialog({ cell, onClose }: { cell: string; onClose: () => void }) {
  const log = useQuery({
    queryKey: ['cell-log', cell],
    queryFn: () => fetchCellLog(cell),
    retry: false,
  });

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
      aria-label={`Log for ${cell}`}
      data-testid="cell-log-dialog"
    >
      <div className="flex h-[80vh] w-full max-w-4xl flex-col gap-4 rounded-lg border bg-background p-6">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 className="text-lg font-semibold">{cell}</h2>
            <p className="text-sm text-muted-foreground">
              The nightly matrix&rsquo;s own log for this combination.
            </p>
          </div>
          <button type="button" className="text-sm underline" onClick={onClose}>
            Close
          </button>
        </div>
        {log.isPending && <p className="text-sm text-muted-foreground">Loading…</p>}
        {log.isError && (
          <p role="alert" className="text-sm text-destructive">
            That log is no longer published. A newer run has replaced it.
          </p>
        )}
        {log.data && <BuildLog text={log.data} />}
      </div>
    </div>
  );
}
