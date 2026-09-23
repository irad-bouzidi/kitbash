import { ApiError, type ProblemDetail as Problem } from '@/lib/api';

/**
 * The envelope behind whatever was thrown, or null when there is nothing to render.
 *
 * A thrown value that is not an `ApiError` is a network failure or a bug in this client — neither
 * has an envelope, and inventing one would be the generic fallback §39 forbids. Callers handle that
 * case themselves, with a sentence that fits where they are.
 */
export function asProblem(error: unknown): Problem | null {
  if (error instanceof ApiError) return error.problem;
  return null;
}

/**
 * Whether this error belongs on a particular field.
 *
 * §9 puts an error that names an option **on that option's control**, not in a banner. The server
 * says which one through the envelope's `field` property, so this is a lookup rather than a guess —
 * and nothing here knows what any field means.
 */
export function fieldOf(error: unknown): string | undefined {
  const problem = asProblem(error);
  return problem?.field ?? undefined;
}
