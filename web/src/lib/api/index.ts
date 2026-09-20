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
export type Preset = components['schemas']['PresetResponse'];
export type Generation = components['schemas']['GenerationResponse'];
export type ShareToken = components['schemas']['ShareResponse'];
export type SharedSelection = components['schemas']['SharedSelection'];
export type Replay = components['schemas']['ReplayResponse'];
export type PresetRequest = components['schemas']['PresetRequest'];
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

  saveBlob(await response.blob(), `${selection.projectName}.zip`);
}

/** Hands a blob to the browser's download manager under a name. */
function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  } finally {
    // The object URL pins the blob in memory until it is revoked, and the click has already
    // handed the bytes to the download manager by the time this runs.
    URL.revokeObjectURL(url);
  }
}

/*
 * Presets (§3, §7, §9).
 *
 * The list is the landing page for a returning user — §9 folds the Implementation Plan's Dashboard
 * into it, because a page of counter tiles earns nothing and what somebody coming back wants is
 * their own stacks.
 */

export function fetchPresets(): Promise<Preset[]> {
  return request<Preset[]>('/api/v1/presets');
}

export function fetchPreset(id: string): Promise<Preset> {
  return request<Preset>(`/api/v1/presets/${id}`);
}

export function createPreset(preset: PresetRequest): Promise<Preset> {
  return request<Preset>('/api/v1/presets', { method: 'POST', body: JSON.stringify(preset) });
}

export function updatePreset(id: string, preset: PresetRequest): Promise<Preset> {
  return request<Preset>(`/api/v1/presets/${id}`, { method: 'PUT', body: JSON.stringify(preset) });
}

export async function deletePreset(id: string): Promise<void> {
  const response = await fetch(`/api/v1/presets/${id}`, {
    method: 'DELETE',
    headers: authorizationHeader(),
  });
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as ProblemDetail;
    throw new ApiError(problem, response.status);
  }
}

/**
 * The one click §9 asks for: a cold load, a preset, a zip.
 *
 * The selection is resolved now rather than when the preset was saved, which is what "tracks
 * latest" means mechanically (§7) — so this deliberately sends nothing but the id.
 */
export async function downloadFromPreset(preset: Preset): Promise<void> {
  const response = await fetch(`/api/v1/presets/${preset.id}/generate`, {
    method: 'POST',
    headers: authorizationHeader(),
  });

  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as ProblemDetail;
    throw new ApiError(problem, response.status);
  }

  saveBlob(await response.blob(), `${preset.selection?.projectName ?? preset.name}.zip`);
}

/*
 * History (§3, §7, §10, §24).
 *
 * A generation is a receipt. The lock on it is the valuable part: it is what makes a row
 * replayable after its zip has expired, and what makes "this used to work" answerable by diffing
 * two of them.
 */

export function fetchGenerations(): Promise<Generation[]> {
  return request<Generation[]>('/api/v1/generations');
}

/**
 * Replays a generation in one of the two modes §24 keeps apart.
 *
 * `exact` asks what you shipped and refuses if the catalog has moved past it; `current` asks what
 * the same choice gives today and says what moved. The response states which ran, because a
 * reproduction that quietly used different versions would be worse than none.
 */
export function replayGeneration(id: string, mode: 'exact' | 'current'): Promise<Replay> {
  return request<Replay>(`/api/v1/generations/${id}/replay?mode=${mode}`, { method: 'POST' });
}

/** Exempts a row and its artifact from the 30-day sweep (§10). */
export function keepGeneration(id: string): Promise<Generation> {
  return request<Generation>(`/api/v1/generations/${id}/keep`, { method: 'POST' });
}

/** The zip again — re-rendered from the stored selection until kitbash-27 brings the object store. */
export async function downloadGeneration(generation: Generation): Promise<void> {
  const response = await fetch(`/api/v1/generations/${generation.id}/download`, {
    method: 'POST',
    headers: authorizationHeader(),
  });

  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as ProblemDetail;
    throw new ApiError(problem, response.status);
  }

  saveBlob(await response.blob(), `${generation.projectName ?? 'project'}.zip`);
}

/*
 * Share links (§8, §9).
 *
 * The URL is the mechanism and the token is the fallback — that ordering is §8's, and it matters:
 * a link that depends on a row stops working when the row expires, while a URL carries the
 * selection itself. These two functions are for the case the ordering allows for, which is a URL
 * too long to paste comfortably.
 */

export function createShareLink(selection: GenerateRequest): Promise<ShareToken> {
  return request<ShareToken>('/api/v1/share', { method: 'POST', body: JSON.stringify(selection) });
}

export function fetchSharedSelection(token: string): Promise<SharedSelection> {
  return request<SharedSelection>(`/api/v1/share/${token}`);
}
