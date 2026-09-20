import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * §8: "the wizard renders itself from that document, so adding a recipe is a backend-only change.
 * Resist every temptation to hardcode an option name in React."
 *
 * That is a rule somebody breaks the first time a special case is convenient, and the breakage is
 * invisible — the app keeps working, right up until a recipe is added and the wizard does not
 * change. So it is a test: no option id and no option label from the catalog may appear anywhere
 * in `web/src`, outside the generated client.
 */

const WEB_SRC = resolve(import.meta.dirname, '..');
const CATALOG = resolve(WEB_SRC, '../../recipes/_catalog.yaml');

/**
 * Files exempt from the rule, each with the task that removes it.
 *
 * Empty, and meant to stay that way. It held the phase-0 form until `kitbash-16` replaced it with
 * the metadata-driven wizard; the assertion at the bottom fails if an exemption ever outlives the
 * file it excuses, which is the only way a temporary allowance stays temporary.
 */
const EXEMPT: string[] = [];

/** Generated from the server's OpenAPI document; it is allowed to name what the server names. */
const GENERATED = 'lib/api/';

function sourceFiles(directory: string): string[] {
  return readdirSync(directory).flatMap((entry) => {
    const path = join(directory, entry);
    if (statSync(path).isDirectory()) return sourceFiles(path);
    return /\.(ts|tsx)$/.test(path) ? [path] : [];
  });
}

/**
 * The slot ids and labels the catalog declares.
 *
 * Read with a regex rather than a YAML parser on purpose: pulling a parser into the web build to
 * read one file in one test is a dependency the application would then carry around forever.
 */
function catalogNames(): { ids: string[]; labels: string[] } {
  const yaml = readFileSync(CATALOG, 'utf8');
  const slotBlock = yaml.slice(yaml.indexOf('groups:'));
  const ids = [...slotBlock.matchAll(/^\s*- id: (\w+)$/gm)].map((match) => match[1]!);
  const labels = [...slotBlock.matchAll(/^\s*label: (.+)$/gm)].map((match) => match[1]!.trim());
  return { ids, labels };
}

describe('the wizard renders itself from /metadata', () => {
  const files = sourceFiles(WEB_SRC)
    .map((path) => relative(WEB_SRC, path).replaceAll('\\', '/'))
    .filter((path) => !path.startsWith(GENERATED));

  it('has something to check', () => {
    const { ids, labels } = catalogNames();
    expect(ids.length).toBeGreaterThan(3);
    expect(labels.length).toBeGreaterThan(3);
    expect(files.length).toBeGreaterThan(3);
  });

  it('never names an option id the catalog declares', () => {
    const { ids } = catalogNames();

    for (const file of files) {
      if (EXEMPT.includes(file)) continue;
      const source = readFileSync(join(WEB_SRC, file), 'utf8');
      for (const id of ids) {
        expect(
          new RegExp(`['"\`]${id}['"\`]`).test(source),
          `${file} names the option '${id}'. The catalog is data: read it from /api/v1/metadata (§8).`,
        ).toBe(false);
      }
    }
  });

  it('never spells out an option label the catalog carries', () => {
    const { labels } = catalogNames();

    for (const file of files) {
      if (EXEMPT.includes(file)) continue;
      const source = readFileSync(join(WEB_SRC, file), 'utf8');
      for (const label of labels) {
        // Word boundaries, not substrings. A label is a whole word: the query library's name
        // contains one of them as a fragment, and a check that cannot tell the difference is a
        // check somebody eventually disables.
        const boundary = new RegExp(`\\b${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`);
        expect(
          boundary.test(source),
          `${file} spells out the label '${label}'. Every human-readable string comes from the server (§8).`,
        ).toBe(false);
      }
    }
  });

  it('exempts only files that still exist, so an exemption cannot outlive its file', () => {
    // kitbash-16 deletes the phase-0 form; when it does, this test fails until the exemption
    // goes with it, which is the only way a temporary allowance stays temporary.
    for (const exempt of EXEMPT) {
      expect(files, `${exempt} is exempt but no longer exists — remove it from EXEMPT`).toContain(
        exempt,
      );
    }
  });
});
