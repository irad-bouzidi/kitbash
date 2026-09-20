// vitest/config re-exports Vite's defineConfig with the `test` block typed, so the
// unit-test setup lives beside the build config instead of in a second file that
// has to be kept in step with it.
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import path from 'node:path';

const apiProxy = {
  '/api': {
    target: process.env.KITBASH_API_URL ?? 'http://localhost:8080',
    changeOrigin: true,
  },
};

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': path.resolve(import.meta.dirname, 'src') },
  },
  // The API is proxied rather than called cross-origin, so development needs no CORS
  // configuration on the server and the download stays a same-origin navigation.
  // `preview` carries the same proxy because the Playwright smoke test runs against
  // the production build.
  server: { port: 5173, proxy: apiProxy },
  preview: { port: 4173, proxy: apiProxy },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    // There is no identity provider in jsdom, and starting an OIDC redirect from a component
    // test would be a navigation to nowhere. The component tests are about what the wizard
    // renders; who may see it is asserted against the real filter chain, on the server.
    env: { VITE_OIDC_DISABLED: 'true' },
    css: true,
    // Playwright specs live in e2e/ and are run by `pnpm test:e2e`; Vitest would
    // otherwise try to execute them and fail on the missing test runner.
    exclude: ['node_modules/**', 'e2e/**', 'dist/**'],
  },
});
