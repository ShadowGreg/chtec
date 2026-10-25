package ru.chtec.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Онлайн-голоса Microsoft (те же, что «Читать вслух» в браузере Edge) — перенос протокола edge-tts 7.3.1.
 * Отдаёт MP3 24 кГц моно 48 кбит/с. Работает без сторонних библиотек: свой маленький WebSocket-клиент.
 */
public final class EdgeTts {
    private EdgeTts() {}

    public static final String DMITRY = "ru-RU-DmitryNeural";
    public static final String SVETLANA = "ru-RU-SvetlanaNeural";

    private static final String CHROMIUM_FULL_VERSION = "143.0.3650.75";
    private static final String CHROMIUM_MAJOR = "143";
    private static final String PATH = "/consumer/speech/synthesize/readaloud/edge/v1";

    // Можно переопределить для тестов (локальный сервер без TLS).
    static String host = "speech.platform.bing.com";
    static int port = 443;
    static boolean tls = true;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Звук фрагмента и метки слов: когда (мс от начала) звучит каждое слово. */
    public static final class Speech {
        public final byte[] audio;
        public final long[] offsetsMs;
        public final String[] words;

        Speech(byte[] audio, long[] offsetsMs, String[] words) {
            this.audio = audio;
            this.offsetsMs = offsetsMs;
            this.words = words;
        }

        /** Длительность звука: поток CBR 48 кбит/с, значит 6 байт на миллисекунду. */
        public int durationMs() {
            return audio.length / 6;
        }
    }

    /** Ошибка ответа сервера при подключении (например, 403 из-за неверного времени на телефоне). */
    public static final class HandshakeException extends IOException {
        final String dateHeader;

        HandshakeException(String status, String dateHeader) {
            super("Сервер Microsoft отказал: " + status);
            this.dateHeader = dateHeader;
        }
    }

    // ------------------------------------------------------------ тишина в формате MP3

    private static final byte[] SILENT_FRAME = fromHex(
            "FFF364C47C0000034800000000555555555555554C414D45332E31303055555555555555555555555555555555555555"
                    + "555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555"
                    + "555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555");
    private static final int FRAME_MS = 24; // 576 отсчётов / 24000 Гц

    /** Тишина нужной длины теми же MP3-кадрами, что отдаёт сервис, — можно склеивать без перекодирования. */
    public static byte[] silence(int ms) {
        int n = Math.max(0, Math.round(ms / (float) FRAME_MS));
        byte[] out = new byte[n * SILENT_FRAME.length];
        for (int i = 0; i < n; i++) System.arraycopy(SILENT_FRAME, 0, out, i * SILENT_FRAME.length, SILENT_FRAME.length);
        return out;
    }

    // ------------------------------------------------------------ синтез

    /** Озвучивает фрагмент. rate — %, pitch — Гц, volume — % (относительно обычных). */
    public static Speech synthesize(String text, String voice, int rate, int pitch, int volume) throws IOException {
        try {
            return synthesizeOnce(text, voice, rate, pitch, volume);
        } catch (HandshakeException e) {
            // Как edge-tts: если часы телефона расходятся с сервером, подстраиваемся по заголовку Date и повторяем.
            if (e.dateHeader == null) throw e;
            try {
                SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                double server = f.parse(e.dateHeader).getTime() / 1000.0;
                EdgeDrm.clockSkewSeconds += server - (System.currentTimeMillis() / 1000.0 + EdgeDrm.clockSkewSeconds);
            } catch (ParseException pe) {
                throw e;
            }
            return synthesizeOnce(text, voice, rate, pitch, volume);
        }
    }

    private static Speech synthesizeOnce(String text, String voice, int rate, int pitch, int volume) throws IOException {
        String query = "?TrustedClientToken=" + EdgeDrm.TRUSTED_CLIENT_TOKEN
                + "&ConnectionId=" + uuid()
                + "&Sec-MS-GEC=" + EdgeDrm.secMsGec()
                + "&Sec-MS-GEC-Version=2-" + CHROMIUM_FULL_VERSION;
        WebSocket ws = new WebSocket(PATH + query);
        try {
            ws.sendText("X-Timestamp:" + dateString() + "\r\n"
                    + "Content-Type:application/json; charset=utf-8\r\n"
                    + "Path:speech.config\r\n\r\n"
                    + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                    + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\""
                    + "},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n");
            String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                    + "<voice name='" + voice + "'>"
                    + "<prosody pitch='" + signed(pitch, "Hz") + "' rate='" + signed(rate, "%")
                    + "' volume='" + signed(Math.max(-100, volume), "%") + "'>"
                    + escape(clean(text))
                    + "</prosody></voice></speak>";
            ws.sendText("X-RequestId:" + uuid() + "\r\n"
                    + "Content-Type:application/ssml+xml\r\n"
                    + "X-Timestamp:" + dateString() + "Z\r\n" // «Z» лишний, но так делает сам Edge
                    + "Path:ssml\r\n\r\n" + ssml);

            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            List<Long> offsets = new ArrayList<>();
            List<String> words = new ArrayList<>();
            while (true) {
                WebSocket.Message m = ws.receive();
                if (m.text) {
                    String s = new String(m.data, StandardCharsets.UTF_8);
                    int end = s.indexOf("\r\n\r\n");
                    String headers = end >= 0 ? s.substring(0, end) : s;
                    if (headers.contains("Path:turn.end")) break;
                    if (headers.contains("Path:audio.metadata") && end >= 0) parseWords(s.substring(end + 4), offsets, words);
                } else {
                    if (m.data.length < 2) continue;
                    int headerLen = ((m.data[0] & 0xFF) << 8) | (m.data[1] & 0xFF);
                    if (headerLen + 2 > m.data.length) throw new IOException("Неверный ответ сервера");
                    String headers = new String(m.data, 2, headerLen, StandardCharsets.UTF_8);
                    if (headers.contains("Path:audio")) audio.write(m.data, 2 + headerLen, m.data.length - 2 - headerLen);
                }
            }
            long[] off = new long[offsets.size()];
            for (int i = 0; i < off.length; i++) off[i] = offsets.get(i);
            return new Speech(audio.toByteArray(), off, words.toArray(new String[0]));
        } finally {
            ws.close();
        }
    }

    // {"Metadata":[{"Type":"WordBoundary","Data":{"Offset":1000000,"Duration":3500000,"text":{"Text":"Привет",...}}}]}
    private static final Pattern WORD_META = Pattern.compile(
            "\"Type\"\\s*:\\s*\"WordBoundary\".*?\"Offset\"\\s*:\\s*(\\d+).*?\"Text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
            Pattern.DOTALL);

    static void parseWords(String json, List<Long> offsets, List<String> words) {
        Matcher m = WORD_META.matcher(json);
        while (m.find()) {
            offsets.add(Long.parseLong(m.group(1)) / 10_000); // такты по 100 нс -> мс
            words.add(xmlUnescape(jsonUnescape(m.group(2))));
        }
    }

    private static String jsonUnescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char e = s.charAt(++i);
            if (e == 'u' && i + 4 < s.length()) {
                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                i += 4;
            } else if (e == 'n') sb.append('\n');
            else if (e == 't') sb.append('\t');
            else sb.append(e);
        }
        return sb.toString();
    }

    private static String xmlUnescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String clean(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c <= 8 || c == 11 || c == 12 || (c >= 14 && c <= 31) ? ' ' : c);
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String signed(int v, String unit) {
        return (v >= 0 ? "+" : "") + v + unit;
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String dateString() {
        SimpleDateFormat f = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date()) + " GMT+0000 (Coordinated Universal Time)";
    }

    private static byte[] fromHex(String h) {
        byte[] r = new byte[h.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return r;
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02X", x & 0xFF));
        return sb.toString();
    }

    // ------------------------------------------------------------ WebSocket (RFC 6455), только нужное

    static final class WebSocket {
        static final class Message {
            final boolean text;
            final byte[] data;

            Message(boolean text, byte[] data) {
                this.text = text;
                this.data = data;
            }
        }

        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        WebSocket(String pathAndQuery) throws IOException {
            Socket plain = new Socket();
            plain.connect(new InetSocketAddress(host, port), 10000);
            plain.setSoTimeout(20000); // зависшее соединение бросаем — следующая попытка обычно проходит
            if (tls) {
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(plain, host, port, true);
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS"); // проверка сертификата по имени сервера
                ssl.setSSLParameters(params);
                ssl.startHandshake();
                socket = ssl;
            } else {
                socket = plain;
            }
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();

            byte[] keyBytes = new byte[16];
            RANDOM.nextBytes(keyBytes);
            String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                    + CHROMIUM_MAJOR + ".0.0.0 Safari/537.36 Edg/" + CHROMIUM_MAJOR + ".0.0.0";
            String req = "GET " + pathAndQuery + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + base64(keyBytes) + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Pragma: no-cache\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n"
                    + "User-Agent: " + ua + "\r\n"
                    + "Accept-Encoding: gzip, deflate, br, zstd\r\n"
                    + "Accept-Language: en-US,en;q=0.9\r\n"
                    + "Cookie: muid=" + randomHex(16) + ";\r\n"
                    + "\r\n";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String status = readLine();
            String date = null;
            String line;
            while (!(line = readLine()).isEmpty()) {
                if (line.toLowerCase(Locale.ROOT).startsWith("date:")) date = line.substring(5).trim();
            }
            if (!status.contains(" 101")) {
                close();
                throw new HandshakeException(status, date);
            }
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') break;
                if (c != '\r') b.write(c);
            }
            if (c == -1 && b.size() == 0) throw new EOFException("Сервер закрыл соединение");
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }

        void sendText(String s) throws IOException {
            sendFrame(0x1, s.getBytes(StandardCharsets.UTF_8));
        }

        private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
            f.write(0x80 | opcode);
            int len = payload.length;
            if (len < 126) {
                f.write(0x80 | len);
            } else if (len < 65536) {
                f.write(0x80 | 126);
                f.write(len >>> 8);
                f.write(len);
            } else {
                f.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) f.write(i >= 4 ? 0 : (len >>> (8 * i)));
            }
            byte[] mask = new byte[4];
            RANDOM.nextBytes(mask);
            f.write(mask, 0, 4);
            for (int i = 0; i < len; i++) f.write(payload[i] ^ mask[i % 4]);
            out.write(f.toByteArray());
            out.flush();
        }

        Message receive() throws IOException {
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            int msgOpcode = -1;
            while (true) {
                int b0 = readByte(), b1 = readByte();
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) len = ((long) readByte() << 8) | readByte();
                else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
                }
                if (len > 64L * 1024 * 1024) throw new IOException("Слишком большое сообщение");
                byte[] mask = null;
                if ((b1 & 0x80) != 0) {
                    mask = new byte[4];
                    readFully(mask);
                }
                byte[] payload = new byte[(int) len];
                readFully(payload);
                if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];

                if (opcode == 0x8) throw new EOFException("Сервер закрыл соединение");
                if (opcode == 0x9) {
                    sendFrame(0xA, payload); // ping -> pong
                    continue;
                }
                if (opcode == 0xA) continue;
                if (opcode != 0x0) msgOpcode = opcode;
                msg.write(payload, 0, payload.length);
                if (fin) return new Message(msgOpcode == 0x1, msg.toByteArray());
            }
        }

        private int readByte() throws IOException {
            int b = in.read();
            if (b < 0) throw new EOFException("Сервер закрыл соединение");
            return b;
        }

        private void readFully(byte[] b) throws IOException {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) throw new EOFException("Сервер закрыл соединение");
                off += n;
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // уже закрыт
            }
        }
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String base64(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int b = (d[i] & 0xFF) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xFF) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 0xFF : 0);
            sb.append(B64[b >>> 18 & 63]).append(B64[b >>> 12 & 63]);
            sb.append(i + 1 < d.length ? B64[b >>> 6 & 63] : '=');
            sb.append(i + 2 < d.length ? B64[b & 63] : '=');
        }
        return sb.toString();
    }
}
