package dev.kitbash.core.pack;

import dev.kitbash.core.workspace.GeneratedFile;
import dev.kitbash.core.workspace.Workspace;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes a zip whose bytes depend only on its contents.
 *
 * <p>Two generations from the same input must produce the same file, byte for byte: that is what
 * makes the cache key sound and the reproducibility claim true (§4). It is cheap to get right now
 * and miserable to retrofit, so it is written here rather than left for the caching task.
 *
 * <p>What varies between runs in a naive zip, and what is done about it:
 *
 * <ul>
 *   <li><b>Timestamps</b> — every entry is stamped 1980-01-01 00:00:00, the earliest a DOS
 *       timestamp can express.
 *   <li><b>Entry order</b> — entries are sorted by path, so a map's iteration order cannot reach
 *       the output.
 *   <li><b>File modes</b> — carried explicitly in the external attributes, so {@code gradlew}
 *       arrives executable instead of arriving broken.
 *   <li><b>Compression</b> — a fixed deflate level; the JDK's deflater is deterministic for a
 *       given level and input.
 * </ul>
 *
 * <p>{@code java.util.zip.ZipOutputStream} cannot express Unix modes, which is why the format is
 * written out here rather than delegated.
 */
public final class DeterministicZipWriter {

    /** 1980-01-01 00:00:00 in the DOS encoding: the fixed stamp every entry carries. */
    private static final int DOS_TIME = 0;

    private static final int DOS_DATE = (1 << 5) | 1;

    private static final int DIRECTORY_MODE = 0_040755;

    private static final int COMPRESSION_LEVEL = Deflater.DEFAULT_COMPRESSION;

    private static final int LOCAL_HEADER_SIGNATURE = 0x04034b50;
    private static final int CENTRAL_HEADER_SIGNATURE = 0x02014b50;
    private static final int END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50;

    /** "Made by" Unix (3), zip version 3.0 — what tells a reader the mode bits are meaningful. */
    private static final int VERSION_MADE_BY = (3 << 8) | 30;

    private static final int VERSION_NEEDED = 20;
    private static final int METHOD_DEFLATED = 8;
    private static final int METHOD_STORED = 0;

    private static final int MAX_ENTRIES = 0xFFFF;
    private static final long MAX_SIZE = 0xFFFFFFFFL;

    private DeterministicZipWriter() {}

    /**
     * Streams {@code workspace} to {@code out}, every path prefixed with {@code rootDirectory} so
     * unzipping produces one folder rather than scattering files into the current directory.
     *
     * <p>Written straight to the stream rather than buffered: the archive would fit in memory
     * today, but the streaming shape is what the later phases need.
     */
    public static void write(Workspace workspace, String rootDirectory, OutputStream out) throws IOException {
        Map<String, GeneratedFile> entries = prefixed(workspace, rootDirectory);
        if (entries.size() > MAX_ENTRIES) {
            throw new IOException("too many entries for a non-zip64 archive: " + entries.size());
        }

        CountingOutputStream counting = new CountingOutputStream(out);
        List<CentralDirectoryEntry> central = new ArrayList<>(entries.size());

        for (Map.Entry<String, GeneratedFile> entry : entries.entrySet()) {
            central.add(writeEntry(counting, entry.getKey(), entry.getValue()));
        }

        long centralDirectoryOffset = counting.count();
        for (CentralDirectoryEntry entry : central) {
            entry.writeTo(counting);
        }
        long centralDirectorySize = counting.count() - centralDirectoryOffset;

        writeEndOfCentralDirectory(counting, central.size(), centralDirectorySize, centralDirectoryOffset);
        counting.flush();
    }

    /**
     * Every file under {@code rootDirectory}, plus an explicit entry for each directory on the way
     * to it, in lexicographic order. Directory entries are not strictly required — unzip creates
     * missing parents — but archive browsers show an empty-looking tree without them.
     */
    private static Map<String, GeneratedFile> prefixed(Workspace workspace, String rootDirectory) {
        String root = rootDirectory.endsWith("/") ? rootDirectory : rootDirectory + "/";

        TreeSet<String> directories = new TreeSet<>();
        directories.add(root);
        for (String path : workspace.files().keySet()) {
            String full = root + path;
            for (int slash = full.indexOf('/'); slash >= 0; slash = full.indexOf('/', slash + 1)) {
                directories.add(full.substring(0, slash + 1));
            }
        }

        TreeSet<String> names = new TreeSet<>(directories);
        workspace.files().keySet().forEach(path -> names.add(root + path));

        Map<String, GeneratedFile> ordered = new LinkedHashMap<>();
        for (String name : names) {
            ordered.put(name, name.endsWith("/") ? null : workspace.get(name.substring(root.length())));
        }
        return ordered;
    }

    private static CentralDirectoryEntry writeEntry(CountingOutputStream out, String name, GeneratedFile file)
            throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        boolean directory = file == null;
        byte[] content = directory ? new byte[0] : file.content();

        CRC32 crc = new CRC32();
        crc.update(content);

        byte[] payload = directory ? content : deflate(content);
        // A file that deflates larger than it started (tiny files, already-compressed bytes) is
        // stored instead, which is both smaller and what every other zip writer does.
        int method = directory || payload.length >= content.length ? METHOD_STORED : METHOD_DEFLATED;
        if (method == METHOD_STORED) {
            payload = content;
        }

        if (content.length > MAX_SIZE || payload.length > MAX_SIZE) {
            throw new IOException("entry too large for a non-zip64 archive: " + name);
        }

        long offset = out.count();

        writeInt(out, LOCAL_HEADER_SIGNATURE);
        writeShort(out, VERSION_NEEDED);
        writeShort(out, 0); // general purpose flags: none — sizes are known before the data
        writeShort(out, method);
        writeShort(out, DOS_TIME);
        writeShort(out, DOS_DATE);
        writeInt(out, (int) crc.getValue());
        writeInt(out, payload.length);
        writeInt(out, content.length);
        writeShort(out, nameBytes.length);
        writeShort(out, 0); // extra field length: none, because there is no timestamp to record
        out.write(nameBytes);
        out.write(payload);

        int mode = directory ? DIRECTORY_MODE : file.unixMode();
        return new CentralDirectoryEntry(
                nameBytes, method, (int) crc.getValue(), payload.length, content.length, mode, offset);
    }

    private static byte[] deflate(byte[] content) {
        Deflater deflater = new Deflater(COMPRESSION_LEVEL, true);
        try {
            deflater.setInput(content);
            deflater.finish();
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(Math.max(32, content.length / 2));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                compressed.write(buffer, 0, deflater.deflate(buffer));
            }
            return compressed.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static void writeEndOfCentralDirectory(
            OutputStream out, int entryCount, long centralDirectorySize, long centralDirectoryOffset)
            throws IOException {
        writeInt(out, END_OF_CENTRAL_DIRECTORY_SIGNATURE);
        writeShort(out, 0); // this disk
        writeShort(out, 0); // disk with the start of the central directory
        writeShort(out, entryCount);
        writeShort(out, entryCount);
        writeInt(out, (int) centralDirectorySize);
        writeInt(out, (int) centralDirectoryOffset);
        writeShort(out, 0); // archive comment length: a comment would be a place for a timestamp
    }

    private record CentralDirectoryEntry(
            byte[] name, int method, int crc, int compressedSize, int size, int unixMode, long localHeaderOffset) {

        void writeTo(OutputStream out) throws IOException {
            writeInt(out, CENTRAL_HEADER_SIGNATURE);
            writeShort(out, VERSION_MADE_BY);
            writeShort(out, VERSION_NEEDED);
            writeShort(out, 0);
            writeShort(out, method);
            writeShort(out, DOS_TIME);
            writeShort(out, DOS_DATE);
            writeInt(out, crc);
            writeInt(out, compressedSize);
            writeInt(out, size);
            writeShort(out, name.length);
            writeShort(out, 0); // extra
            writeShort(out, 0); // comment
            writeShort(out, 0); // disk number start
            writeShort(out, 0); // internal attributes
            writeInt(out, unixMode << 16); // external attributes: the Unix mode lives here
            writeInt(out, (int) localHeaderOffset);
            out.write(name);
        }
    }

    private static void writeShort(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    /** Tracks the byte offset, which the central directory has to record for each entry. */
    private static final class CountingOutputStream extends OutputStream {

        private final OutputStream delegate;
        private long count;

        private CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        long count() {
            return count;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }
    }
}
