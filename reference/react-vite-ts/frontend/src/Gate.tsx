import type { ReactNode } from 'react';

// kitbash:imports

/**
 * The seam every recipe that guards the application inserts into.
 *
 * With nothing selected it is a pass-through, and that is the point: `App` always renders through
 * it, so turning auth on is one insertion at the marker below rather than a rewrite of `App`. A
 * patch can add lines to a file another recipe owns; it cannot wrap an element in one, so the
 * wrapper has to exist first.
 *
 * It ships even when nothing uses it, for the same reason the README's `<!-- kitbash:... -->`
 * markers do: an extension point nobody wrote down is one the next recipe reaches for by
 * pattern-matching on the code around it.
 */
export function Gate({ children }: { children: ReactNode }) {
  // kitbash:gate
  return <>{children}</>;
}
