import { describe, expect, it } from 'vitest';
import { promptsFrom, reasonUnavailable } from '../src/metadata.js';
import { readZip } from '../src/unpack.js';

/**
 * §8's claim, for the terminal: the metadata document carries everything needed to render an
 * interface, so a second client is a rendering problem rather than a product rewrite.
 *
 * <p>Invented option ids throughout, exactly as the web wizard's tests use. If the prompts come
 * out right for a catalog full of gizmos and flourishes, they come out right because the document
 * said so — which is what makes adding a recipe a backend-only change for this tool too.
 */
describe('prompts are the catalog, rendered', () => {
  it('follow the document group order, so this and the wizard read the same way', () => {
    const groups = promptsFrom({
      groups: [
        { id: 'last', order: 9, options: [] },
        { id: 'first', order: 1, options: [] },
        { id: 'middle', order: 4, options: [] },
      ],
    });

    // §44 asks for this by name. Somebody who has used the wizard should recognise the CLI, and a
    // different order makes one catalog feel like two products.
    expect(groups.map((group) => group.id)).toEqual(['first', 'middle', 'last']);
  });

  it('treats a group with no order as first rather than dropping it', () => {
    const groups = promptsFrom({ groups: [{ id: 'ordered', order: 2 }, { id: 'unordered' }] });

    expect(groups.map((group) => group.id)).toEqual(['unordered', 'ordered']);
  });

  it('skips an option none of the selected recipes declares, and says which would', () => {
    // §9 keeps a blocked control visible with its reason, because hiding it makes the catalog feel
    // arbitrary. A terminal has no disabled state; naming the recipe is the nearest true
    // equivalent, and silence is the thing to avoid.
    const reason = reasonUnavailable({ id: 'tint', availableWhen: ['contraption-alpha'] }, [
      'gizmo-beta',
    ]);

    expect(reason).toBe('applies when contraption-alpha is selected');
  });

  it('asks about an option once any declaring recipe is selected', () => {
    // A list since kitbash-30: two recipes can declare one option, and it applies when either is
    // chosen. Requiring all of them would hide a control that applies.
    expect(
      reasonUnavailable({ id: 'architecture', availableWhen: ['alpha', 'beta'] }, ['beta']),
    ).toBeUndefined();
  });

  it('asks about a slot unconditionally, because a slot belongs to no recipe', () => {
    expect(reasonUnavailable({ id: 'backend', availableWhen: [] }, [])).toBeUndefined();
  });
});

/**
 * The zip reader, against archives built by hand.
 *
 * <p>Written without a dependency because the format this meets is a small, known subset — the one
 * this project's own deterministic writer produces. These assert the two properties the unpacking
 * actually depends on: sizes come from the central directory, and the mode survives.
 */
describe('reading a zip', () => {
  it('refuses something that is not a zip, rather than producing an empty project', () => {
    expect(() => readZip(new TextEncoder().encode('not a zip at all'))).toThrow(/not a zip/i);
  });

  it('reads a stored entry and its executable bit', () => {
    const zip = storedZip('gradlew', 'echo hi\n', 0o100755);

    const entries = readZip(zip);

    expect(entries).toHaveLength(1);
    expect(new TextDecoder().decode(entries[0]!.content)).toBe('echo hi\n');
    // A gradlew that arrives 0644 is a project whose first documented command fails.
    expect(entries[0]!.executable).toBe(true);
  });

  it('leaves a non-executable file non-executable', () => {
    const entries = readZip(storedZip('README.md', '# hi\n', 0o100644));

    expect(entries[0]!.executable).toBe(false);
  });
});

/** One stored (uncompressed) entry, which is all these assertions need. */
function storedZip(name: string, content: string, mode: number): Uint8Array {
  const nameBytes = new TextEncoder().encode(name);
  const data = new TextEncoder().encode(content);
  const local = 30 + nameBytes.length;
  const centralStart = local + data.length;
  const total = centralStart + 46 + nameBytes.length + 22;

  const buffer = new Uint8Array(total);
  const view = new DataView(buffer.buffer);

  view.setUint32(0, 0x04034b50, true);
  view.setUint16(8, 0, true); // stored
  view.setUint32(18, data.length, true);
  view.setUint32(22, data.length, true);
  view.setUint16(26, nameBytes.length, true);
  buffer.set(nameBytes, 30);
  buffer.set(data, local);

  view.setUint32(centralStart, 0x02014b50, true);
  view.setUint16(centralStart + 10, 0, true);
  view.setUint32(centralStart + 20, data.length, true);
  view.setUint32(centralStart + 24, data.length, true);
  view.setUint16(centralStart + 28, nameBytes.length, true);
  view.setUint32(centralStart + 38, mode << 16, true);
  view.setUint32(centralStart + 42, 0, true);
  buffer.set(nameBytes, centralStart + 46);

  const end = centralStart + 46 + nameBytes.length;
  view.setUint32(end, 0x06054b50, true);
  view.setUint16(end + 8, 1, true);
  view.setUint16(end + 10, 1, true);
  view.setUint32(end + 12, 46 + nameBytes.length, true);
  view.setUint32(end + 16, centralStart, true);
  return buffer;
}
