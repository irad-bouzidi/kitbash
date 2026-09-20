import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from 'react-oidc-context';
import { BrowserRouter, Link, Route, Routes } from 'react-router-dom';
import { AuthGate } from '@/auth/AuthGate';
import { authEnabled, oidcConfig } from '@/auth/config';
import { ThemeToggle } from '@/components/ThemeToggle';
import { PresetDetail } from '@/presets/PresetDetail';
import { PresetsPage } from '@/presets/PresetsPage';
import { Wizard } from '@/wizard/Wizard';

// The catalog is immutable per digest and the server sends an ETag that is that digest, so
// refetching on focus would be a request that always answers 304.
const queryClient = new QueryClient({
  defaultOptions: { queries: { refetchOnWindowFocus: false, staleTime: Infinity } },
});

/**
 * Three routes, and the choice of which one is `/` is §9's.
 *
 * The preset list is the landing page, because §9 folds the Implementation Plan's Dashboard into
 * it: a returning user wants their own stacks, with Generate one click from a cold load. The
 * wizard moves to `/new`, which is also where the URL-as-configuration encoding lives — a link
 * with a selection in it is a link to the wizard, not to a list.
 */
export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <div className="flex min-h-svh flex-col items-center gap-8 px-6 py-10">
          <header className="flex w-full max-w-5xl items-start justify-between gap-4">
            <div>
              <h1 className="text-2xl font-semibold tracking-tight">
                <Link to="/">kitbash</Link>
              </h1>
              <p className="text-sm text-muted-foreground">
                Pick a stack. Get a project that already builds.
              </p>
            </div>
            <ThemeToggle />
          </header>
          <SignedIn>
            <Routes>
              <Route path="/" element={<PresetsPage />} />
              <Route path="/new" element={<Wizard />} />
              <Route path="/presets/:id" element={<PresetDetail />} />
            </Routes>
          </SignedIn>
        </div>
      </BrowserRouter>
    </QueryClientProvider>
  );
}

/**
 * The application, behind whatever sign-in this build has.
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
