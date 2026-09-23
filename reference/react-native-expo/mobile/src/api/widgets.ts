import Constants from 'expo-constants';

/**
 * The four calls the screen makes, over `fetch`.
 *
 * Hand-written, and the mobile counterpart of the web client's hand-written variant. It exists so
 * the screen imports one path whether or not the typed client is generated: a generated client's
 * shape is the generator's business and changes when it is upgraded, and a screen that imported it
 * directly would change with it.
 *
 * The base URL comes from `app.json`'s `extra` rather than from an environment variable, because a
 * mobile app has no environment at runtime — it is a bundle on a device. `extra` is the value that
 * survives into the bundle and can be changed per build.
 */
const BASE_URL = (Constants.expoConfig?.extra?.apiBaseUrl as string | undefined) ?? 'http://localhost:8080';

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

export class ApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

/** The server's own words where there are any: it explains the failure better than we can. */
async function unwrap(response: Response): Promise<unknown> {
  if (response.ok) {
    return response.status === 204 ? undefined : response.json();
  }
  const problem = (await response.json().catch(() => ({}))) as { title?: string; detail?: string };
  throw new ApiError(problem.detail ?? problem.title ?? response.statusText, response.status);
}

function headers(): Record<string, string> {
  return {
    'Content-Type': 'application/json',
    // kitbash:headers
  };
}

export async function listWidgets(): Promise<Widget[]> {
  return (await unwrap(await fetch(`${BASE_URL}/api/widgets`, { headers: headers() }))) as Widget[];
}

export async function createWidget(widget: CreateWidgetRequest): Promise<Widget> {
  const response = await fetch(`${BASE_URL}/api/widgets`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify(widget),
  });
  return (await unwrap(response)) as Widget;
}

export async function deleteWidget(id: number): Promise<void> {
  await unwrap(await fetch(`${BASE_URL}/api/widgets/${id}`, { method: 'DELETE', headers: headers() }));
}
