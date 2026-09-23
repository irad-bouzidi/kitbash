/**
 * The API, as this client sees it.
 *
 * Deliberately thin. §44 puts offline operation out of scope — that is kitbash-42's binary, a
 * separate tool with different trade-offs — so everything here is a request, and the interesting
 * decisions are about what to do when one fails.
 */
import type { MetadataDocument } from './metadata.js';

/** The §14 envelope. Every failure carries these; a client that invents its own throws them away. */
export interface ProblemDetail {
  title?: string;
  detail?: string;
  error?: string;
  stage?: string;
  hint?: string;
  recipe?: string;
  file?: string;
  field?: string;
  rule?: string;
  selectionHash?: string;
  reference?: string;
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

export interface Diagnostic {
  code?: string;
  message?: string;
  hint?: string;
  optionId?: string | null;
}

export interface ResolvedRecipe {
  id?: string;
  label?: string;
  implied?: boolean;
}

export interface ValidationResponse {
  valid?: boolean;
  catalogDigest?: string;
  recipes?: ResolvedRecipe[];
  conflicts?: Diagnostic[];
  warnings?: Diagnostic[];
}

export interface SelectionEnvelope {
  schemaVersion: number;
  projectName: string;
  options: Record<string, string | boolean>;
  variables: Record<string, string>;
}

export class Api {
  private metadataCache?: { etag: string | null; document: MetadataDocument };

  constructor(
    private readonly baseUrl: string,
    private readonly token: string | undefined,
  ) {}

  /**
   * The catalog, revalidated by entity tag.
   *
   * §44 asks for this to keep startup fast. The tag **is** the catalog digest, so a 304 is proof
   * the catalog has not moved — which is a stronger statement than a cache hit usually gets to
   * make, and is why this can be trusted rather than timed out.
   */
  async metadata(): Promise<MetadataDocument> {
    const headers: Record<string, string> = { Accept: 'application/json', ...this.authorization() };
    if (this.metadataCache?.etag) headers['If-None-Match'] = this.metadataCache.etag;

    const response = await fetch(`${this.baseUrl}/api/v1/metadata`, { headers });
    if (response.status === 304 && this.metadataCache) return this.metadataCache.document;
    await this.refuseIfFailed(response);

    const document = (await response.json()) as MetadataDocument;
    this.metadataCache = { etag: response.headers.get('etag'), document };
    return document;
  }

  /** Stages 1–2 only, which is what makes this affordable after every answer (§6, §8). */
  async validate(selection: SelectionEnvelope): Promise<ValidationResponse> {
    const response = await fetch(`${this.baseUrl}/api/v1/validate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...this.authorization() },
      body: JSON.stringify(selection),
    });
    await this.refuseIfFailed(response);
    return (await response.json()) as ValidationResponse;
  }

  /** The zip, as bytes. The same endpoint the web wizard posts to, with the same envelope. */
  async generate(selection: SelectionEnvelope): Promise<Uint8Array> {
    const response = await fetch(`${this.baseUrl}/api/v1/generate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...this.authorization() },
      body: JSON.stringify(selection),
    });
    await this.refuseIfFailed(response);
    return new Uint8Array(await response.arrayBuffer());
  }

  /** A saved preset, resolved against today's catalog rather than the one it was saved with. */
  async preset(id: string): Promise<{ selection: SelectionEnvelope; name?: string }> {
    const response = await fetch(`${this.baseUrl}/api/v1/presets/${encodeURIComponent(id)}`, {
      headers: { Accept: 'application/json', ...this.authorization() },
    });
    await this.refuseIfFailed(response);
    return (await response.json()) as { selection: SelectionEnvelope; name?: string };
  }

  private authorization(): Record<string, string> {
    return this.token ? { Authorization: `Bearer ${this.token}` } : {};
  }

  /**
   * A failure, in the server's own words.
   *
   * §14 gives every error a message and a hint naming the next action, and §44 requires this tool
   * to honour that envelope rather than replace it. A body that is not JSON is still a failure
   * worth reporting, so it becomes a minimal envelope rather than a parse error.
   */
  private async refuseIfFailed(response: Response): Promise<void> {
    if (response.ok) return;
    const problem = (await response.json().catch(() => ({
      title: `HTTP ${response.status}`,
      detail: `${response.status} from ${response.url}`,
    }))) as ProblemDetail;
    throw new ApiError(problem, response.status);
  }
}
