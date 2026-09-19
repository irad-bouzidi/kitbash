package dev.kitbash.core.pack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a zip's central directory directly.
 *
 * <p>{@code java.util.zip.ZipEntry} has no accessor for the external attributes, which is where the
 * Unix mode lives — so the field the writer exists to set is the one field the JDK reader cannot
 * show. Parsing the directory here checks the output against the format rather than against another
 * library's interpretation of it.
 */
final class ZipInspector {

    record Entry(String name, int method, int unixMode, long compressedSize, long size, int dosTime, int dosDate) {}

    private ZipInspector() {}

    static Map<String, Entry> centralDirectory(byte[] archive) {
        ByteBuffer buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);

        int endOfCentralDirectory = -1;
        for (int i = archive.length - 22; i >= 0; i--) {
            if (buffer.getInt(i) == 0x06054b50) {
                endOfCentralDirectory = i;
                break;
            }
        }
        if (endOfCentralDirectory < 0) {
            throw new IllegalArgumentException("not a zip file: no end-of-central-directory record");
        }

        int count = Short.toUnsignedInt(buffer.getShort(endOfCentralDirectory + 10));
        int offset = buffer.getInt(endOfCentralDirectory + 16);

        Map<String, Entry> entries = new LinkedHashMap<>();
        int position = offset;
        for (int i = 0; i < count; i++) {
            if (buffer.getInt(position) != 0x02014b50) {
                throw new IllegalArgumentException("corrupt central directory at entry " + i);
            }
            int method = Short.toUnsignedInt(buffer.getShort(position + 10));
            int dosTime = Short.toUnsignedInt(buffer.getShort(position + 12));
            int dosDate = Short.toUnsignedInt(buffer.getShort(position + 14));
            long compressedSize = Integer.toUnsignedLong(buffer.getInt(position + 20));
            long size = Integer.toUnsignedLong(buffer.getInt(position + 24));
            int nameLength = Short.toUnsignedInt(buffer.getShort(position + 28));
            int extraLength = Short.toUnsignedInt(buffer.getShort(position + 30));
            int commentLength = Short.toUnsignedInt(buffer.getShort(position + 32));
            int unixMode = buffer.getInt(position + 38) >>> 16;

            String name = new String(archive, position + 46, nameLength, StandardCharsets.UTF_8);
            entries.put(name, new Entry(name, method, unixMode, compressedSize, size, dosTime, dosDate));
            position += 46 + nameLength + extraLength + commentLength;
        }
        return entries;
    }
}
