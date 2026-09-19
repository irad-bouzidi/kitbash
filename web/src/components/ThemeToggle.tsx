import { Moon, Sun } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';

const STORAGE_KEY = 'kitbash-theme';

function prefersDark() {
  if (typeof window === 'undefined') return true;
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored) return stored === 'dark';
  } catch {
    // Private browsing: fall through to the media query.
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}

export function ThemeToggle() {
  const [dark, setDark] = useState(prefersDark);

  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark);
    try {
      localStorage.setItem(STORAGE_KEY, dark ? 'dark' : 'light');
    } catch {
      // Not being able to remember the choice is not a reason to fail to apply it.
    }
  }, [dark]);

  return (
    <Button
      variant="ghost"
      size="icon"
      aria-label={dark ? 'Switch to light theme' : 'Switch to dark theme'}
      onClick={() => setDark((current) => !current)}
    >
      {dark ? <Sun aria-hidden /> : <Moon aria-hidden />}
    </Button>
  );
}
