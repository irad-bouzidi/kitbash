import { useEffect } from 'react';
import { create } from 'zustand';
import type { CatalogVariable, GenerateRequest, MetadataDocument } from '@/lib/api';

/**
 * The selection, and its reflection in the URL.
 *
 * §9 asks for the selection to sync to the URL on every change, so back and forward work and a
 * link captures a configuration. The encoding is the §7 envelope's own flat shape — option id to
 * value, in one namespace with the variables — rather than a second format invented for the
 * address bar. Two encodings of the same thing is two things to keep in step.
 *
 * Nothing in this file names an option or a variable. Where a value belongs in the envelope comes
 * from the catalog's `scope`, because the alternative is one `if (id === …)` and that special case
 * is exactly the failure §9 warns about.
 */

export type SelectionValue = string | boolean;

interface SelectionState {
  values: Record<string, SelectionValue>;
  ready: boolean;
  set: (id: string, value: SelectionValue) => void;
  replaceAll: (values: Record<string, SelectionValue>) => void;
}

export const useSelectionStore = create<SelectionState>((set) => ({
  values: {},
  ready: false,
  set: (id, value) => set((state) => ({ values: { ...state.values, [id]: value } })),
  replaceAll: (values) => set({ values, ready: true }),
}));

const ENVELOPE_SCOPE = 'envelope';

/** The §7 envelope, which is what /validate and /generate both take. */
export function toEnvelope(
  metadata: MetadataDocument | undefined,
  values: Record<string, SelectionValue>,
): GenerateRequest {
  const variables: Record<string, CatalogVariable> = {};
  for (const variable of metadata?.variables ?? []) {
    if (variable.id) variables[variable.id] = variable;
  }

  const options: Record<string, SelectionValue> = {};
  const variableValues: Record<string, string> = {};
  let projectName = '';

  for (const [id, value] of Object.entries(values)) {
    const variable = variables[id];
    if (variable?.scope === ENVELOPE_SCOPE) {
      projectName = String(value);
    } else if (variable) {
      variableValues[id] = String(value);
    } else if (value !== '' && value !== false) {
      // An unset enum and an off toggle are both "not chosen": sending them would make the
      // canonical hash depend on which controls the user happened to touch (§7).
      options[id] = value;
    }
  }

  return {
    schemaVersion: 1,
    projectName,
    options,
    variables: variableValues,
  };
}

function toSearch(values: Record<string, SelectionValue>): string {
  const params = new URLSearchParams();
  for (const [id, value] of Object.entries(values)) {
    if (value === '' || value === false) continue;
    params.set(id, String(value));
  }
  params.sort();
  return params.toString();
}

/** Every control the catalog offers, with its default, before the URL is applied. */
function defaults(metadata: MetadataDocument): Record<string, SelectionValue> {
  const values: Record<string, SelectionValue> = {};
  for (const group of metadata.groups ?? []) {
    for (const option of group.options ?? []) {
      if (!option.id) continue;
      if (option.defaultValue !== null && option.defaultValue !== undefined) {
        values[option.id] = option.defaultValue as SelectionValue;
      } else if (option.type !== 'boolean') {
        values[option.id] = '';
      }
    }
  }
  for (const variable of metadata.variables ?? []) {
    if (variable.id) values[variable.id] = variable.defaultValue ?? '';
  }
  return values;
}

/**
 * Reads the URL once the catalog has arrived, and writes it on every change afterwards.
 *
 * The defaults come from the catalog, so a new option arrives with its own default and this
 * needs no edit.
 */
export function useUrlSync(metadata: MetadataDocument | undefined) {
  const values = useSelectionStore((state) => state.values);
  const ready = useSelectionStore((state) => state.ready);
  const replaceAll = useSelectionStore((state) => state.replaceAll);

  useEffect(() => {
    if (!metadata || ready) return;
    const params = new URLSearchParams(window.location.search);
    const initial = defaults(metadata);

    for (const [id, fallback] of Object.entries(initial)) {
      const raw = params.get(id);
      if (raw === null) continue;
      initial[id] = typeof fallback === 'boolean' ? raw === 'true' : raw;
    }
    replaceAll(initial);
  }, [metadata, ready, replaceAll]);

  useEffect(() => {
    if (!ready) return;
    const search = toSearch(values);
    const next = `${window.location.pathname}${search ? `?${search}` : ''}`;
    if (next !== `${window.location.pathname}${window.location.search}`) {
      // replaceState, not pushState: a keystroke in a text field is not a navigation, and
      // pushing one per character would make Back useless.
      window.history.replaceState(null, '', next);
    }
  }, [ready, values]);
}
