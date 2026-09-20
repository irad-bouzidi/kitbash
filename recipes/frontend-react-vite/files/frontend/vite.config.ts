// vitest/config re-exports Vite's defineConfig with the `test` block typed, so the unit-test
// setup lives beside the build config rather than in a second file that has to be kept in step.
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// The API is proxied rather than called cross-origin, so development needs no CORS
// configuration on the server. `preview` carries the same proxy because the production
// build is what gets smoke-tested.
const apiProxy = {
  '/api': {
    target: process.env.VITE_API_BASE_URL ?? 'http://localhost:8080',
    changeOrigin: true,
  },
};

export default defineConfig({
  plugins: [react()],
  // The project's single .env lives at its root, one level up: §15 wants one file listing
  // every variable the application reads, not one per half of the stack.
  envDir: path.resolve(import.meta.dirname, '..'),
  resolve: {
    alias: { '@': path.resolve(import.meta.dirname, 'src') },
  },
  server: { port: 5173, proxy: apiProxy },
  preview: { port: 4173, proxy: apiProxy },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    exclude: ['node_modules/**', 'dist/**'],
  },
});
