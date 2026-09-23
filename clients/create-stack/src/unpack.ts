import { mkdir, writeFile, chmod } from 'node:fs/promises';
import { dirname, join, normalize, resolve, sep } from 'node:path';
import { inflateRawSync } from 'node:zlib';

/**
 * Unpacking the zip, without a dependency.
 *
 * Node has `zlib` and the generated zip is written by this project's own deterministic writer — a
 * small, known subset of the format: stored or deflated entries, no encryption, no spanning, no
 * zip64. A full library would handle formats this tool will never see.
 *
 * The central directory is read rather than the local headers, because a local header may carry a
 * zero size with the real one in a trailing data descriptor. The central directory always has it.
 */
export interface ZipEntry {
  path: string;
  content: Uint8Array;
  executable: boolean;
}

export function readZip(zip: Uint8Array): ZipEntry[] {
  const view = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
  const end = findEndOfCentralDirectory(view);
  const count = view.getUint16(end + 10, true);
  let offset = view.getUint32(end + 16, true);

  const entries: ZipEntry[] = [];
  for (let index = 0; index < count; index++) {
    if (view.getUint32(offset, true) !== 0x02014b50) {
      throw new Error('Not a central directory entry; this zip is not one kitbash produced.');
    }
    const method = view.getUint16(offset + 10, true);
    const compressedSize = view.getUint32(offset + 20, true);
    const nameLength = view.getUint16(offset + 28, true);
    const extraLength = view.getUint16(offset + 30, true);
    const commentLength = view.getUint16(offset + 32, true);
    const externalAttributes = view.getUint32(offset + 38, true);
    const localOffset = view.getUint32(offset + 42, true);
    const name = new TextDecoder().decode(zip.subarray(offset + 46, offset + 46 + nameLength));

    const localNameLength = view.getUint16(localOffset + 26, true);
    const localExtraLength = view.getUint16(localOffset + 28, true);
    const dataStart = localOffset + 30 + localNameLength + localExtraLength;
    const raw = zip.subarray(dataStart, dataStart + compressedSize);

    if (!name.endsWith('/')) {
      entries.push({
        path: name,
        content: method === 0 ? raw : new Uint8Array(inflateRawSync(raw)),
        // The mode lives in the top sixteen bits of the external attributes. A `gradlew` that
        // arrives 0644 is a project whose first documented command fails.
        executable: ((externalAttributes >>> 16) & 0o111) !== 0,
      });
    }
    offset += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

/**
 * Writes the tree, refusing any path that would land outside the target.
 *
 * The zip comes from this project's own API, so a traversing entry would mean the server had been
 * compromised — but a client that unpacks an archive without checking is a client that turns that
 * into arbitrary file write on a developer's machine, and the check costs one comparison.
 */
export async function unpack(entries: ZipEntry[], into: string): Promise<number> {
  const root = resolve(into);
  let written = 0;

  for (const entry of entries) {
    const target = resolve(root, normalize(entry.path));
    if (target !== root && !target.startsWith(root + sep)) {
      throw new Error(`Refusing to write ${entry.path}: it resolves outside ${into}.`);
    }
    await mkdir(dirname(target), { recursive: true });
    await writeFile(target, entry.content);
    if (entry.executable) await chmod(target, 0o755);
    written++;
  }
  return written;
}

/** The single directory a kitbash zip unpacks to, which is the project. */
export function projectDirectory(entries: ZipEntry[], into: string): string {
  const first = entries[0]?.path ?? '';
  const top = first.split('/')[0] ?? '';
  return top ? join(into, top) : into;
}

function findEndOfCentralDirectory(view: DataView): number {
  // Scanned backwards because the record is last and its length varies with a trailing comment.
  for (let offset = view.byteLength - 22; offset >= 0; offset--) {
    if (view.getUint32(offset, true) === 0x06054b50) return offset;
  }
  throw new Error('No end-of-central-directory record; this is not a zip.');
}
