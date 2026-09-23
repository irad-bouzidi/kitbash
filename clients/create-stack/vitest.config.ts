import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['test/**/*.test.ts', 'src/**/*.test.ts'],
    // The parity test generates a real project over HTTP; the default five seconds is not enough
    // on a cold API, and a flaky timeout would teach people to rerun rather than to look.
    testTimeout: 60_000,
  },
});
