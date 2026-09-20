import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { ButtonLink } from '@/components/ui/button-link';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { ApiError, deletePreset, downloadFromPreset, fetchPresets, type Preset } from '@/lib/api';
import { wizardLink } from '@/presets/selectionLink';

/**
 * The landing page for a returning user (§9).
 *
 * §9 folds the Implementation Plan's Dashboard into this list deliberately: a page of counter tiles
 * earns nothing, and what somebody coming back actually wants is their own stacks — with Generate
 * one click from a cold load, which is what this page is arranged around.
 *
 * Nothing here knows what a recipe is. A preset carries the ids it selects and the server decides
 * whether they still resolve, so adding a recipe to the catalog changes this page without changing
 * this file (§8).
 */
export function PresetsPage() {
  const queryClient = useQueryClient();
  const {
    data: presets,
    isPending,
    isError,
    error,
  } = useQuery({
    queryKey: ['presets'],
    queryFn: fetchPresets,
  });

  const remove = useMutation({
    mutationFn: deletePreset,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['presets'] }),
  });

  if (isPending) return <p className="text-sm text-muted-foreground">Loading your presets…</p>;

  if (isError) {
    return (
      <div className="flex w-full max-w-5xl flex-col gap-3">
        <p role="alert" className="text-sm text-destructive">
          {error instanceof ApiError
            ? (error.problem.detail ?? error.message)
            : 'Presets are not reachable.'}
        </p>
        {/* A deployment without a database serves the generator and nothing else (§10, §12), so
            this is a normal state rather than a broken one — and the wizard still works. */}
        <ButtonLink to={'/new'} variant="outline">
          Start a new project
        </ButtonLink>
      </div>
    );
  }

  return (
    <div className="flex w-full max-w-5xl flex-col gap-4">
      <div className="flex items-center justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold">Presets</h2>
          <p className="text-sm text-muted-foreground">
            Saved stacks. They follow the catalog, so a preset saved last year builds with this
            year&rsquo;s versions.
          </p>
        </div>
        <ButtonLink to={'/new'}>Start a new project</ButtonLink>
      </div>

      {presets.length === 0 ? (
        <Card>
          <CardHeader>
            <CardTitle>Nothing saved yet</CardTitle>
            <CardDescription>
              Build a stack in the wizard and choose <strong>Save as preset</strong>. It will be
              here next time, one click from a project.
            </CardDescription>
          </CardHeader>
        </Card>
      ) : (
        <ul className="flex flex-col gap-3" data-testid="preset-list">
          {presets.map((preset) => (
            <PresetCard
              key={preset.id}
              preset={preset}
              onDelete={() => remove.mutate(preset.id ?? '')}
            />
          ))}
        </ul>
      )}
    </div>
  );
}

function PresetCard({ preset, onDelete }: { preset: Preset; onDelete: () => void }) {
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<ApiError | null>(null);
  const stale = preset.staleReason;

  return (
    <li>
      <Card data-testid="preset-card">
        <CardHeader className="flex flex-row items-start justify-between gap-4">
          <div>
            <CardTitle className="text-base">
              <Link to={`/presets/${preset.id}`} className="hover:underline">
                {preset.name}
              </Link>
            </CardTitle>
            <CardDescription>{preset.description}</CardDescription>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            <Badge>{preset.visibility}</Badge>
            <Badge>{preset.versionPolicy === 'pinned' ? 'pinned' : 'tracks latest'}</Badge>
          </div>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          {stale && (
            // Reported at load, not at render (§23). A preset that lost a recipe is a fact about
            // the catalog, and finding out halfway through a download would be a bug report.
            <p role="alert" className="text-sm text-destructive">
              {stale}
            </p>
          )}
          {failure && (
            <p role="alert" className="text-sm text-destructive">
              {failure.problem.detail ?? failure.message}
              {failure.problem.hint && (
                <span className="block text-muted-foreground">{failure.problem.hint}</span>
              )}
            </p>
          )}
          <div className="flex flex-wrap items-center gap-2">
            <Button
              disabled={busy || Boolean(stale)}
              onClick={() => {
                setFailure(null);
                setBusy(true);
                downloadFromPreset(preset)
                  .catch((thrown: unknown) =>
                    setFailure(thrown instanceof ApiError ? thrown : null),
                  )
                  .finally(() => setBusy(false));
              }}
            >
              {busy ? 'Generating…' : 'Generate'}
            </Button>
            <ButtonLink to={wizardLink(preset.selection)} variant="outline">
              Open in the wizard
            </ButtonLink>
            {preset.mine && (
              <Button variant="outline" onClick={onDelete}>
                Delete
              </Button>
            )}
          </div>
        </CardContent>
      </Card>
    </li>
  );
}

function Badge({ children }: { children: React.ReactNode }) {
  return (
    <span className="rounded-full border px-2 py-0.5 text-xs text-muted-foreground">
      {children}
    </span>
  );
}
