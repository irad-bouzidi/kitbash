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
    headers: { 'Content-Type': 'application/json', ...init?.headers },
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

/**
 * Generation is not here on purpose. §9 wants the download to be a real form submission so the
 * browser shows native progress; `fetch` plus a blob URL would hold a multi-megabyte string in
 * the tab and show the user nothing.
 */
export const GENERATE_URL = '/api/v1/generate';
