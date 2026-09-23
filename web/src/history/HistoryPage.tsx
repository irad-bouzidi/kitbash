import { asProblem } from '@/errors/problem';
import { ProblemDetail } from '@/errors/ProblemDetail';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { ButtonLink } from '@/components/ui/button-link';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import {
  ApiError,
  createPreset,
  downloadGeneration,
  fetchGenerations,
  keepGeneration,
  replayGeneration,
  type Generation,
  type Replay,
} from '@/lib/api';
import { wizardLink } from '@/presets/selectionLink';

/**
 * Generations as receipts (§3, §7, §24).
 *
 * Every row carries the lock that produced it, which is the point: §7 calls the lock the valuable
 * part, and it is why nothing reproducible is lost when an artifact expires. A row that can no
 * longer be reproduced exactly says so before anybody clicks, because a button that might fail is
 * worse than one that explains itself.
 */
export function HistoryPage() {
  const {
    data: generations,
    isPending,
    isError,
    error,
  } = useQuery({ queryKey: ['generations'], queryFn: fetchGenerations });

  if (isPending) return <p className="text-sm text-muted-foreground">Loading your history…</p>;

  if (isError) {
    return (
      <div className="flex w-full max-w-5xl flex-col gap-3">
        {/* The server's own envelope where there is one, so a 404 explains itself and a 403
            says which role is missing. Our sentence only for a failure that never reached it. */}
        {asProblem(error) ? (
          <ProblemDetail error={error} className="text-sm" />
        ) : (
          <p role="alert" className="text-sm text-destructive">
            History is not reachable.
          </p>
        )}
        <ButtonLink to="/new" variant="outline">
          Start a new project
        </ButtonLink>
      </div>
    );
  }

  return (
    <div className="flex w-full max-w-5xl flex-col gap-4">
      <div>
        <h2 className="text-lg font-semibold">History</h2>
        <p className="text-sm text-muted-foreground">
          Every generation, with the exact versions that produced it. Kept for 30 days unless you
          keep one.
        </p>
      </div>

      {generations.length === 0 ? (
        <Card>
          <CardHeader>
            <CardTitle>Nothing generated yet</CardTitle>
            <CardDescription>
              Every project you generate shows up here with its lock, so you can reproduce it or see
              what changed since.
            </CardDescription>
          </CardHeader>
        </Card>
      ) : (
        <ul className="flex flex-col gap-3" data-testid="generation-list">
          {generations.map((generation) => (
            <GenerationCard key={generation.id} generation={generation} />
          ))}
        </ul>
      )}
    </div>
  );
}

function GenerationCard({ generation }: { generation: Generation }) {
  const queryClient = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<ApiError | null>(null);
  const [replay, setReplay] = useState<Replay | null>(null);
  const failed = generation.status === 'failed';

  const keep = useMutation({
    mutationFn: () => keepGeneration(generation.id ?? ''),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['generations'] }),
  });

  /**
   * Saving a history row as a preset copies its lock forward (§10) — so the preset reproduces
   * what was shipped rather than what the catalog happens to hold. It can be switched to tracking
   * afterwards; what it must not do is silently become a different stack.
   */
  const saveAsPreset = useMutation({
    mutationFn: () =>
      createPreset({
        name: generation.projectName ?? 'saved generation',
        description: `Saved from the generation of ${new Date(generation.createdAt ?? '').toLocaleDateString()}`,
        visibility: 'private',
        versionPolicy: 'pinned',
        pinnedRecipes: generation.lock,
        selection: generation.selection,
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['presets'] }),
  });

  function run(work: Promise<unknown>) {
    setFailure(null);
    setBusy(true);
    work
      .catch((thrown: unknown) => setFailure(thrown instanceof ApiError ? thrown : null))
      .finally(() => setBusy(false));
  }

  return (
    <li>
      <Card data-testid="generation-card">
        <CardHeader className="flex flex-row items-start justify-between gap-4">
          <div>
            <CardTitle className="text-base">{generation.projectName}</CardTitle>
            <CardDescription>
              {new Date(generation.createdAt ?? '').toLocaleString()}
              {generation.sizeBytes ? ` · ${Math.round(generation.sizeBytes / 1024)} KB` : ''}
              {generation.durationMillis ? ` · ${generation.durationMillis} ms` : ''}
            </CardDescription>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            {failed && <Tag>failed</Tag>}
            {generation.kept && <Tag>kept</Tag>}
            {/* Answered when the row was read, not when a button is pressed (§24). */}
            {!failed && (
              <Tag>{generation.exactlyReproducible ? 'reproducible' : 'catalog moved'}</Tag>
            )}
          </div>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <details>
            <summary className="cursor-pointer text-sm text-muted-foreground">
              The lock — the exact versions that produced this
            </summary>
            <ul className="mt-2 flex flex-col gap-1 text-sm" data-testid="generation-lock">
              {Object.entries(generation.lock ?? {}).map(([recipe, version]) => (
                <li key={recipe} className="flex items-center justify-between gap-4">
                  <span>{recipe}</span>
                  <span className="text-xs text-muted-foreground">{version}</span>
                </li>
              ))}
            </ul>
          </details>

          {replay && (
            <p role="status" className="text-sm">
              {/* §24: the response states which mode ran and what changed. */}
              Replayed <strong>{replay.mode}</strong>:{' '}
              {replay.catalogMoved ? replay.summary : 'nothing has changed since'}
            </p>
          )}
          {failure && <ProblemDetail error={failure} className="text-sm" />}

          <div className="flex flex-wrap items-center gap-2">
            <Button disabled={busy || failed} onClick={() => run(downloadGeneration(generation))}>
              Download again
            </Button>
            <Button
              variant="outline"
              disabled={busy || failed}
              onClick={() =>
                run(
                  replayGeneration(generation.id ?? '', 'exact').then((result) =>
                    setReplay(result),
                  ),
                )
              }
              title="What did I ship? Refuses if the catalog has moved past this lock."
            >
              Replay exactly
            </Button>
            <Button
              variant="outline"
              disabled={busy || failed}
              onClick={() =>
                run(
                  replayGeneration(generation.id ?? '', 'current').then((result) =>
                    setReplay(result),
                  ),
                )
              }
              title="What would this choice give me today?"
            >
              Replay on today&rsquo;s catalog
            </Button>
            <Button
              variant="outline"
              disabled={failed || saveAsPreset.isPending}
              onClick={() => saveAsPreset.mutate()}
            >
              {saveAsPreset.isSuccess ? 'Saved as preset' : 'Save as preset'}
            </Button>
            {!generation.kept && (
              <Button variant="outline" disabled={keep.isPending} onClick={() => keep.mutate()}>
                Keep
              </Button>
            )}
            <ButtonLink to={wizardLink(generation.selection)} variant="outline">
              Open in the wizard
            </ButtonLink>
          </div>
        </CardContent>
      </Card>
    </li>
  );
}

function Tag({ children }: { children: React.ReactNode }) {
  return (
    <span className="rounded-full border px-2 py-0.5 text-xs text-muted-foreground">
      {children}
    </span>
  );
}
