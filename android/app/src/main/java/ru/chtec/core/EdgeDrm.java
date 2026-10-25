package ru.chtec.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Токен Sec-MS-GEC для онлайн-голосов Microsoft Edge — точный перенос edge_tts/drm.py (edge-tts 7.3.1).
 * Если Microsoft поменяет алгоритм, обновлять нужно этот файл и версию в EdgeTts.
 */
final class EdgeDrm {
    private EdgeDrm() {}

    static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final long MASK = 0xFFFFFFFFL;
    private static final double WIN_EPOCH = 11644473600.0;

    /** Поправка часов (сек), если время телефона расходится с сервером. */
    static volatile double clockSkewSeconds = 0;

    private static final long[] SHA256_INITIAL = {
            0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL, 0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L};
    private static final long[] SHA256_STATE = {
            0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL, 0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L};
    private static final long[] SHA256_SCHEDULE = {
            0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL, 0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L,
            0xF9E70AB6L, 0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L, 0xD258B060L, 0x72766564L};
    private static final long[] K = {
            0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L, 0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
            0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L, 0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
            0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL, 0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
            0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L, 0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
            0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L, 0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
            0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L, 0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
            0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L, 0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
            0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L, 0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L};
    private static final long SHA256_ADJUSTMENT = 0xC9DC06A7L;
    private static final long[] K_BLOCK = K.clone();
    private static final long SIGMA0_ADJUSTMENT = 0x059C0B80L;
    private static final long SIGMA1_ADJUSTMENT = 0x000B92C0L;

    static {
        K_BLOCK[14] = SHA256_ADJUSTMENT;
    }

    private static final long[][] CS64_COEFFICIENTS = {
            {0xE82E91B1L, 0xB32700D0L}, {0x44DE24C6L, 0x88DFF51DL}, {0x3BEB65BCL, 0x29514038L},
            {0xC89C5933L, 0xB328CBDFL}, {0x8D1EC211L, 0xD405B4D5L}, {0x314EF5D5L, 0x26E55F3CL},
            {0xE92491B1L, 0x607801C6L}, {0xF0A5B9D5L, 0x82B2C1FBL}, {0x06064CF3L, 0xFA51A3DAL},
            {0xDA79FA60L, 0x207CC464L}, {0xBED20560L, 0x3C3C74F3L}, {0x89F9C9F8L, 0xD2D14B61L},
            {0x35FA38C6L, 0xBB04A2C1L}, {0xCB49E3C6L, 0xC5626167L}};
    private static final long CS64_FACTOR = 0x82893933L;
    private static final long CS64_ADJUSTMENT0 = 0xF2F90B1AL;
    private static final long CS64_ADJUSTMENT1 = 0x0B1A0000L;

    private static long rol16(long v) {
        v &= MASK;
        return ((v << 16) | (v >>> 16)) & MASK;
    }

    private static long sigma0(long v) {
        return ((v >>> 7) ^ (v << 25) ^ (v >>> 18) ^ (v << 14) ^ (v >>> 3)) & MASK;
    }

    private static long sigma1(long v) {
        return ((v >>> 17) ^ (v << 15) ^ (v >>> 19) ^ (v << 13) ^ (v >>> 10)) & MASK;
    }

    private static long bigSigma1(long e) {
        long r = e * 0x100000001L;
        return (r >>> 6) ^ (r >>> 11) ^ (r >>> 25);
    }

    private static long bigSigma0(long a) {
        long r = a * 0x100000001L;
        return (r >>> 2) ^ (r >>> 13) ^ (r >>> 22);
    }

    /** Раунды SHA-256; при firstRound == 14 продолжает вычисление с середины первого блока. */
    private static long[] rounds(long[] state, long[] words, int firstRound) {
        long[] k = firstRound == 14 ? K_BLOCK : K;
        long a = state[0], b = state[1], c = state[2], d = state[3];
        long e = state[4], f = state[5], g = state[6], h = state[7];
        for (int i = firstRound; i < 64; i++) {
            long t1 = h + bigSigma1(e) + (g ^ (e & (f ^ g))) + k[i] + words[i];
            long t2 = bigSigma0(a) + ((a & b) | (c & (a | b)));
            h = g;
            g = f;
            f = e;
            e = (d + t1) & MASK;
            d = c;
            c = b;
            b = a;
            a = (t1 + t2) & MASK;
        }
        return new long[]{a, b, c, d, e, f, g, h};
    }

    private static void expand(List<Long> words, int firstWord) {
        for (int i = firstWord; i < 64; i++) {
            long x = words.get(i - 2), y = words.get(i - 15);
            long s1 = (x >>> 17) ^ (x << 15) ^ (x >>> 19) ^ (x << 13) ^ (x >>> 10);
            long s0 = (y >>> 7) ^ (y << 25) ^ (y >>> 18) ^ (y << 14) ^ (y >>> 3);
            words.add((s1 + words.get(i - 7) + s0 + words.get(i - 16)) & MASK);
        }
    }

    private static long[] toArray(List<Long> l) {
        long[] r = new long[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }

    private static long beInt(byte[] d, int from, int to) {
        long v = 0;
        for (int i = from; i < to; i++) v = (v << 8) | (d[i] & 0xFF);
        return v;
    }

    private static long[] sha256Init(byte[] data) {
        List<Long> w = new ArrayList<>();
        for (int i = 0; i < 14; i++) w.add(0L);
        w.add(beInt(data, 0, 3));
        w.add(beInt(data, 3, 7));
        for (int i = 16; i < 30; i++) {
            long v = SHA256_SCHEDULE[i - 16];
            if (i - 2 >= 14) v += i == 16 ? (sigma1(w.get(i - 2)) ^ SIGMA1_ADJUSTMENT) : sigma1(w.get(i - 2));
            if (i - 7 >= 14) v += w.get(i - 7);
            if (i - 15 >= 14) v += i == 29 ? (sigma0(w.get(i - 15)) ^ SIGMA0_ADJUSTMENT) : sigma0(w.get(i - 15));
            w.add(v & MASK);
        }
        w.add((sigma1(w.get(28)) + w.get(23) + sigma0(w.get(15)) + w.get(14) + SHA256_ADJUSTMENT - K[14]) & MASK);
        expand(w, 31);
        long[] working = rounds(SHA256_STATE, toArray(w), 14);
        long[] r = new long[8];
        for (int i = 0; i < 8; i++) r[i] = (SHA256_INITIAL[i] + working[i]) & MASK;
        return r;
    }

    static byte[] sha256Digest(byte[] data) {
        int length = 57 + data.length;
        int padLen = Math.floorMod(55 - length, 64);
        ByteBuffer p = ByteBuffer.allocate(data.length - 7 + 1 + padLen + 8).order(ByteOrder.BIG_ENDIAN);
        p.put(data, 7, data.length - 7).put((byte) 0x80);
        p.position(p.position() + padLen);
        p.putLong((long) length * 8);
        byte[] padded = p.array();

        byte[] first = new byte[7];
        System.arraycopy(data, 0, first, 0, 7);
        long[] state = sha256Init(first);
        for (int off = 0; off < padded.length; off += 64) {
            List<Long> w = new ArrayList<>();
            for (int i = 0; i < 16; i++) w.add(beInt(padded, off + 4 * i, off + 4 * i + 4));
            expand(w, 16);
            long[] working = rounds(state, toArray(w), 0);
            for (int i = 0; i < 8; i++) state[i] = (state[i] + working[i]) & MASK;
        }
        ByteBuffer out = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
        for (long s : state) out.putInt((int) s);
        return out.array();
    }

    private static long[] wordsLe4Even(byte[] data) {
        int len = (data.length + 3) / 4 * 4;
        ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        b.put(data);
        int n = len / 4;
        long[] r = new long[n % 2 == 1 ? n + 1 : n];
        for (int i = 0; i < n; i++) r[i] = b.getInt(4 * i) & MASK;
        return r;
    }

    static long[] cs64Digest(byte[] data, byte[] digest) {
        ByteBuffer d = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN);
        long keyA = (d.getInt(0) & MASK) | 1;
        long keyB = (d.getInt(4) & MASK) | 1;
        long[] factors = {(keyA * CS64_FACTOR) & MASK, (keyB * CS64_FACTOR) & MASK};
        List<Long> terms = new ArrayList<>();
        List<Long> offsets = new ArrayList<>();
        for (int i = 0; i < CS64_COEFFICIENTS.length; i++) {
            terms.add((CS64_COEFFICIENTS[i][0] * factors[i % 2]) & MASK);
            offsets.add(CS64_COEFFICIENTS[i][1]);
        }
        byte[] withZero = new byte[data.length + 1];
        System.arraycopy(data, 0, withZero, 1, data.length);
        long[] words = wordsLe4Even(withZero);
        for (int i = 0; i < words.length; i++) {
            long word = words[i];
            long term = word * (i % 2 == 0 ? keyA : keyB);
            long offset = rol16(word) * (i % 2 == 0 ? 0xEF0569FBL : 0xC31713DBL);
            if (i == 0) {
                term += CS64_ADJUSTMENT0 * factors[0];
                offset += CS64_ADJUSTMENT1;
            }
            terms.add(term & MASK);
            offsets.add(offset & MASK);
        }
        long swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int i = 0; i < terms.size(); i += 2) {
            long x = terms.get(i), y = terms.get(i + 1);
            long t = rol16((offsets.get(i) + x) & MASK);
            long q = (t * 0x76F50F34L) & MASK;
            swapAcc += (rol16(q) * 0xC3EFEA97L + q) & MASK;
            t = rol16((offsets.get(i + 1) + y) & MASK);
            q = (t * 0x1881CE3CL) & MASK;
            swapLast = (rol16(q) * 0x35BD1EC9L + q) & MASK;
            swapAcc = (swapAcc + swapLast) & MASK;

            state = (state * keyA + x) & MASK;
            state = (rol16(state) * 0xCF98B111L) & MASK;
            state = (rol16(state) * 0x87085B9FL) & MASK;
            state = (rol16(state) * 0x12CEB96DL) & MASK;
            state = (rol16(state) * 0x257E1D83L) & MASK;
            acc += state;
            t = (state * keyB + y) & MASK;
            t = (rol16(t) * 0xA27416F5L) & MASK;
            t = (rol16(t) * 0xD38396FFL) & MASK;
            state = (rol16((rol16(t) * 0x7C932B89L) & MASK) * 0xBFA49F69L) & MASK;
            acc = (acc + state) & MASK;
        }
        return new long[]{swapLast ^ state, swapAcc ^ acc};
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02X", x & 0xFF));
        return sb.toString();
    }

    /** Значение Sec-MS-GEC для момента unixSeconds: "&lt;16 hex&gt;-&lt;64 hex&gt;". */
    static String secMsGec(double unixSeconds) {
        double ticks = unixSeconds + WIN_EPOCH;
        ticks -= ticks % 300;
        ticks *= 1e9 / 100;
        String str = new BigDecimal(ticks).setScale(0, RoundingMode.HALF_EVEN).toPlainString() + TRUSTED_CLIENT_TOKEN;
        byte[] data = str.getBytes(StandardCharsets.US_ASCII);
        byte[] digest = sha256Digest(data);
        long[] cs = cs64Digest(data, digest);
        ByteBuffer c = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        c.putInt((int) cs[0]).putInt((int) cs[1]);
        return hex(c.array()) + "-" + hex(digest);
    }

    static String secMsGec() {
        return secMsGec(System.currentTimeMillis() / 1000.0 + clockSkewSeconds);
    }
}
