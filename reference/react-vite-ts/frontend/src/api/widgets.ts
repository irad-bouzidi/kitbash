/*
 * The only file that knows the shape of the API.
 *
 * Deliberately isolated: when the typed-client option is on, `kitbash-33` replaces exactly
 * this file with a client generated from the backend's OpenAPI document, and nothing else in
 * the application has to change. A fetch call scattered through three components is what makes
 * that swap impossible.
 */

/** Same-origin by default, because the dev server and the container both proxy `/api`. */
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';

export interface Widget {
  id: number;
  name: string;
  quantity: number;
  createdAt: string;
}

export interface CreateWidgetRequest {
  name: string;
  quantity: number;
}

/** An RFC 9457 problem document, which is what the backend returns for every failure. */
interface ProblemDetail {
  title?: string;
  detail?: string;
}

export class ApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE_URL}/api${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  });

  if (!response.ok) {
    // The server's own words, not a generic message: it already explains what went wrong
    // and carries the correlation id needed to find the log line.
    const problem = (await response.json().catch(() => ({}))) as ProblemDetail;
    throw new ApiError(problem.detail ?? problem.title ?? response.statusText, response.status);
  }

  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

export function listWidgets(): Promise<Widget[]> {
  return request<Widget[]>('/widgets');
}

export function createWidget(widget: CreateWidgetRequest): Promise<Widget> {
  return request<Widget>('/widgets', { method: 'POST', body: JSON.stringify(widget) });
}

export function deleteWidget(id: number): Promise<void> {
  return request<void>(`/widgets/${id}`, { method: 'DELETE' });
}
