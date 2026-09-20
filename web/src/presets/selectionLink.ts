import type { GenerateRequest } from '@/lib/api';

/**
 * A preset's selection as a wizard URL.
 *
 * §9 makes the URL the configuration, so "open this preset in the wizard" needs no second
 * mechanism: the same flat encoding the wizard already reads and writes is what a preset's stored
 * selection turns into. A separate "load preset into store" path would be a second way to express
 * the same thing, and the two would drift.
 */
export function wizardLink(selection: GenerateRequest | undefined): string {
  const params = new URLSearchParams();
  for (const [id, value] of Object.entries(selection?.options ?? {})) {
    // The envelope types option values as `unknown` because the catalog decides what they are
    // (§7, §8). Only the three primitives the wizard can put in a URL are carried back out of
    // one; anything else was not written by this application.
    if (typeof value === 'string' && value !== '') params.set(id, value);
    else if (typeof value === 'boolean' && value) params.set(id, 'true');
    else if (typeof value === 'number') params.set(id, String(value));
  }
  for (const [id, value] of Object.entries(selection?.variables ?? {})) {
    if (value) params.set(id, value);
  }
  if (selection?.projectName) params.set('projectName', selection.projectName);
  params.sort();
  return `/new?${params.toString()}`;
}
