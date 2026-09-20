import { type ReactNode, useEffect } from 'react';
import { useAuth } from 'react-oidc-context';
import { Button } from '@/components/ui/button';
import { useNavigate } from 'react-router-dom';
import { rememberWhereWeWere, takeReturnTo } from '@/auth/config';
import { setAccessToken } from '@/auth/token';

/**
 * Nothing renders until there is a token.
 *
 * §13 closes every endpoint but health, so a wizard rendered before sign-in would be a screen of
 * fields whose first request is a 401. Redirecting straight away is the right default for an
 * internal tool: the identity provider almost always has a session already, so the user sees a
 * flicker rather than a login screen.
 *
 * The manual button exists for the case where it does not — an expired SSO session, a stub issuer
 * on a laptop — because a page that silently fails to redirect leaves nothing to click.
 */
export function AuthGate({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const navigate = useNavigate();

  // Published during render, not in an effect, and that ordering is the whole point: React runs
  // a child's effects before its parent's, so the wizard's first request would go out before this
  // component's effect had handed over the token. The symptom was a 401 on the first /metadata
  // followed by a silent retry — invisible unless you watch the network tab, and a wasted round
  // trip on every sign-in. Writing to a module variable is idempotent, so doing it here is safe.
  setAccessToken(auth.user?.access_token ?? null);

  useEffect(() => {
    if (!auth.isLoading && !auth.isAuthenticated && !auth.activeNavigator && !auth.error) {
      rememberWhereWeWere();
      void auth.signinRedirect();
    }
  }, [auth]);

  // The sign-in round trip lands on the origin, because that is the redirect_uri an identity
  // provider registers — so a deep link has to be restored afterwards, through the router rather
  // than through history.replaceState, which React Router does not hear.
  useEffect(() => {
    if (!auth.isAuthenticated) return;
    const wasGoingTo = takeReturnTo();
    if (wasGoingTo && wasGoingTo !== window.location.pathname + window.location.search) {
      void navigate(wasGoingTo, { replace: true });
    }
  }, [auth.isAuthenticated, navigate]);

  if (auth.isLoading || auth.activeNavigator) {
    return <Status>Signing in…</Status>;
  }

  if (auth.error) {
    return (
      <Status>
        <p className="text-sm text-destructive">Sign-in failed: {auth.error.message}</p>
        <Button
          onClick={() => {
            rememberWhereWeWere();
            void auth.signinRedirect();
          }}
        >
          Try again
        </Button>
      </Status>
    );
  }

  if (!auth.isAuthenticated) {
    return (
      <Status>
        <Button
          onClick={() => {
            rememberWhereWeWere();
            void auth.signinRedirect();
          }}
        >
          Sign in
        </Button>
      </Status>
    );
  }

  return <>{children}</>;
}

function Status({ children }: { children: ReactNode }) {
  return (
    <div className="flex w-full max-w-5xl flex-col items-center gap-4 py-20 text-sm text-muted-foreground">
      {children}
    </div>
  );
}
