/*
 * The typed API client.
 *
 * `schema.d.ts` is generated from the server's own OpenAPI document (`pnpm gen:api`) and is not
 * edited by hand. This file is the thin layer over `fetch` that uses it, so every request and
 * response in the application is checked against the shape the server actually publishes: change a
 * DTO on the server, regenerate, and the call site stops compiling (§9, §15).
 *
 * Nothing here knows what an option means. The metadata document carries the option ids, labels
 * and types, and this module carries only the request shapes — which is the difference between a
 * client that follows the catalog and one that has to be redeployed when the catalog grows.
 */
import { authorizationHeader } from '@/auth/token';
import type { components, paths } from '@/lib/api/schema';

export type MetadataDocument =
  paths['/api/v1/metadata']['get']['responses']['200']['content']['*/*'];
export type OptionGroup = components['schemas']['Group'];
export type CatalogOption = components['schemas']['Option'];
export type OptionChoice = components['schemas']['Choice'];
export type CatalogVariable = components['schemas']['Variable'];
export type RecipeSummary = components['schemas']['RecipeSummary'];

export type GenerateRequest = components['schemas']['GenerateRequest'];
export type ValidationResponse = components['schemas']['ValidationResponse'];
export type Diagnostic = components['schemas']['Diagnostic'];
export type ResolvedRecipe = components['schemas']['ResolvedRecipe'];

/** An RFC 9457 problem document carrying the §14 fields, which is what every failure returns. */
export interface ProblemDetail {
  title?: string;
  detail?: string;
  error?: string;
  stage?: string;
  hint?: string;
  recipe?: string;
  file?: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail;

  constructor(problem: ProblemDetail, status: number) {
    super(problem.detail ?? problem.title ?? `HTTP ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      // Every endpoint but health is closed (§13), so every request carries the token. One place,
      // because a request that forgets is a 401 the user cannot act on.
      ...authorizationHeader(),
      ...init?.headers,
    },
  });

  if (!response.ok) {
    // The server's own words: §14 gives every error a message and a hint naming the next
    // action, and replacing that with "something went wrong" throws away the answer.
    const problem = (await response.json().catch(() => ({}))) as ProblemDetail;
    throw new ApiError(problem, response.status);
  }

  return (await response.json()) as T;
}

/** The whole catalog. Immutable per catalog digest, so the browser's cache does the work. */
export function fetchMetadata(): Promise<MetadataDocument> {
  return request<MetadataDocument>('/api/v1/metadata');
}

/** Stages 1–2 only, which is what makes this affordable on every debounced change (§6, §8). */
export function validate(selection: GenerateRequest): Promise<ValidationResponse> {
  return request<ValidationResponse>('/api/v1/validate', {
    method: 'POST',
    body: JSON.stringify(selection),
  });
}

export const GENERATE_URL = '/api/v1/generate';

/**
 * Downloads the generated project.
 *
 * <p>This used to be a real form submission, because §9 wanted the browser's own download
 * progress rather than a blob held in the tab. Authentication ended that: a form navigation
 * cannot carry an Authorization header, and the alternatives — the token in a query string, or a
 * cookie session beside the bearer tokens — are both worse than losing a progress bar. A token in
 * a URL ends up in history, in referrers and in access logs.
 *
 * <p>The cost is small in practice. A generated project is a few hundred kilobytes, so the blob
 * exists for about as long as it takes to click; §9's concern was multi-megabyte downloads that
 * appear to hang. The form endpoint stays on the server for clients that are not browsers.
 */
export async function downloadProject(selection: GenerateRequest): Promise<void> {
  const response = await fetch(GENERATE_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authorizationHeader() },
    body: JSON.stringify(selection),
  });

  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as ProblemDetail;
    throw new ApiError(problem, response.status);
  }

  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `${selection.projectName}.zip`;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  } finally {
    // The object URL pins the blob in memory until it is revoked, and the click has already
    // handed the bytes to the download manager by the time this runs.
    URL.revokeObjectURL(url);
  }
}
