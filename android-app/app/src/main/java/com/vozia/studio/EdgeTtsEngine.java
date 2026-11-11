package com.vozia.studio;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

public class EdgeTtsEngine {

    private static final String HOST = "speech.platform.bing.com";
    private static final int PORT = 443;
    private static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String SEC_MS_GEC_VERSION = "2-143.0.3650.75";
    private static final long WIN_EPOCH = 11644473600L;
    private static final SecureRandom RNG = new SecureRandom();

    private static String cachedVoicesJson = null;
    private static volatile long clockSkewSeconds = 0L;
    private static volatile boolean clockSynced = false;

    private static final Map<String, String> COUNTRY_NAMES = new HashMap<>();
    private static final Map<String, String> LANGUAGE_NAMES = new HashMap<>();

    static {
        COUNTRY_NAMES.put("es-MX", "México 🇲🇽");
        COUNTRY_NAMES.put("es-ES", "España 🇪🇸");
        COUNTRY_NAMES.put("es-AR", "Argentina 🇦🇷");
        COUNTRY_NAMES.put("es-CO", "Colombia 🇨🇴");
        COUNTRY_NAMES.put("es-CL", "Chile 🇨🇱");
        COUNTRY_NAMES.put("es-PE", "Perú 🇵🇪");
        COUNTRY_NAMES.put("es-VE", "Venezuela 🇻🇪");
        COUNTRY_NAMES.put("es-US", "EE.UU. (Español) 🇺🇸");
        COUNTRY_NAMES.put("es-UY", "Uruguay 🇺🇾");
        COUNTRY_NAMES.put("es-CR", "Costa Rica 🇨🇷");
        COUNTRY_NAMES.put("es-EC", "Ecuador 🇪🇨");
        COUNTRY_NAMES.put("es-GT", "Guatemala 🇬🇹");
        COUNTRY_NAMES.put("es-PA", "Panamá 🇵🇦");
        COUNTRY_NAMES.put("es-DO", "Rep. Dominicana 🇩🇴");
        COUNTRY_NAMES.put("es-PR", "Puerto Rico 🇵🇷");
        COUNTRY_NAMES.put("es-PY", "Paraguay 🇵🇾");
        COUNTRY_NAMES.put("es-SV", "El Salvador 🇸🇻");
        COUNTRY_NAMES.put("es-HN", "Honduras 🇭🇳");
        COUNTRY_NAMES.put("es-NI", "Nicaragua 🇳🇮");
        COUNTRY_NAMES.put("es-BO", "Bolivia 🇧🇴");
        COUNTRY_NAMES.put("es-CU", "Cuba 🇨🇺");
        COUNTRY_NAMES.put("es-GQ", "Guinea Ecuatorial 🇬🇶");
        COUNTRY_NAMES.put("en-US", "EE.UU. (Inglés) 🇺🇸");
        COUNTRY_NAMES.put("en-GB", "Reino Unido 🇬🇧");
        COUNTRY_NAMES.put("en-AU", "Australia 🇦🇺");
        COUNTRY_NAMES.put("en-CA", "Canadá 🇨🇦");
        COUNTRY_NAMES.put("pt-BR", "Brasil 🇧🇷");
        COUNTRY_NAMES.put("pt-PT", "Portugal 🇵🇹");
        COUNTRY_NAMES.put("fr-FR", "Francia 🇫🇷");
        COUNTRY_NAMES.put("fr-CA", "Canadá (Francés) 🇨🇦");
        COUNTRY_NAMES.put("de-DE", "Alemania 🇩🇪");
        COUNTRY_NAMES.put("it-IT", "Italia 🇮🇹");

        LANGUAGE_NAMES.put("es", "Español");
        LANGUAGE_NAMES.put("en", "Inglés");
        LANGUAGE_NAMES.put("pt", "Portugués");
        LANGUAGE_NAMES.put("fr", "Francés");
        LANGUAGE_NAMES.put("de", "Alemán");
        LANGUAGE_NAMES.put("it", "Italiano");
    }

    public static synchronized String fetchVoicesJson() throws Exception {
        if (cachedVoicesJson != null) {
            return cachedVoicesJson;
        }
        String urlStr = "https://" + HOST + "/consumer/speech/synthesize/readaloud/voices/list?trustedclienttoken=" + TRUSTED_CLIENT_TOKEN;
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0");
        conn.setRequestProperty("Accept", "*/*");

        updateClockSkewFromDateHeader(conn.getHeaderField("Date"));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
        }
        JSONArray rawVoices = new JSONArray(new String(baos.toByteArray(), StandardCharsets.UTF_8));
        List<JSONObject> list = new ArrayList<>();

        for (int i = 0; i < rawVoices.length(); i++) {
            JSONObject v = rawVoices.getJSONObject(i);
            String locale = v.optString("Locale", "");
            String langCode = locale.contains("-") ? locale.split("-")[0] : locale;
            String shortName = v.optString("ShortName", "");
            String cleanName = shortName.replace(locale + "-", "").replace("Neural", "");
            boolean isMulti = cleanName.contains("Multilingual");
            if (isMulti) {
                cleanName = cleanName.replace("Multilingual", " (Multilingüe)");
            }
            String genderRaw = v.optString("Gender", "Female");
            String genderEs = "Female".equalsIgnoreCase(genderRaw) ? "Femenina" : "Masculina";

            JSONObject item = new JSONObject();
            item.put("id", shortName);
            item.put("name", cleanName);
            item.put("locale", locale);
            item.put("lang", langCode);
            item.put("lang_label", LANGUAGE_NAMES.containsKey(langCode) ? LANGUAGE_NAMES.get(langCode) : langCode.toUpperCase());
            item.put("region_label", COUNTRY_NAMES.containsKey(locale) ? COUNTRY_NAMES.get(locale) : locale);
            item.put("gender", genderRaw);
            item.put("gender_label", genderEs);
            item.put("multilingual", isMulti);
            list.add(item);
        }

        final List<String> priorityLocales = Arrays.asList("es-MX", "es-ES", "es-AR", "es-CO", "es-US", "es-CL", "es-PE", "es-VE");
        list.sort((a, b) -> {
            String locA = a.optString("locale", "");
            String locB = b.optString("locale", "");
            int idxA = priorityLocales.indexOf(locA);
            int idxB = priorityLocales.indexOf(locB);
            if (idxA != -1 && idxB != -1) {
                if (idxA != idxB) return Integer.compare(idxA, idxB);
                return a.optString("name", "").compareTo(b.optString("name", ""));
            }
            if (idxA != -1) return -1;
            if (idxB != -1) return 1;
            boolean esA = "es".equals(a.optString("lang", ""));
            boolean esB = "es".equals(b.optString("lang", ""));
            if (esA != esB) return esA ? -1 : 1;
            int cmpLoc = locA.compareTo(locB);
            if (cmpLoc != 0) return cmpLoc;
            return a.optString("name", "").compareTo(b.optString("name", ""));
        });

        JSONArray sortedArr = new JSONArray();
        for (JSONObject obj : list) {
            sortedArr.put(obj);
        }
        JSONObject result = new JSONObject();
        result.put("voices", sortedArr);
        cachedVoicesJson = result.toString();
        return cachedVoicesJson;
    }

    public static List<String> preprocessAndChunk(String text, String pauseMode, int maxChars) {
        List<String> chunks = new ArrayList<>();
        if (text == null) return chunks;
        text = text.trim();
        if (text.isEmpty()) return chunks;

        text = text.replaceAll("(?i)\\[pausa(?:\\s+\\d+(?:\\.\\d+)?s)?\\]", " ... \n\n");
        if ("narrator".equalsIgnoreCase(pauseMode)) {
            text = text.replaceAll("\\n\\s*\\n", "\n\n...\n\n");
        }

        String[] paragraphs = text.split("\\n+");
        StringBuilder currentChunk = new StringBuilder();

        for (String rawPara : paragraphs) {
            String para = rawPara.trim();
            if (para.isEmpty()) continue;

            if (currentChunk.length() + para.length() + 2 <= maxChars) {
                if (currentChunk.length() > 0) currentChunk.append("\n\n");
                currentChunk.append(para);
            } else {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                    currentChunk.setLength(0);
                }
                if (para.length() <= maxChars) {
                    currentChunk.append(para);
                } else {
                    String[] sentences = para.split("(?<=[.!?…])\\s+");
                    for (String sent : sentences) {
                        if (currentChunk.length() + sent.length() + 1 <= maxChars) {
                            if (currentChunk.length() > 0) currentChunk.append(" ");
                            currentChunk.append(sent);
                        } else {
                            if (currentChunk.length() > 0) {
                                chunks.add(currentChunk.toString());
                                currentChunk.setLength(0);
                            }
                            if (sent.length() > maxChars) {
                                String[] words = sent.split("\\s+");
                                for (String w : words) {
                                    if (currentChunk.length() + w.length() + 1 <= maxChars) {
                                        if (currentChunk.length() > 0) currentChunk.append(" ");
                                        currentChunk.append(w);
                                    } else {
                                        if (currentChunk.length() > 0) chunks.add(currentChunk.toString());
                                        currentChunk.setLength(0);
                                        currentChunk.append(w);
                                    }
                                }
                            } else {
                                currentChunk.append(sent);
                            }
                        }
                    }
                }
            }
        }
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }
        return chunks;
    }

    public static byte[] synthesizeAllChunks(List<String> chunks, String voice, int rate, int pitch, int volume) throws Exception {
        String rateStr = (rate >= 0 ? "+" : "") + rate + "%";
        String pitchStr = (pitch >= 0 ? "+" : "") + pitch + "Hz";
        String volStr = (volume >= 0 ? "+" : "") + volume + "%";

        ByteArrayOutputStream totalAudio = new ByteArrayOutputStream();
        for (String chunk : chunks) {
            byte[] part = synthesizeSingleChunk(chunk, voice, rateStr, pitchStr, volStr);
            totalAudio.write(part);
        }
        return totalAudio.toByteArray();
    }

    public interface ChunkProgressListener {
        void onChunkProgress(int currentChunk, int totalChunks);
    }

    public static void synthesizeToFile(List<String> chunks, String voice, int rate, int pitch, int volume, File outFile) throws Exception {
        synthesizeToFile(chunks, voice, rate, pitch, volume, outFile, null);
    }

    public static void synthesizeToFile(List<String> chunks, String voice, int rate, int pitch, int volume, File outFile, ChunkProgressListener listener) throws Exception {
        String rateStr = (rate >= 0 ? "+" : "") + rate + "%";
        String pitchStr = (pitch >= 0 ? "+" : "") + pitch + "Hz";
        String volStr = (volume >= 0 ? "+" : "") + volume + "%";

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            int total = chunks.size();
            for (int i = 0; i < total; i++) {
                if (listener != null) {
                    listener.onChunkProgress(i + 1, total);
                }
                byte[] part = synthesizeSingleChunk(chunks.get(i), voice, rateStr, pitchStr, volStr);
                fos.write(part);
            }
        }
    }

    private static synchronized void updateClockSkewFromDateHeader(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) return;
        try {
            SimpleDateFormat rfcFmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
            rfcFmt.setTimeZone(TimeZone.getTimeZone("GMT"));
            Date serverDate = rfcFmt.parse(dateStr.trim());
            if (serverDate != null) {
                long serverSec = serverDate.getTime() / 1000L;
                long localSec = System.currentTimeMillis() / 1000L;
                clockSkewSeconds = serverSec - localSec;
                clockSynced = true;
            }
        } catch (Exception ignored) {
        }
    }

    private static void ensureClockSynced() {
        if (clockSynced) return;
        try {
            String urlStr = "https://" + HOST + "/consumer/speech/synthesize/readaloud/voices/list?trustedclienttoken=" + TRUSTED_CLIENT_TOKEN;
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0");
            updateClockSkewFromDateHeader(conn.getHeaderField("Date"));
            conn.disconnect();
        } catch (Exception ignored) {
        }
    }

    private static byte[] synthesizeSingleChunk(String text, String voice, String rate, String pitch, String volume) throws Exception {
        ensureClockSynced();
        try {
            return doSynthesizeSingleChunk(text, voice, rate, pitch, volume);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("403")) {
                // Retry once after clock skew was updated from the 403 Date header
                return doSynthesizeSingleChunk(text, voice, rate, pitch, volume);
            }
            throw e;
        }
    }

    private static byte[] doSynthesizeSingleChunk(String text, String voice, String rate, String pitch, String volume) throws Exception {
        String connId = UUID.randomUUID().toString().replace("-", "");
        long unixSec = (System.currentTimeMillis() / 1000L) + clockSkewSeconds;
        String secMsGec = generateSecMsGec(unixSec);

        byte[] muidBytes = new byte[16];
        RNG.nextBytes(muidBytes);
        StringBuilder muid = new StringBuilder();
        for (byte b : muidBytes) muid.append(String.format("%02X", b & 0xFF));

        String path = "/consumer/speech/synthesize/readaloud/edge/v1"
                + "?TrustedClientToken=" + TRUSTED_CLIENT_TOKEN
                + "&ConnectionId=" + connId
                + "&Sec-MS-GEC=" + secMsGec
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION;

        byte[] wsKeyBytes = new byte[16];
        RNG.nextBytes(wsKeyBytes);
        String wsKey = Base64.encodeToString(wsKeyBytes, Base64.NO_WRAP);

        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket socket = (SSLSocket) factory.createSocket(HOST, PORT)) {
            socket.setSoTimeout(35000);
            socket.startHandshake();

            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            InputStream in = new BufferedInputStream(socket.getInputStream());

            String handshake = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + HOST + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Pragma: no-cache\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n"
                    + "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0\r\n"
                    + "Accept-Language: en-US,en;q=0.9\r\n"
                    + "Cookie: muid=" + muid + ";\r\n\r\n";

            out.write(handshake.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int b;
            int matchState = 0;
            while ((b = in.read()) != -1) {
                headerBuf.write(b);
                if ((matchState == 0 && b == '\r') || (matchState == 1 && b == '\n') || (matchState == 2 && b == '\r') || (matchState == 3 && b == '\n')) {
                    matchState++;
                    if (matchState == 4) break;
                } else {
                    matchState = (b == '\r') ? 1 : 0;
                }
            }
            String respHeader = new String(headerBuf.toByteArray(), StandardCharsets.US_ASCII);
            for (String line : respHeader.split("\r\n")) {
                if (line.toLowerCase(Locale.US).startsWith("date:")) {
                    updateClockSkewFromDateHeader(line.substring(5).trim());
                }
            }
            if (!respHeader.startsWith("HTTP/1.1 101")) {
                throw new IOException("Handshake error: " + respHeader.split("\r\n")[0]);
            }

            String timestamp = getJsDateString();
            String configMsg = "X-Timestamp:" + timestamp + "\r\n"
                    + "Content-Type:application/json; charset=utf-8\r\n"
                    + "Path:speech.config\r\n\r\n"
                    + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n";
            sendWsTextFrame(out, configMsg);

            String reqId = UUID.randomUUID().toString().replace("-", "");
            String escapedText = escapeXml(text);
            String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                    + "<voice name='" + voice + "'>"
                    + "<prosody pitch='" + pitch + "' rate='" + rate + "' volume='" + volume + "'>"
                    + escapedText
                    + "</prosody></voice></speak>";
            String ssmlMsg = "X-RequestId:" + reqId + "\r\n"
                    + "Content-Type:application/ssml+xml\r\n"
                    + "X-Timestamp:" + timestamp + "Z\r\n"
                    + "Path:ssml\r\n\r\n"
                    + ssml;
            sendWsTextFrame(out, ssmlMsg);

            ByteArrayOutputStream audioOut = new ByteArrayOutputStream();
            while (true) {
                int b0 = in.read();
                if (b0 == -1) break;
                int b1 = in.read();
                if (b1 == -1) break;

                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long payloadLen = b1 & 0x7F;

                if (payloadLen == 126) {
                    payloadLen = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                } else if (payloadLen == 127) {
                    payloadLen = 0;
                    for (int i = 0; i < 8; i++) {
                        payloadLen = (payloadLen << 8) | (in.read() & 0xFF);
                    }
                }

                byte[] maskKey = null;
                if (masked) {
                    maskKey = readFully(in, 4);
                }

                byte[] payload = readFully(in, (int) payloadLen);
                if (masked && maskKey != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= maskKey[i % 4];
                    }
                }

                if (opcode == 0x1) {
                    String msg = new String(payload, StandardCharsets.UTF_8);
                    if (msg.contains("Path:turn.end")) {
                        break;
                    }
                } else if (opcode == 0x2) {
                    if (payload.length >= 2) {
                        int headerLen = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                        int audioStart = headerLen + 2;
                        if (audioStart < payload.length) {
                            String hdr = new String(payload, 2, headerLen, StandardCharsets.US_ASCII);
                            if (hdr.contains("Path:audio")) {
                                audioOut.write(payload, audioStart, payload.length - audioStart);
                            }
                        }
                    }
                } else if (opcode == 0x8) {
                    break;
                } else if (opcode == 0x9) {
                    sendWsFrame(out, 0xA, payload);
                }
            }
            return audioOut.toByteArray();
        }
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int read = 0;
        while (read < len) {
            int r = in.read(buf, read, len - read);
            if (r == -1) throw new EOFException("Unexpected EOF");
            read += r;
        }
        return buf;
    }

    private static void sendWsTextFrame(OutputStream out, String text) throws IOException {
        sendWsFrame(out, 0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendWsFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        out.write(0x80 | (opcode & 0x0F));
        int len = payload.length;
        if (len <= 125) {
            out.write(0x80 | len);
        } else if (len <= 65535) {
            out.write(0x80 | 126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((len >>> (8 * i)) & 0xFF));
            }
        }
        byte[] mask = new byte[4];
        RNG.nextBytes(mask);
        out.write(mask);
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) {
            masked[i] = (byte) (payload[i] ^ mask[i % 4]);
        }
        out.write(masked);
        out.flush();
    }

    private static String getJsDateString() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(System.currentTimeMillis() + (clockSkewSeconds * 1000L)));
    }

    private static String escapeXml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 8 || c == 11 || c == 12 || (c >= 14 && c <= 31)) {
                sb.append(' ');
            } else if (c == '&') {
                sb.append("&amp;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else if (c == '\'') {
                sb.append("&apos;");
            } else if (c == '"') {
                sb.append("&quot;");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // --- Sec-MS-GEC v2 DRM implementation ---
    private static final int[] SHA256_INITIAL = {
        0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
        0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19
    };
    private static final int[] SHA256_STATE = {
        0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A,
        0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2
    };
    private static final int[] SHA256_SCHEDULE = {
        0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA,
        0xA622EE8C, 0xD1DD9491, 0xC0DD25A0, 0xF9E70AB6,
        0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33,
        0xD258B060, 0x72766564
    };
    private static final int[] SHA256_ROUND_CONSTANTS = {
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5,
        0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
        0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3,
        0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
        0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC,
        0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7,
        0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
        0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
        0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3,
        0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5,
        0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208,
        0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2
    };
    private static final int SHA256_ADJUSTMENT = 0xC9DC06A7;
    private static final int SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80;
    private static final int SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0;

    private static final int[][] CS64_COEFFICIENTS = {
        {0xE82E91B1, 0xB32700D0}, {0x44DE24C6, 0x88DFF51D},
        {0x3BEB65BC, 0x29514038}, {0xC89C5933, 0xB328CBDF},
        {0x8D1EC211, 0xD405B4D5}, {0x314EF5D5, 0x26E55F3C},
        {0xE92491B1, 0x607801C6}, {0xF0A5B9D5, 0x82B2C1FB},
        {0x06064CF3, 0xFA51A3DA}, {0xDA79FA60, 0x207CC464},
        {0xBED20560, 0x3C3C74F3}, {0x89F9C9F8, 0xD2D14B61},
        {0x35FA38C6, 0xBB04A2C1}, {0xCB49E3C6, 0xC5626167}
    };
    private static final int CS64_FACTOR = 0x82893933;
    private static final int CS64_ADJUSTMENT0 = 0xF2F90B1A;
    private static final int CS64_ADJUSTMENT1 = 0x0B1A0000;

    private static int rol16(int value) { return (value << 16) | (value >>> 16); }
    private static int sigma0(int value) { return (value >>> 7) ^ (value << 25) ^ (value >>> 18) ^ (value << 14) ^ (value >>> 3); }
    private static int sigma1(int value) { return (value >>> 17) ^ (value << 15) ^ (value >>> 19) ^ (value << 13) ^ (value >>> 10); }
    private static int rotr(int x, int n) { return (x >>> n) | (x << (32 - n)); }

    private static int[] sha256Rounds(int[] state, int[] words, int firstRound) {
        int a = state[0], b = state[1], c = state[2], d = state[3];
        int e = state[4], f = state[5], g = state[6], h = state[7];
        for (int index = firstRound; index < 64; index++) {
            int k = (firstRound == 14 && index == 14) ? SHA256_ADJUSTMENT : SHA256_ROUND_CONSTANTS[index];
            int s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            int ch = g ^ (e & (f ^ g));
            int t1 = h + s1 + ch + k + words[index];
            int s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            int maj = (a & b) | (c & (a | b));
            int t2 = s0 + maj;
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
        }
        return new int[]{a, b, c, d, e, f, g, h};
    }

    private static void expandSha256Schedule(int[] words, int firstWord) {
        for (int index = firstWord; index < 64; index++) {
            words[index] = sigma1(words[index - 2]) + words[index - 7] + sigma0(words[index - 15]) + words[index - 16];
        }
    }

    private static int[] sha256Init(byte[] data7) {
        int[] words = new int[64];
        words[14] = ((data7[0] & 0xFF) << 16) | ((data7[1] & 0xFF) << 8) | (data7[2] & 0xFF);
        words[15] = ((data7[3] & 0xFF) << 24) | ((data7[4] & 0xFF) << 16) | ((data7[5] & 0xFF) << 8) | (data7[6] & 0xFF);
        for (int index = 16; index < 30; index++) {
            int value = SHA256_SCHEDULE[index - 16];
            if (index - 2 >= 14) value += (index == 16) ? (sigma1(words[index - 2]) ^ SHA256_SIGMA1_ADJUSTMENT) : sigma1(words[index - 2]);
            if (index - 7 >= 14) value += words[index - 7];
            if (index - 15 >= 14) value += (index == 29) ? (sigma0(words[index - 15]) ^ SHA256_SIGMA0_ADJUSTMENT) : sigma0(words[index - 15]);
            words[index] = value;
        }
        words[30] = sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] + SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14];
        expandSha256Schedule(words, 31);
        int[] working = sha256Rounds(SHA256_STATE, words, 14);
        int[] out = new int[8];
        for (int i = 0; i < 8; i++) out[i] = SHA256_INITIAL[i] + working[i];
        return out;
    }

    private static byte[] sha256Digest(byte[] data) {
        int length = 57 + data.length;
        int padZeroes = (55 - length) % 64;
        if (padZeroes < 0) padZeroes += 64;
        int tailLen = data.length - 7;
        ByteBuffer buf = ByteBuffer.allocate(tailLen + 1 + padZeroes + 8).order(ByteOrder.BIG_ENDIAN);
        buf.put(data, 7, tailLen);
        buf.put((byte) 0x80);
        for (int i = 0; i < padZeroes; i++) buf.put((byte) 0);
        buf.putLong((long) length * 8L);
        byte[] padded = buf.array();

        int[] state = sha256Init(Arrays.copyOfRange(data, 0, 7));
        for (int offset = 0; offset < padded.length; offset += 64) {
            int[] words = new int[64];
            for (int i = 0; i < 16; i++) {
                int p = offset + i * 4;
                words[i] = ((padded[p] & 0xFF) << 24) | ((padded[p + 1] & 0xFF) << 16) | ((padded[p + 2] & 0xFF) << 8) | (padded[p + 3] & 0xFF);
            }
            expandSha256Schedule(words, 16);
            int[] working = sha256Rounds(state, words, 0);
            for (int i = 0; i < 8; i++) state[i] += working[i];
        }
        ByteBuffer out = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
        for (int s : state) out.putInt(s);
        return out.array();
    }

    private static int[] wordsLe4Even(byte[] data) {
        int pad = (4 - (data.length % 4)) % 4;
        byte[] padded = Arrays.copyOf(data, data.length + pad);
        int count = padded.length / 4;
        int evenCount = (count % 2 != 0) ? count + 1 : count;
        int[] words = new int[evenCount];
        ByteBuffer bb = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) words[i] = bb.getInt();
        return words;
    }

    private static int[] cs64Digest(byte[] data, byte[] digest) {
        ByteBuffer dBuf = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN);
        int keyA = dBuf.getInt() | 1;
        int keyB = dBuf.getInt() | 1;
        int[] factors = { keyA * CS64_FACTOR, keyB * CS64_FACTOR };

        byte[] zeroPlusData = new byte[data.length + 1];
        System.arraycopy(data, 0, zeroPlusData, 1, data.length);
        int[] extraWords = wordsLe4Even(zeroPlusData);

        int total = CS64_COEFFICIENTS.length + extraWords.length;
        int[] terms = new int[total];
        int[] offsets = new int[total];

        for (int i = 0; i < CS64_COEFFICIENTS.length; i++) {
            terms[i] = CS64_COEFFICIENTS[i][0] * factors[i % 2];
            offsets[i] = CS64_COEFFICIENTS[i][1];
        }
        for (int index = 0; index < extraWords.length; index++) {
            int word = extraWords[index];
            int term = word * ((index % 2 == 0) ? keyA : keyB);
            int offset = rol16(word) * ((index % 2 == 0) ? 0xEF0569FB : 0xC31713DB);
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * factors[0];
                offset += CS64_ADJUSTMENT1;
            }
            terms[CS64_COEFFICIENTS.length + index] = term;
            offsets[CS64_COEFFICIENTS.length + index] = offset;
        }

        int swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int index = 0; index < total; index += 2) {
            int x = terms[index], y = terms[index + 1];
            int t = rol16(offsets[index] + x);
            int q = t * 0x76F50F34;
            swapAcc += rol16(q) * 0xC3EFEA97 + q;

            t = rol16(offsets[index + 1] + y);
            q = t * 0x1881CE3C;
            swapLast = rol16(q) * 0x35BD1EC9 + q;
            swapAcc += swapLast;

            state = state * keyA + x;
            state = rol16(state) * 0xCF98B111;
            state = rol16(state) * 0x87085B9F;
            state = rol16(state) * 0x12CEB96D;
            state = rol16(state) * 0x257E1D83;
            acc += state;

            t = state * keyB + y;
            t = rol16(t) * 0xA27416F5;
            t = rol16(t) * 0xD38396FF;
            state = rol16(rol16(t) * 0x7C932B89) * 0xBFA49F69;
            acc += state;
        }
        return new int[]{ swapLast ^ state, swapAcc ^ acc };
    }

    private static String bytesToHexUpper(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b & 0xFF));
        return sb.toString();
    }

    public static String generateSecMsGec(long unixSeconds) {
        long ticks = unixSeconds + WIN_EPOCH;
        ticks -= (ticks % 300);
        long fileTime = ticks * 10_000_000L;
        String strToHash = fileTime + TRUSTED_CLIENT_TOKEN;
        byte[] data = strToHash.getBytes(StandardCharsets.US_ASCII);
        byte[] digest = sha256Digest(data);
        int[] cs64Words = cs64Digest(data, digest);
        ByteBuffer csBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        csBuf.putInt(cs64Words[0]);
        csBuf.putInt(cs64Words[1]);
        return bytesToHexUpper(csBuf.array()) + "-" + bytesToHexUpper(digest);
    }
}

