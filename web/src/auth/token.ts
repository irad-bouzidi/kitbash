/*
 * The access token, reachable from code that is not a React component.
 *
 * The API client is a plain module over `fetch` — deliberately, since §9 wants every request in
 * the application checked against the server's own OpenAPI document rather than wrapped in hooks.
 * That module cannot call `useAuth()`, so the provider publishes the current token here and the
 * client reads it.
 *
 * A module-level variable rather than a store: there is exactly one signed-in user per tab, and
 * anything more elaborate would be machinery around a single string.
 */
let accessToken: string | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token ?? null;
}

export function currentAccessToken(): string | null {
  return accessToken;
}

/** The Authorization header, or nothing when there is no token to send. */
export function authorizationHeader(): Record<string, string> {
  return accessToken ? { Authorization: `Bearer ${accessToken}` } : {};
}
