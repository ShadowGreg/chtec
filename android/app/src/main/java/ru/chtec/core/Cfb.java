package ru.chtec.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Минимальное чтение составного файла OLE (CFB) — контейнера старых документов Word .doc. */
final class Cfb {
    private static final int END = 0xFFFFFFFE;
    private static final int FREE = 0xFFFFFFFF;

    private final ByteBuffer buf;
    private final int sectorSize;
    private final int miniSectorSize;
    private final int miniCutoff;
    private final int[] fat;
    private final int[] miniFat;
    private final List<Entry> entries = new ArrayList<>();
    private byte[] miniStream = new byte[0];

    private static final class Entry {
        String name;
        int type;
        int start;
        long size;
    }

    Cfb(byte[] data) throws IOException {
        buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        sectorSize = 1 << u16(0x1E);
        miniSectorSize = 1 << u16(0x20);
        int numFat = buf.getInt(0x2C);
        int firstDir = buf.getInt(0x30);
        miniCutoff = buf.getInt(0x38);
        int firstMiniFat = buf.getInt(0x3C);
        int firstDifat = buf.getInt(0x44);

        // Список секторов таблицы FAT: 109 в заголовке + цепочка DIFAT.
        List<Integer> fatSectors = new ArrayList<>();
        for (int i = 0; i < 109 && fatSectors.size() < numFat; i++) {
            int s = buf.getInt(0x4C + 4 * i);
            if (s != FREE) fatSectors.add(s);
        }
        int difat = firstDifat;
        int guard = 0;
        while (difat != END && difat != FREE && fatSectors.size() < numFat && guard++ < 100000) {
            int off = offset(difat);
            int per = sectorSize / 4 - 1;
            for (int i = 0; i < per && fatSectors.size() < numFat; i++) {
                int s = buf.getInt(off + 4 * i);
                if (s != FREE) fatSectors.add(s);
            }
            difat = buf.getInt(off + 4 * per);
        }
        int per = sectorSize / 4;
        fat = new int[fatSectors.size() * per];
        for (int i = 0; i < fatSectors.size(); i++) {
            int off = offset(fatSectors.get(i));
            for (int j = 0; j < per; j++) fat[i * per + j] = buf.getInt(off + 4 * j);
        }

        byte[] dir = readChain(firstDir, -1);
        ByteBuffer d = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN);
        for (int off = 0; off + 128 <= dir.length; off += 128) {
            Entry e = new Entry();
            int nameLen = d.getShort(off + 0x40) & 0xFFFF;
            e.name = nameLen >= 2 ? new String(dir, off, Math.min(nameLen - 2, 64), StandardCharsets.UTF_16LE) : "";
            e.type = dir[off + 0x42];
            e.start = d.getInt(off + 0x74);
            e.size = d.getInt(off + 0x78) & 0xFFFFFFFFL;
            entries.add(e);
        }

        miniFat = firstMiniFat == END || firstMiniFat == FREE ? new int[0] : toInts(readChain(firstMiniFat, -1));
        if (!entries.isEmpty() && entries.get(0).type == 5) {
            miniStream = readChain(entries.get(0).start, entries.get(0).size);
        }
    }

    boolean exists(String name) {
        return find(name) != null;
    }

    byte[] open(String name) throws IOException {
        Entry e = find(name);
        if (e == null) throw new IOException("Нет потока " + name);
        if (e.size < miniCutoff) return readMini(e.start, e.size);
        return readChain(e.start, e.size);
    }

    private Entry find(String name) {
        for (Entry e : entries) if (e.type == 2 && e.name.equals(name)) return e;
        return null;
    }

    private int u16(int off) {
        return buf.getShort(off) & 0xFFFF;
    }

    private int offset(int sector) throws IOException {
        long off = (long) (sector + 1) * sectorSize;
        if (sector < 0 || off + sectorSize > buf.capacity()) throw new IOException("Повреждённый файл .doc");
        return (int) off;
    }

    private byte[] readChain(int start, long size) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int s = start;
        int guard = 0;
        while (s != END && s != FREE && guard++ <= fat.length) {
            out.write(buf.array(), offset(s), sectorSize);
            if (size >= 0 && out.size() >= size) break;
            if (s >= fat.length) throw new IOException("Повреждённый файл .doc");
            s = fat[s];
        }
        return trim(out.toByteArray(), size);
    }

    private byte[] readMini(int start, long size) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int s = start;
        int guard = 0;
        while (s != END && s != FREE && guard++ <= miniFat.length) {
            int off = s * miniSectorSize;
            if (off + miniSectorSize > miniStream.length) throw new IOException("Повреждённый файл .doc");
            out.write(miniStream, off, miniSectorSize);
            if (out.size() >= size || s >= miniFat.length) break;
            s = miniFat[s];
        }
        return trim(out.toByteArray(), size);
    }

    private static byte[] trim(byte[] b, long size) {
        if (size < 0 || b.length <= size) return b;
        byte[] r = new byte[(int) size];
        System.arraycopy(b, 0, r, 0, r.length);
        return r;
    }

    private static int[] toInts(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int[] r = new int[b.length / 4];
        for (int i = 0; i < r.length; i++) r[i] = bb.getInt(4 * i);
        return r;
    }
}
