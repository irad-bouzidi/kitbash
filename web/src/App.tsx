import { Phase0Form } from '@/components/Phase0Form';
import { ThemeToggle } from '@/components/ThemeToggle';

export function App() {
  return (
    <div className="min-h-dvh bg-background text-foreground">
      <header className="mx-auto flex max-w-5xl items-center justify-between px-6 py-5">
        <div>
          <p className="text-lg font-semibold tracking-tight">kitbash</p>
          <p className="text-sm text-muted-foreground">Projects that already build.</p>
        </div>
        <ThemeToggle />
      </header>

      <main className="mx-auto flex max-w-5xl justify-center px-6 pb-20 pt-6">
        <Phase0Form />
      </main>
    </div>
  );
}
