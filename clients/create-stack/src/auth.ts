/**
 * Getting a token in a terminal (§44).
 *
 * <h2>Device code, not a browser redirect</h2>
 *
 * A redirect flow assumes a browser on the machine running the tool. `npx create-stack` over SSH,
 * in a container, or on a build box has no browser and no loopback the provider can reach. The
 * device-code flow degrades honestly: it prints a URL and a code, and the user completes it
 * wherever they do have a browser.
 *
 * <h2>KITBASH_TOKEN first</h2>
 *
 * §44 wants scripted use, and a script cannot complete an interactive flow. An environment
 * variable is the path CI takes, and taking it first means the interactive flow is never started
 * for a caller that already has a token.
 */
export interface DeviceCodeResponse {
  device_code: string;
  user_code: string;
  verification_uri: string;
  verification_uri_complete?: string;
  interval?: number;
  expires_in?: number;
}

export interface AuthOptions {
  issuer: string;
  clientId: string;
  out?: (line: string) => void;
}

/** The token to use, or undefined when the API is running without an issuer configured. */
export async function acquireToken(options: AuthOptions | undefined): Promise<string | undefined> {
  const fromEnvironment = process.env.KITBASH_TOKEN;
  if (fromEnvironment && fromEnvironment.trim() !== '') return fromEnvironment.trim();
  if (!options) return undefined;
  return deviceCode(options);
}

async function deviceCode(options: AuthOptions): Promise<string> {
  const write = options.out ?? ((line: string) => process.stdout.write(line));
  const discovery = (await fetchJson(`${options.issuer}/.well-known/openid-configuration`)) as {
    device_authorization_endpoint?: string;
    token_endpoint?: string;
  };

  const deviceEndpoint = discovery.device_authorization_endpoint;
  const tokenEndpoint = discovery.token_endpoint;
  if (!deviceEndpoint || !tokenEndpoint) {
    throw new Error(
      `${options.issuer} does not advertise a device-code endpoint. Set KITBASH_TOKEN instead, ` +
        'or sign in through the web wizard and copy its token.',
    );
  }

  const started = (await postForm(deviceEndpoint, {
    client_id: options.clientId,
    scope: 'openid',
  })) as DeviceCodeResponse;

  write(
    `\nSign in to continue.\n  Open ${started.verification_uri_complete ?? started.verification_uri}\n`,
  );
  if (!started.verification_uri_complete) write(`  Enter the code ${started.user_code}\n`);

  // The provider's own interval, because polling faster than it asks is what earns a slow_down.
  const intervalMs = Math.max(1, started.interval ?? 5) * 1000;
  const deadline = Date.now() + (started.expires_in ?? 600) * 1000;

  while (Date.now() < deadline) {
    await new Promise((wake) => setTimeout(wake, intervalMs));
    const answer = (await postForm(tokenEndpoint, {
      grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
      device_code: started.device_code,
      client_id: options.clientId,
    }).catch(() => undefined)) as { access_token?: string; error?: string } | undefined;

    if (answer?.access_token) return answer.access_token;
    // authorization_pending is the normal answer while somebody is still typing.
    if (answer?.error && answer.error !== 'authorization_pending' && answer.error !== 'slow_down') {
      throw new Error(`Sign-in failed: ${answer.error}`);
    }
  }
  throw new Error('Sign-in timed out. Run it again, or set KITBASH_TOKEN.');
}

async function fetchJson(url: string): Promise<unknown> {
  const response = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!response.ok) throw new Error(`${response.status} from ${url}`);
  return response.json();
}

async function postForm(url: string, form: Record<string, string>): Promise<unknown> {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
    body: new URLSearchParams(form).toString(),
  });
  const body: unknown = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = (body as { error?: string }).error;
    if (error) return body;
    throw new Error(`${response.status} from ${url}`);
  }
  return body;
}
