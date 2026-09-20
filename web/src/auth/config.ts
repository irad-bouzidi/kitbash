/*
 * Where the identity provider is, and who this application says it is.
 *
 * §18 settles the audience: an internal team behind the SSO they already have. So there is no sign
 * up, no password field and no profile page here — the whole client-side surface of authentication
 * is a redirect out and a token back.
 *
 * Read from the environment rather than hardcoded, because the same build runs against the stub
 * issuer `docker compose up` starts and against the company's real one.
 */
import type { AuthProviderProps } from 'react-oidc-context';
import { WebStorageStateStore } from 'oidc-client-ts';

const issuer = import.meta.env.VITE_OIDC_ISSUER ?? 'http://localhost:9500/kitbash';
const clientId = import.meta.env.VITE_OIDC_CLIENT_ID ?? 'kitbash-web';

export const oidcConfig: AuthProviderProps = {
  authority: issuer,
  client_id: clientId,
  redirect_uri: window.location.origin,
  post_logout_redirect_uri: window.location.origin,
  // Authorization code with PKCE. A single-page application cannot keep a secret, so it does not
  // get one: PKCE is what stops an intercepted code from being redeemable by anyone else.
  response_type: 'code',
  scope: 'openid profile',

  // Session storage rather than local: a token that outlives the tab is a token left behind on a
  // shared machine. The cost is signing in again in a new tab, which against an SSO that already
  // has a session is a redirect the user does not see.
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),

  // The provider's own callback handling leaves the code and state in the address bar; clearing
  // them keeps a reload from replaying a code that has already been redeemed.
  //
  // The selection is restored at the same time. §9 makes the URL the configuration — a link is
  // how somebody shares a stack — and the sign-in round trip replaces that URL with the
  // provider's callback, so a shared link opened by somebody without a session would arrive
  // empty. Stashing the query string before the redirect and putting it back here is what keeps
  // "open this link" working for a signed-out colleague.
  onSigninCallback: () => {
    const selection = readAndClear(RETURN_TO) ?? '';
    window.history.replaceState({}, document.title, window.location.pathname + selection);
  },
};

const RETURN_TO = 'kitbash.returnTo';

/** Called immediately before a sign-in redirect, while the address bar still holds the link. */
export function rememberWhereWeWere(): void {
  try {
    window.sessionStorage.setItem(RETURN_TO, window.location.search);
  } catch {
    // A browser with storage disabled still signs in; it just loses the selection in the link.
  }
}

function readAndClear(key: string): string | null {
  try {
    const value = window.sessionStorage.getItem(key);
    window.sessionStorage.removeItem(key);
    return value;
  } catch {
    return null;
  }
}

/**
 * Whether authentication is configured at all.
 *
 * The wizard's own tests render the application without an identity provider, and starting a
 * redirect from a jsdom test would be a navigation to nowhere. This is the one switch that lets
 * the same component tree run in both places, and it is deliberately a build-time value rather
 * than a runtime toggle — a production build with authentication switched off is not something
 * this should be able to express.
 */
export const authEnabled = import.meta.env.VITE_OIDC_DISABLED !== 'true';
