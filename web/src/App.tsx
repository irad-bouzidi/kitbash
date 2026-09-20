import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from 'react-oidc-context';
import { AuthGate } from '@/auth/AuthGate';
import { authEnabled, oidcConfig } from '@/auth/config';
import { ThemeToggle } from '@/components/ThemeToggle';
import { Wizard } from '@/wizard/Wizard';

// The catalog is immutable per digest and the server sends an ETag that is that digest, so
// refetching on focus would be a request that always answers 304.
const queryClient = new QueryClient({
  defaultOptions: { queries: { refetchOnWindowFocus: false, staleTime: Infinity } },
});

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <div className="flex min-h-svh flex-col items-center gap-8 px-6 py-10">
        <header className="flex w-full max-w-5xl items-start justify-between gap-4">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">kitbash</h1>
            <p className="text-sm text-muted-foreground">
              Pick a stack. Get a project that already builds.
            </p>
          </div>
          <ThemeToggle />
        </header>
        <SignedIn>
          <Wizard />
        </SignedIn>
      </div>
    </QueryClientProvider>
  );
}

/**
 * The wizard, behind whatever sign-in this build has.
 *
 * Authentication is a build-time switch rather than a runtime one: the component tests render this
 * tree in jsdom, where starting an OIDC redirect would be a navigation to nowhere. A production
 * build cannot express "no authentication", which is the property worth having.
 */
function SignedIn({ children }: { children: React.ReactNode }) {
  if (!authEnabled) return <>{children}</>;

  return (
    <AuthProvider {...oidcConfig}>
      <AuthGate>{children}</AuthGate>
    </AuthProvider>
  );
}
