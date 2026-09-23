import { Api, type SelectionEnvelope, type ValidationResponse } from './api.js';
import { promptsFrom, reasonUnavailable, type MetadataDocument, type Option } from './metadata.js';
import type { Prompter } from './prompt.js';

/**
 * The prompts, driven entirely by the metadata document (§8, §9, §44).
 *
 * <p>The one rule: **this file never names an option.** It switches on `type` and nothing else,
 * exactly as the web wizard's `FieldRenderer` does, which is what makes adding a recipe a
 * backend-only change for this tool as well.
 *
 * <p>It revalidates after every answer rather than at the end, because a conflict discovered at
 * the end is nine questions the user has to re-answer — and because the resolver's answer is what
 * decides which of the *later* prompts even apply.
 */
export async function interview(
  api: Api,
  metadata: MetadataDocument,
  prompter: Prompter,
): Promise<SelectionEnvelope> {
  const options: Record<string, string | boolean> = {};
  const variables: Record<string, string> = {};

  // The one variable with its own field in the §7 envelope, declared as such rather than
  // special-cased by id — the failure §9 warns about.
  const envelopeVariables = (metadata.variables ?? []).filter(
    (variable) => variable.scope === 'envelope',
  );
  const projectNameSpec = envelopeVariables[0];
  let projectName = String(projectNameSpec?.defaultValue ?? 'demo');

  if (projectNameSpec) {
    projectName = await prompter.text(
      projectNameSpec.label ?? 'Project name',
      projectNameSpec.help ?? undefined,
      projectNameSpec.pattern ?? undefined,
      projectName,
    );
  }

  let resolution: ValidationResponse = { recipes: [] };

  for (const group of promptsFrom(metadata)) {
    for (const option of group.options ?? []) {
      const id = option.id;
      if (!id) continue;

      const selectedRecipes = (resolution.recipes ?? [])
        .map((recipe) => recipe.id)
        .filter((recipeId): recipeId is string => typeof recipeId === 'string');
      const unavailable = reasonUnavailable(option, selectedRecipes);
      if (unavailable) {
        // §9 keeps a blocked control visible with the reason. A terminal has no disabled state, so
        // saying why it was skipped is the nearest true equivalent — silence would make the
        // catalog look arbitrary, which is the thing §9 is protecting against.
        prompter.skipped(option.label ?? id, unavailable);
        continue;
      }

      const answer = await ask(prompter, option);
      if (answer === undefined) {
        delete options[id];
      } else {
        options[id] = answer;
      }

      // After every answer, so a conflict surfaces here rather than at the end (§44) — and so the
      // next prompt knows what the resolver has implied.
      resolution = await api.validate({ schemaVersion: 1, projectName, options, variables });
      report(prompter, resolution);
    }
  }

  // The remaining variables last: they name things in a stack the user has now chosen, and asking
  // for a package name before there is a backend is asking about something that may not apply.
  for (const variable of metadata.variables ?? []) {
    if (!variable.id || variable.scope === 'envelope') continue;
    variables[variable.id] = await prompter.text(
      variable.label ?? variable.id,
      variable.help ?? undefined,
      variable.pattern ?? undefined,
      String(variable.defaultValue ?? ''),
    );
  }

  return { schemaVersion: 1, projectName, options, variables };
}

/** Option **type** to prompt. The only switch in this client, and it never sees an id (§9). */
async function ask(prompter: Prompter, option: Option): Promise<string | boolean | undefined> {
  const label = option.label ?? option.id ?? '';
  const help = option.help ?? undefined;

  switch (option.type) {
    case 'boolean':
      return prompter.confirm(label, help, option.defaultValue === true);
    case 'enum':
    case 'multi-select': {
      const choices = (option.choices ?? [])
        .filter(
          (choice): choice is { value: string; label?: string } => typeof choice.value === 'string',
        )
        .map((choice) => ({
          value: choice.value,
          label: choice.label ?? choice.value,
          note:
            (option.choices ?? []).find((c) => c.value === choice.value)?.frameworkVersion ??
            undefined,
        }));
      const fallback = typeof option.defaultValue === 'string' ? option.defaultValue : undefined;
      return prompter.choose(
        label,
        help,
        choices,
        option.required ? (fallback ?? choices[0]?.value) : fallback,
      );
    }
    case 'string':
    default:
      return prompter.text(label, help, undefined, asText(option.defaultValue));
  }
}

/**
 * A default, as a string a person can be shown.
 *
 * `defaultValue` is `unknown` because the metadata document types it as "whatever this option's
 * type takes". Anything that is not already text has no sensible prompt default — `String()` on an
 * object offers `[object Object]` and then sends it to the server.
 */
function asText(value: unknown): string {
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return '';
}

/**
 * What the resolver said, as it says it.
 *
 * Conflicts and warnings are printed verbatim: §14 puts the next action in the hint, and a client
 * that summarised would be throwing away the half that helps.
 */
function report(prompter: Prompter, resolution: ValidationResponse): void {
  for (const conflict of resolution.conflicts ?? []) {
    process.stdout.write(`  ! ${conflict.message ?? ''} ${conflict.hint ?? ''}\n`);
  }
  for (const warning of resolution.warnings ?? []) {
    process.stdout.write(`  · ${warning.message ?? ''}\n`);
  }
  void prompter;
}
