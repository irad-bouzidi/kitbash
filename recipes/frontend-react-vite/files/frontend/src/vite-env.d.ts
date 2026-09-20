/// <reference types="vite/client" />

/**
 * Only the variables this application actually reads. Declaring them rather than relying on
 * Vite's index signature means a typo in `import.meta.env.VITE_API_BSAE_URL` is a typecheck
 * failure instead of `undefined` at runtime.
 */
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
