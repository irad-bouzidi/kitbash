#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { Api, ApiError, type SelectionEnvelope } from './api.js';
import { acquireToken } from './auth.js';
import { interview } from './interview.js';
import { Prompter } from './prompt.js';
import { projectDirectory, readZip, unpack } from './unpack.js';

/**
 * `npx create-stack` (§8, §17, §44).
 *
 * <p>A second client against the same API, and cheap precisely because of the decision §8 made:
 * the metadata endpoint carries everything needed to render an interface, so this is a rendering
 * problem rather than a product rewrite. **Nothing in this package names an option.** Any option
 * knowledge that turns out to be missing from `/metadata` is a gap in the endpoint and belongs
 * fixed there rather than patched here.
 *
 * <p>Offline operation is deliberately not here: that is kitbash-42's self-contained binary, which
 * carries its own catalog and makes different trade-offs. This tool always talks to a server, and
 * shows which catalog that server is serving.
 */
const USAGE = `create-stack — generate a project that already builds

Usage:
  npx create-stack [directory]                 answer prompts drawn from the catalog
  npx create-stack --selection <file> [dir]    non-interactive, from a §7 envelope
  npx create-stack --preset <id> [dir]         non-interactive, from a saved preset
  npx create-stack --version

Options:
  --api <url>        The kitbash API. Default: $KITBASH_API or http://localhost:8080
  --issuer <url>     OIDC issuer for the device-code sign-in. Default: $KITBASH_ISSUER
  --client <id>      OIDC client id. Default: $KITBASH_CLIENT_ID or create-stack
  --dry-run          Resolve and report, write nothing.

Environment:
  KITBASH_TOKEN      A bearer token, which skips the interactive sign-in. What CI uses.
`;

interface Flags {
  directory: string;
  api: string;
  issuer?: string;
  clientId: string;
  selection?: string;
  preset?: string;
  version: boolean;
  help: boolean;
  dryRun: boolean;
}

export function parse(argv: string[]): Flags {
  const flags: Flags = {
    directory: '.',
    api: process.env.KITBASH_API ?? 'http://localhost:8080',
    issuer: process.env.KITBASH_ISSUER,
    clientId: process.env.KITBASH_CLIENT_ID ?? 'create-stack',
    version: false,
    help: false,
    dryRun: false,
  };
  const positional: string[] = [];

  for (let index = 0; index < argv.length; index++) {
    const argument = argv[index]!;
    switch (argument) {
      case '--api':
        flags.api = expect(argv, ++index, argument);
        break;
      case '--issuer':
        flags.issuer = expect(argv, ++index, argument);
        break;
      case '--client':
        flags.clientId = expect(argv, ++index, argument);
        break;
      case '--selection':
        flags.selection = expect(argv, ++index, argument);
        break;
      case '--preset':
        flags.preset = expect(argv, ++index, argument);
        break;
      case '--dry-run':
        flags.dryRun = true;
        break;
      case '--version':
      case '-v':
        flags.version = true;
        break;
      case '--help':
      case '-h':
        flags.help = true;
        break;
      default:
        if (argument.startsWith('-')) throw new Error(`Unknown option '${argument}'.`);
        positional.push(argument);
    }
  }
  if (positional.length > 1) throw new Error('Expected at most one directory.');
  flags.directory = positional[0] ?? '.';
  return flags;
}

function expect(argv: string[], index: number, flag: string): string {
  const value = argv[index];
  if (value === undefined) throw new Error(`${flag} needs a value.`);
  return value;
}

export async function run(argv: string[]): Promise<number> {
  let flags: Flags;
  try {
    flags = parse(argv);
  } catch (failure) {
    process.stderr.write(`${(failure as Error).message}\n\n${USAGE}`);
    return 2;
  }

  if (flags.help) {
    process.stdout.write(USAGE);
    return 0;
  }

  const api = new Api(flags.api, await acquireTokenFor(flags));

  try {
    const metadata = await api.metadata();

    if (flags.version) {
      // The catalog digest, for the same reason the web footer and the binary show it: this tool
      // carries no catalog, but it talks to one, and which one is what makes a bug report
      // actionable.
      process.stdout.write(
        `create-stack ${version()}\ncatalog ${metadata.catalogDigest ?? 'unknown'}\n  from ${flags.api}\n`,
      );
      return 0;
    }

    const selection = await selectionFor(api, metadata, flags);
    const resolution = await api.validate(selection);

    if (resolution.valid === false) {
      for (const conflict of resolution.conflicts ?? []) {
        process.stderr.write(`${conflict.message ?? ''}\n  ${conflict.hint ?? ''}\n`);
      }
      return 1;
    }

    process.stdout.write(
      `\n${(resolution.recipes ?? []).map((recipe) => recipe.label ?? recipe.id).join(', ')}\n`,
    );

    if (flags.dryRun) {
      process.stdout.write('\n--dry-run: nothing written.\n');
      return 0;
    }

    const entries = readZip(await api.generate(selection));
    const written = await unpack(entries, flags.directory);
    const directory = projectDirectory(entries, flags.directory);

    process.stdout.write(
      `\n${written} files into ${directory}\n\n  cd ${directory}\n  docker compose up\n`,
    );
    return 0;
  } catch (failure) {
    return report(failure);
  }
}

/** The §14 envelope, printed as the server sent it — no re-wording, no swallowed detail (§39). */
function report(failure: unknown): number {
  if (failure instanceof ApiError) {
    const problem = failure.problem;
    process.stderr.write(`${problem.error ?? 'FAILED'}: ${problem.detail ?? failure.message}\n`);
    if (problem.hint) process.stderr.write(`  ${problem.hint}\n`);
    if (problem.recipe || problem.file || problem.stage) {
      const facts = [
        problem.recipe && `recipe ${problem.recipe}`,
        problem.file && `file ${problem.file}`,
        problem.stage && `${problem.stage} stage`,
      ];
      process.stderr.write(`  ${facts.filter(Boolean).join(' · ')}\n`);
    }
    return 1;
  }
  process.stderr.write(`${(failure as Error).message}\n`);
  return 1;
}

async function acquireTokenFor(flags: Flags): Promise<string | undefined> {
  if (!flags.issuer) return acquireToken(undefined);
  return acquireToken({ issuer: flags.issuer, clientId: flags.clientId });
}

async function selectionFor(
  api: Api,
  metadata: Awaited<ReturnType<Api['metadata']>>,
  flags: Flags,
): Promise<SelectionEnvelope> {
  if (flags.selection) {
    return JSON.parse(await readFile(resolve(flags.selection), 'utf8')) as SelectionEnvelope;
  }
  if (flags.preset) {
    // Resolved against today's catalog rather than the one it was saved with, which is what
    // "tracks the catalog" means for a preset (§23).
    return (await api.preset(flags.preset)).selection;
  }
  const prompter = new Prompter();
  try {
    return await interview(api, metadata, prompter);
  } finally {
    prompter.close();
  }
}

function version(): string {
  return process.env.npm_package_version ?? '0.1.0';
}

// Guarded, so the tests can import `run` without the module exiting the process.
if (process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop() ?? '\u0000')) {
  run(process.argv.slice(2)).then(
    (status) => process.exit(status),
    (failure: unknown) => {
      process.stderr.write(`${String(failure)}\n`);
      process.exit(1);
    },
  );
}
