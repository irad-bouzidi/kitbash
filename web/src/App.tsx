import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
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
        <Wizard />
      </div>
    </QueryClientProvider>
  );
}
