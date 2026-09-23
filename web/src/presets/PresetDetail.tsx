import { ProblemDetail } from '@/errors/ProblemDetail';
import { asProblem } from '@/errors/problem';
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { ButtonLink } from '@/components/ui/button-link';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { ApiError, downloadFromPreset, fetchPreset } from '@/lib/api';
import { wizardLink } from '@/presets/selectionLink';

/**
 * One preset: what it selects, and on what terms.
 *
 * The version policy is shown rather than implied. §7 keeps pinning available for compliance
 * cases and makes tracking the default, and a preset that silently did one or the other would
 * be the trap that decision exists to avoid — so a pinned preset says what it pinned to, and a
 * mixed policy is displayed as the normal thing it is.
 */
export function PresetDetail() {
  const { id = '' } = useParams();
  const {
    data: preset,
    isPending,
    isError,
    error,
  } = useQuery({
    queryKey: ['presets', id],
    queryFn: () => fetchPreset(id),
  });
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<ApiError | null>(null);

  if (isPending) return <p className="text-sm text-muted-foreground">Loading…</p>;
  if (isError || !preset) {
    return asProblem(error) ? (
      <ProblemDetail error={error} className="text-sm" />
    ) : (
      <p role="alert" className="text-sm text-destructive">
        No such preset.
      </p>
    );
  }

  const pins = Object.entries(preset.pinnedRecipes ?? {});

  return (
    <div className="flex w-full max-w-5xl flex-col gap-4">
      <div className="flex items-center justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold">{preset.name}</h2>
          <p className="text-sm text-muted-foreground">{preset.description}</p>
        </div>
        <ButtonLink to={'/'} variant="outline">
          All presets
        </ButtonLink>
      </div>

      {preset.staleReason && (
        <p role="alert" className="text-sm text-destructive">
          {preset.staleReason}
        </p>
      )}
      {failure && <ProblemDetail error={failure} className="text-sm" />}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">The stack</CardTitle>
          <CardDescription>
            Resolved when you generate, not when this was saved — which is what tracking the catalog
            means.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <ul className="flex flex-col gap-1 text-sm" data-testid="preset-recipes">
            {(preset.recipeIds ?? []).map((recipe) => (
              <li key={recipe} className="flex items-center justify-between gap-4">
                <span>{recipe}</span>
                <span className="text-xs text-muted-foreground">
                  {preset.pinnedRecipes?.[recipe] ?? 'latest'}
                </span>
              </li>
            ))}
          </ul>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">
            {preset.versionPolicy === 'pinned' ? 'Pinned' : 'Tracks the latest catalog'}
          </CardTitle>
          <CardDescription>
            {preset.versionPolicy === 'pinned'
              ? 'This preset holds still. Reproducibility of a past build lives on the generation, which carries a lock — pinning here is for the cases that need the catalog itself to stop moving.'
              : 'A preset frozen on an old framework version is a trap. This one follows the catalog, and every generation still records exactly what it used.'}
          </CardDescription>
        </CardHeader>
        {pins.length > 0 && (
          <CardContent>
            <ul className="flex flex-col gap-1 text-sm">
              {pins.map(([recipe, version]) => (
                <li key={recipe}>
                  {recipe} <span className="text-muted-foreground">{version}</span>
                </li>
              ))}
            </ul>
          </CardContent>
        )}
      </Card>

      <div className="flex items-center gap-2">
        <Button
          disabled={busy || Boolean(preset.staleReason)}
          onClick={() => {
            setFailure(null);
            setBusy(true);
            downloadFromPreset(preset)
              .catch((thrown: unknown) => setFailure(thrown instanceof ApiError ? thrown : null))
              .finally(() => setBusy(false));
          }}
        >
          {busy ? 'Generating…' : 'Generate'}
        </Button>
        <ButtonLink to={wizardLink(preset.selection)} variant="outline">
          Open in the wizard
        </ButtonLink>
        <span className="flex-1" />
        <span className="text-xs text-muted-foreground">revision {preset.revision}</span>
      </div>
    </div>
  );
}
