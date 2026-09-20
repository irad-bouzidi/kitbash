import { z } from 'zod';
import type { MetadataDocument } from '@/lib/api';
import { useSelectionStore } from '@/wizard/useSelection';

/**
 * A Zod schema built at runtime from the catalog, and the per-field errors it produces.
 *
 * §5 asks for the schema to be built from catalog metadata rather than written out, and the reason
 * is the same one that shapes everything else here: a static schema cannot follow a catalog that
 * changes without a frontend deploy. Every rule below comes from the `pattern` the server ships.
 *
 * <p>React Hook Form is deliberately not used with it. RHF's contribution is owning form state,
 * and the selection is already a single store — one that has to serialise to the URL and to the
 * §7 envelope. A second copy of the same values would be two things to keep in step, and the
 * first divergence would be invisible.
 *
 * <p>This is a convenience, never the check: §13 treats every input as hostile and the server
 * validates the same values again on arrival.
 */
export function schemaFor(metadata: MetadataDocument | undefined) {
  const shape: Record<string, z.ZodTypeAny> = {};
  for (const variable of metadata?.variables ?? []) {
    if (!variable.id) continue;
    shape[variable.id] = variable.pattern
      ? z.string().regex(new RegExp(variable.pattern), `Does not match ${variable.pattern}`)
      : z.string();
  }
  return z.object(shape).partial();
}

export function useFieldErrors(metadata: MetadataDocument | undefined): Record<string, string> {
  const values = useSelectionStore((state) => state.values);

  const candidate: Record<string, string> = {};
  for (const variable of metadata?.variables ?? []) {
    if (variable.id) candidate[variable.id] = String(values[variable.id] ?? '');
  }

  const result = schemaFor(metadata).safeParse(candidate);
  if (result.success) return {};

  const errors: Record<string, string> = {};
  for (const issue of result.error.issues) {
    const field = String(issue.path[0] ?? '');
    if (field && !errors[field]) errors[field] = issue.message;
  }
  return errors;
}
