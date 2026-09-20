import { useEffect, useState } from 'react';
import { validate, type MetadataDocument, type ValidationResponse } from '@/lib/api';
import { toEnvelope, useSelectionStore } from '@/wizard/useSelection';

/**
 * Resolves the current selection, 250 ms after it stops changing (§9).
 *
 * The debounce is what makes this affordable, and what makes it affordable at all is that
 * `/validate` runs only stages 1–2 of §6 — no template, no filesystem, no database. A response
 * that arrives after a newer request was sent is dropped rather than rendered: without that, a
 * fast typist sees conflicts belonging to a selection they have already moved on from.
 */
export function useValidation(metadata: MetadataDocument | undefined) {
  const values = useSelectionStore((state) => state.values);
  const ready = useSelectionStore((state) => state.ready);
  const [resolution, setResolution] = useState<ValidationResponse | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!metadata || !ready) return undefined;

    let current = true;
    const timer = setTimeout(() => {
      validate(toEnvelope(metadata, values))
        .then((response) => {
          if (current) {
            setResolution(response);
            setFailed(false);
          }
        })
        .catch(() => {
          if (current) setFailed(true);
        });
    }, 250);

    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [metadata, ready, values]);

  return { resolution, failed };
}

/**
 * The diagnostics for one control.
 *
 * §9: conflicts render inline on the offending field, never as a top banner. That only works if
 * the server says which field, which is why every §14 error carries the option the user can
 * change rather than the capability the engine rejected.
 */
export function diagnosticsFor(resolution: ValidationResponse | null, optionId: string) {
  const all = [...(resolution?.conflicts ?? []), ...(resolution?.warnings ?? [])];
  return all.filter((diagnostic) => diagnostic.optionId === optionId);
}
