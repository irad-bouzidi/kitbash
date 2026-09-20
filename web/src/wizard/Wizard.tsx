import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { GENERATE_URL, type CatalogVariable, type MetadataDocument } from '@/lib/api';
import { useMetadata } from '@/catalog/useMetadata';
import { FieldRenderer } from '@/wizard/FieldRenderer';
import { toEnvelope, useSelectionStore, useUrlSync } from '@/wizard/useSelection';
import { useFieldErrors } from '@/wizard/useFieldErrors';
import { useValidation } from '@/wizard/useValidation';

/**
 * The one-page wizard (§2, §9).
 *
 * One page with grouped sections and a live right rail, not a ten-step flow: at this option count
 * steps add friction and hide the shape of what is being built. Nothing here knows what any option
 * means — the groups, their order, the controls, the labels and the help all come from
 * `/api/v1/metadata`, so adding a recipe changes this page without changing this file.
 */
export function Wizard() {
  const { data: metadata, isPending, isError } = useMetadata();
  useUrlSync(metadata);
  const { resolution } = useValidation(metadata);
  const values = useSelectionStore((state) => state.values);
  const fieldErrors = useFieldErrors(metadata);

  if (isPending) return <p className="text-sm text-muted-foreground">Loading the catalog…</p>;
  if (isError || !metadata) {
    return (
      <p role="alert" className="text-sm text-destructive">
        The catalog is not reachable. Is the API running?
      </p>
    );
  }

  const envelope = toEnvelope(metadata, values);
  const nothingSelected = (resolution?.recipes ?? []).length === 0;
  const blocked =
    (resolution?.conflicts ?? []).length > 0 ||
    Object.keys(fieldErrors).length > 0 ||
    nothingSelected;
  const blockedReason = nothingSelected
    ? 'Choose at least one part of the stack.'
    : 'Resolve the problems above first.';

  return (
    <div className="flex w-full max-w-5xl flex-col gap-6">
      <div className="grid gap-6 md:grid-cols-[minmax(0,1fr)_18rem]">
        <div className="flex flex-col gap-6">
          {(metadata.groups ?? []).map((group) => (
            <Card key={group.id}>
              <CardHeader>
                <CardTitle>{group.label}</CardTitle>
                {group.help && <CardDescription>{group.help}</CardDescription>}
              </CardHeader>
              <CardContent className="flex flex-col gap-5">
                {(group.options ?? []).map((option) => (
                  <FieldRenderer key={option.id} option={option} resolution={resolution} />
                ))}
              </CardContent>
            </Card>
          ))}

          <Card>
            <CardHeader>
              <CardTitle>Names</CardTitle>
              <CardDescription>
                What the generated project and its sources are called.
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-5">
              {(metadata.variables ?? []).map((variable) => (
                <FieldRenderer
                  key={variable.id}
                  option={asOption(variable)}
                  resolution={resolution}
                  pattern={variable.pattern ?? undefined}
                  fieldError={variable.id ? fieldErrors[variable.id] : undefined}
                />
              ))}
            </CardContent>
          </Card>
        </div>

        <StackSummary metadata={metadata} resolution={resolution} />
      </div>

      {/*
        A real form submission, so the browser downloads natively: its own progress indicator,
        its own resume, no blob held in memory (§9). fetch + createObjectURL would put a
        multi-megabyte string in the tab and show the user nothing.
      */}
      <form
        method="post"
        action={GENERATE_URL}
        data-testid="download-form"
        className="sticky bottom-0 flex items-center gap-2 border-t bg-background/95 py-3 backdrop-blur"
      >
        <input type="hidden" name="selection" value={JSON.stringify(envelope)} />
        <Button type="button" variant="outline" disabled title="Preview arrives in phase 2.">
          Preview
        </Button>
        <Button type="button" variant="outline" disabled title="Presets arrive in phase 2.">
          Save as preset
        </Button>
        <span className="flex-1" />
        <Button type="submit" disabled={blocked} title={blocked ? blockedReason : undefined}>
          Generate
        </Button>
      </form>

      {/* The digest is what makes a bug report actionable (§9). */}
      <footer className="pb-6 text-xs text-muted-foreground">
        catalog {metadata.catalogDigest} · {metadata.recipeCount} recipes
      </footer>
    </div>
  );
}

/** A variable rendered through the same machinery as an option: one renderer, one set of rules. */
function asOption(variable: CatalogVariable) {
  return {
    id: variable.id,
    type: 'string' as const,
    label: variable.label,
    help: variable.help,
    required: false,
    defaultValue: variable.defaultValue,
    // Always available: a name is needed whatever the stack, and the server tells us which
    // recipes require it through `requiredBy` rather than through this field.
    availableWhen: undefined,
    choices: [],
  };
}

/**
 * The resolved stack, not the raw selection (§9).
 *
 * A user who picked a backend and got a database should see the database — the right rail answers
 * "what am I getting?", and answering "what did I click?" instead leaves the implied half to be
 * discovered in the zip.
 */
function StackSummary({
  metadata,
  resolution,
}: {
  metadata: MetadataDocument;
  resolution: ReturnType<typeof useValidation>['resolution'];
}) {
  const recipes = resolution?.recipes ?? [];

  return (
    <Card data-testid="stack-summary" className="h-fit md:sticky md:top-6">
      <CardHeader>
        <CardTitle>Your stack</CardTitle>
        <CardDescription>
          {recipes.length === 0
            ? 'Nothing selected yet.'
            : `${recipes.length} of ${metadata.recipeCount} recipes`}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-2 text-sm">
        {recipes.map((recipe) => (
          <div key={recipe.id} className="flex items-baseline justify-between gap-2">
            <span>{recipe.label}</span>
            <span className="text-xs text-muted-foreground">
              {recipe.implied ? 'added for you' : (recipe.frameworkVersion ?? '')}
            </span>
          </div>
        ))}
      </CardContent>
    </Card>
  );
}
