/// <reference types="vite/client" />

/**
 * The environment this build was made for.
 *
 * Declared rather than inferred so that a typo in an env var is a compile error instead of an
 * `undefined` that only shows up as a failed redirect in a browser.
 */
interface ImportMetaEnv {
  /** The OIDC issuer: the stub `docker compose up` starts, or the company's own (§18). */
  readonly VITE_OIDC_ISSUER?: string;
  readonly VITE_OIDC_CLIENT_ID?: string;
  /** `'true'` only in test and component builds, where there is no identity provider to talk to. */
  readonly VITE_OIDC_DISABLED?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
