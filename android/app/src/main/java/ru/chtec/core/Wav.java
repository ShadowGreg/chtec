package ru.chtec.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/** Склейка WAV-файлов встроенного голоса в один: приводит к 16 бит моно с частотой первого фрагмента. */
public final class Wav {
    private Wav() {}

    /** Формат и положение данных PCM внутри WAV-файла. */
    public static final class Info {
        public int rate, channels, bits;
        long dataOffset, dataLength;
    }

    public static Info info(File f) throws IOException {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            byte[] h = new byte[12];
            r.readFully(h);
            if (h[0] != 'R' || h[1] != 'I' || h[2] != 'F' || h[3] != 'F') throw new IOException("Не WAV");
            Info info = new Info();
            long pos = 12;
            while (pos + 8 <= r.length()) {
                r.seek(pos);
                byte[] ch = new byte[8];
                r.readFully(ch);
                String id = new String(ch, 0, 4, "US-ASCII");
                long size = le32(ch, 4);
                if (id.equals("fmt ")) {
                    byte[] fmt = new byte[16];
                    r.readFully(fmt);
                    info.channels = le16(fmt, 2);
                    info.rate = (int) le32(fmt, 4);
                    info.bits = le16(fmt, 14);
                } else if (id.equals("data")) {
                    info.dataOffset = pos + 8;
                    // Некоторые движки пишут размер 0 или с запасом — берём до конца файла.
                    long rest = r.length() - info.dataOffset;
                    info.dataLength = size == 0 || size > rest ? rest : size;
                    break;
                }
                pos += 8 + size + (size & 1);
            }
            if (info.rate == 0 || info.dataOffset == 0) throw new IOException("Повреждённый WAV");
            return info;
        } finally {
            r.close();
        }
    }

    /** Дописывает звук из WAV в поток как PCM 16 бит моно targetRate. Возвращает число записанных байт. */
    public static long appendPcm(File wav, int targetRate, OutputStream out) throws IOException {
        Info info = info(wav);
        if (info.bits != 16 && info.bits != 8) throw new IOException("Неподдерживаемый WAV: " + info.bits + " бит");
        int frameBytes = info.bits / 8 * info.channels;
        long frames = info.dataLength / frameBytes;
        short[] mono = new short[(int) frames];
        InputStream in = new FileInputStream(wav);
        try {
            skipFully(in, info.dataOffset);
            byte[] fb = new byte[frameBytes];
            for (int i = 0; i < frames; i++) {
                readFully(in, fb);
                int sum = 0;
                for (int c = 0; c < info.channels; c++) {
                    sum += info.bits == 16 ? (short) le16(fb, c * 2) : ((fb[c] & 0xFF) - 128) << 8;
                }
                mono[i] = (short) (sum / info.channels);
            }
        } finally {
            in.close();
        }
        // Линейная передискретизация, если частоты голосов различаются.
        long outFrames = info.rate == targetRate ? frames : frames * targetRate / info.rate;
        byte[] buf = new byte[8192];
        int bp = 0;
        for (long i = 0; i < outFrames; i++) {
            short s;
            if (info.rate == targetRate) {
                s = mono[(int) i];
            } else {
                double src = (double) i * info.rate / targetRate;
                int a = (int) src;
                int b = Math.min(a + 1, mono.length - 1);
                double t = src - a;
                s = (short) Math.round(mono[a] * (1 - t) + mono[b] * t);
            }
            buf[bp++] = (byte) s;
            buf[bp++] = (byte) (s >> 8);
            if (bp == buf.length) {
                out.write(buf, 0, bp);
                bp = 0;
            }
        }
        out.write(buf, 0, bp);
        return outFrames * 2;
    }

    public static long appendSilence(int ms, int rate, OutputStream out) throws IOException {
        long bytes = (long) rate * ms / 1000 * 2;
        byte[] zero = new byte[8192];
        long left = bytes;
        while (left > 0) {
            int n = (int) Math.min(zero.length, left);
            out.write(zero, 0, n);
            left -= n;
        }
        return bytes;
    }

    public static void writeHeader(OutputStream out, int rate, long dataLength) throws IOException {
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        put32(h, 4, 36 + dataLength);
        put(h, 8, "WAVE");
        put(h, 12, "fmt ");
        put32(h, 16, 16);
        put16(h, 20, 1); // PCM
        put16(h, 22, 1); // моно
        put32(h, 24, rate);
        put32(h, 28, rate * 2L);
        put16(h, 32, 2);
        put16(h, 34, 16);
        put(h, 36, "data");
        put32(h, 40, dataLength);
        out.write(h);
    }

    private static void put(byte[] b, int off, String s) {
        for (int i = 0; i < 4; i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void put16(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }

    private static void put32(byte[] b, int off, long v) {
        for (int i = 0; i < 4; i++) b[off + i] = (byte) (v >> (8 * i));
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    private static long le32(byte[] b, int off) {
        return (b[off] & 0xFFL) | (b[off + 1] & 0xFFL) << 8 | (b[off + 2] & 0xFFL) << 16 | (b[off + 3] & 0xFFL) << 24;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) throw new IOException("Повреждённый WAV");
            n -= s;
        }
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) throw new IOException("Повреждённый WAV");
            off += r;
        }
    }
}
