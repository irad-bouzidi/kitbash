import { mkdtemp, readFile, readdir, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import { describe, expect, it, beforeAll } from 'vitest';
import { run } from '../src/cli.js';
import { readZip, unpack } from '../src/unpack.js';

/**
 * §44's exit criterion, and the only test here that could not be written any other way:
 *
 * > `npx create-stack` produces the same project the web wizard does for the same selection,
 * > byte for byte — asserted by a test that runs both paths and compares.
 *
 * Both clients build a §7 envelope and post it to `/api/v1/generate`. If that holds, byte equality
 * follows from §4's determinism rather than from luck — so what this really asserts is that the
 * chain has not broken somewhere: that create-stack sends the envelope it was given unchanged, and
 * that unpacking reproduces the archive rather than normalising it.
 *
 * It needs a running API. Skipped when there is not one, and run for real by the `create-stack`
 * job in CI — a parity test that only ever skips is a parity test that proves nothing, which is
 * why the CI job exists rather than leaving this to a developer's laptop.
 */
const API = process.env.KITBASH_API ?? 'http://localhost:8080';
const TOKEN = process.env.KITBASH_TOKEN;

const SELECTION = {
  schemaVersion: 1,
  projectName: 'parity',
  options: {
    backend: 'backend-spring-java',
    buildTool: 'build-gradle-kts',
    database: 'db-postgres-flyway',
    docker: true,
  },
  variables: {
    groupId: 'com.example',
    packageName: 'com.example.parity',
    javaVersion: '21',
    entityName: 'Widget',
    entityTable: 'widgets',
    envPrefix: 'PARITY',
  },
};

let reachable = false;

beforeAll(async () => {
  reachable = await fetch(`${API}/actuator/health`)
    .then((response) => response.ok)
    .catch(() => false);

  // CI sets this. Without it a job whose API failed to start would skip the one test it exists to
  // run and report green — which is the same as not having the test, except that it looks like
  // having one.
  if (!reachable && process.env.KITBASH_REQUIRE_API === 'true') {
    throw new Error(
      `KITBASH_REQUIRE_API is set and ${API} is not answering. The parity test cannot be skipped here.`,
    );
  }
});

describe('create-stack and the web wizard', () => {
  it('produce the same project for the same selection, byte for byte', async ({ skip }) => {
    if (!reachable) skip();

    const directory = await mkdtemp(join(tmpdir(), 'create-stack-parity-'));
    const selectionFile = join(directory, 'selection.json');
    await writeJson(selectionFile, SELECTION);

    // The create-stack path: the whole CLI, including unpacking.
    const viaCli = join(directory, 'cli');
    const status = await run(['--selection', selectionFile, viaCli, '--api', API]);
    expect(status).toBe(0);

    // The web path: the same envelope, posted the way the browser posts it.
    const response = await fetch(`${API}/api/v1/generate`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {}),
      },
      body: JSON.stringify(SELECTION),
    });
    expect(response.ok).toBe(true);
    const viaWeb = join(directory, 'web');
    await unpack(readZip(new Uint8Array(await response.arrayBuffer())), viaWeb);

    const left = await tree(join(viaCli, 'parity'));
    const right = await tree(join(viaWeb, 'parity'));

    expect([...left.keys()].sort()).toEqual([...right.keys()].sort());
    for (const [path, content] of left) {
      // Mode included: a `gradlew` that arrives 0644 through one path and 0755 through the other
      // is two different projects, and a comparison of bytes alone would call them equal.
      expect(content, path).toEqual(right.get(path));
    }
    // A floor, so two empty trees cannot pass by agreeing about nothing. Well under the real
    // count — the git skeleton is excluded above, and this should not have to move when a recipe
    // adds a file.
    expect(left.size).toBeGreaterThan(20);
  });
});

async function writeJson(path: string, value: unknown): Promise<void> {
  const { writeFile } = await import('node:fs/promises');
  await writeFile(path, JSON.stringify(value));
}

/** Every file under a root, as path to "mode:content". */
async function tree(root: string): Promise<Map<string, string>> {
  const files = new Map<string, string>();
  const walk = async (directory: string): Promise<void> => {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const full = join(directory, entry.name);
      if (entry.isDirectory()) {
        // The git skeleton is generated fresh each time and carries timestamps, so it differs
        // between two runs of one selection by design — §4's byte equality is about the project.
        if (entry.name === '.git') continue;
        await walk(full);
      } else {
        const mode = (await stat(full)).mode & 0o111 ? 'x' : '-';
        files.set(relative(root, full), `${mode}:${(await readFile(full)).toString('base64')}`);
      }
    }
  };
  await walk(root);
  return files;
}
