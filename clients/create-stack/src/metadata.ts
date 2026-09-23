/**
 * The catalog, and the prompts it implies.
 *
 * §8's claim is that `/api/v1/metadata` carries everything needed to render an interface, so a
 * second client is a rendering problem rather than a product rewrite. This file is where that
 * claim is either true or false for the terminal: **nothing here names an option**. Adding a
 * recipe must change the prompts with no release of this tool.
 */

/** One value an option can take. */
export interface Choice {
  value?: string;
  label?: string;
  frameworkVersion?: string | null;
  availableWhen?: string[];
}

/** One control. `type` is the only thing a client switches on (§9). */
export interface Option {
  id?: string;
  type?: string;
  label?: string;
  help?: string | null;
  required?: boolean;
  defaultValue?: unknown;
  availableWhen?: string[];
  choices?: Choice[];
}

export interface Group {
  id?: string;
  label?: string;
  help?: string | null;
  order?: number;
  options?: Option[];
}

export interface Variable {
  id?: string;
  label?: string;
  help?: string | null;
  pattern?: string | null;
  defaultValue?: string | null;
  scope?: string | null;
}

export interface MetadataDocument {
  schemaVersion?: number;
  catalogDigest?: string;
  recipeCount?: number;
  groups?: Group[];
  variables?: Variable[];
}

/**
 * The prompts, in the order the wizard shows them.
 *
 * §44: *prompt ordering should follow the metadata document's option group order, so the CLI and
 * the web wizard present the same mental model.* Somebody who has used one should recognise the
 * other, and a different order would make the same catalog feel like two products.
 */
export function promptsFrom(metadata: MetadataDocument): Group[] {
  return [...(metadata.groups ?? [])].sort((left, right) => (left.order ?? 0) - (right.order ?? 0));
}

/**
 * Whether an option applies to what has been chosen so far.
 *
 * `availableWhen` names the recipes that declare the option, and is empty for a slot. §9 keeps a
 * blocked option **visible and disabled** in the wizard because hiding it makes the catalog feel
 * arbitrary; a terminal has no disabled state, so the equivalent is to skip the prompt and say why
 * — which is what `reasonUnavailable` is for.
 */
export function reasonUnavailable(option: Option, selectedRecipes: string[]): string | undefined {
  const owners = option.availableWhen ?? [];
  if (owners.length === 0) return undefined;
  if (owners.some((owner) => selectedRecipes.includes(owner))) return undefined;
  return `applies when ${owners.join(' or ')} is selected`;
}
