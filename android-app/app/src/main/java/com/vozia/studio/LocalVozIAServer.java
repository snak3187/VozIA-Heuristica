package com.vozia.studio;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class LocalVozIAServer {

    private final Context context;
    private final File outputsDir;
    private final File historyFile;
    private ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, JSONObject> synthJobs = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    private int port = 0;

    public LocalVozIAServer(Context context) {
        this.context = context.getApplicationContext();
        this.outputsDir = new File(this.context.getFilesDir(), "outputs");
        if (!this.outputsDir.exists()) {
            this.outputsDir.mkdirs();
        }
        this.historyFile = new File(this.outputsDir, "history.json");
        PDFBoxResourceLoader.init(this.context);
    }

    public synchronized int start() throws IOException {
        if (running && serverSocket != null && !serverSocket.isClosed()) {
            return port;
        }
        serverSocket = new ServerSocket(0); // Bind to any free local port
        port = serverSocket.getLocalPort();
        running = true;

        executor.execute(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    executor.execute(() -> handleClient(client));
                } catch (IOException e) {
                    if (!running) break;
                }
            }
        });
        return port;
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket;
             InputStream rawIn = new BufferedInputStream(s.getInputStream());
             OutputStream out = new BufferedOutputStream(s.getOutputStream())) {

            s.setSoTimeout(60000);

            String requestLine = readLine(rawIn);
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.US);
            String fullPath = parts[1];
            String path = fullPath.contains("?") ? fullPath.substring(0, fullPath.indexOf("?")) : fullPath;

            int contentLength = 0;
            String contentType = "";
            String rangeHeader = null;

            String headerLine;
            while ((headerLine = readLine(rawIn)) != null && !headerLine.isEmpty()) {
                String lower = headerLine.toLowerCase(Locale.US);
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(headerLine.substring(15).trim());
                } else if (lower.startsWith("content-type:")) {
                    contentType = headerLine.substring(13).trim();
                } else if (lower.startsWith("range:")) {
                    rangeHeader = headerLine.substring(6).trim();
                }
            }

            byte[] body = new byte[0];
            if (contentLength > 0) {
                body = readExactBytes(rawIn, contentLength);
            }

            routeRequest(method, path, contentType, rangeHeader, body, out);
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private void routeRequest(String method, String path, String contentType, String rangeHeader, byte[] body, OutputStream out) throws Exception {
        if ("OPTIONS".equals(method)) {
            sendEmptyResponse(out, "204 No Content");
            return;
        }

        if ("GET".equals(method)) {
            if ("/".equals(path) || "/index.html".equals(path)) {
                serveAsset(out, "static/index.html", "text/html; charset=utf-8");
                return;
            }
            if ("/manifest.json".equals(path)) {
                serveAsset(out, "static/manifest.json", "application/manifest+json");
                return;
            }
            if ("/sw.js".equals(path)) {
                serveAsset(out, "static/sw.js", "application/javascript");
                return;
            }
            if (path.startsWith("/static/")) {
                String assetPath = path.substring(1); // "static/..."
                serveAsset(out, assetPath, guessMimeType(assetPath));
                return;
            }
            if ("/api/voices".equals(path)) {
                try {
                    String json = EdgeTtsEngine.fetchVoicesJson();
                    sendJsonResponse(out, "200 OK", json);
                } catch (Exception e) {
                    sendJsonError(out, "500 Internal Server Error", "Error obteniendo voces (verifica tu conexión a internet): " + e.getMessage());
                }
                return;
            }
            if ("/api/history".equals(path)) {
                JSONObject res = new JSONObject();
                res.put("history", loadHistory());
                sendJsonResponse(out, "200 OK", res.toString());
                return;
            }
            if (path.startsWith("/api/synthesize/status/")) {
                String jobId = path.substring("/api/synthesize/status/".length()).trim();
                JSONObject job = synthJobs.get(jobId);
                if (job == null) {
                    sendJsonError(out, "404 Not Found", "Tarea no encontrada.");
                    return;
                }
                sendJsonResponse(out, "200 OK", job.toString());
                return;
            }
            if ("/api/synthesize/active".equals(path)) {
                JSONObject res = new JSONObject();
                JSONObject active = null;
                for (JSONObject job : synthJobs.values()) {
                    if ("running".equals(job.optString("status"))) {
                        active = job;
                        break;
                    }
                }
                res.put("active_job", active != null ? active : JSONObject.NULL);
                sendJsonResponse(out, "200 OK", res.toString());
                return;
            }
            if (path.startsWith("/api/audio/")) {
                String filename = URLDecoder.decode(path.substring("/api/audio/".length()), "UTF-8");
                File file = new File(outputsDir, new File(filename).getName());
                if (!file.exists()) {
                    sendJsonError(out, "404 Not Found", "Audio no encontrado.");
                    return;
                }
                serveAudioFile(out, file, rangeHeader, false);
                return;
            }
            if (path.startsWith("/api/download/")) {
                String filename = URLDecoder.decode(path.substring("/api/download/".length()), "UTF-8");
                File file = new File(outputsDir, new File(filename).getName());
                if (!file.exists()) {
                    sendJsonError(out, "404 Not Found", "Archivo MP3 no encontrado.");
                    return;
                }
                // Also copy to Android public Downloads folder automatically
                saveFileToPublicDownloads(file);
                serveAudioFile(out, file, null, true);
                return;
            }
        }

        if ("POST".equals(method)) {
            if ("/api/synthesize".equals(path)) {
                handleSynthesize(body, out);
                return;
            }
            if ("/api/extract-document".equals(path)) {
                handleExtractDocument(contentType, body, out);
                return;
            }
        }

        if ("DELETE".equals(method) && path.startsWith("/api/history/")) {
            String itemId = path.substring("/api/history/".length()).trim();
            JSONArray hist = loadHistory();
            JSONArray updated = new JSONArray();
            for (int i = 0; i < hist.length(); i++) {
                JSONObject obj = hist.getJSONObject(i);
                if (itemId.equals(obj.optString("id"))) {
                    File f = new File(outputsDir, obj.optString("filename", ""));
                    if (f.exists()) f.delete();
                } else {
                    updated.put(obj);
                }
            }
            saveHistory(updated);
            sendJsonResponse(out, "200 OK", "{\"ok\":true}");
            return;
        }

        sendJsonError(out, "404 Not Found", "Ruta no encontrada: " + path);
    }

    private void handleSynthesize(byte[] body, OutputStream out) throws Exception {
        JSONObject req = new JSONObject(new String(body, StandardCharsets.UTF_8));
        String text = req.optString("text", "").trim();
        if (text.isEmpty()) {
            sendJsonError(out, "400 Bad Request", "El texto no puede estar vacío.");
            return;
        }

        String voice = req.optString("voice", "es-MX-DaliaNeural");
        int rate = req.optInt("rate", 0);
        int pitch = req.optInt("pitch", 0);
        int volume = req.optInt("volume", 0);
        String pauseMode = req.optString("pause_mode", "natural");
        String title = req.isNull("title") ? "" : req.optString("title", "").trim();
        boolean isPreview = req.optBoolean("is_preview", false);

        List<String> chunks = EdgeTtsEngine.preprocessAndChunk(text, pauseMode, 1800);
        if (chunks.isEmpty()) {
            sendJsonError(out, "400 Bad Request", "No se encontró texto válido para convertir.");
            return;
        }

        if (isPreview) {
            try {
                byte[] previewMp3 = EdgeTtsEngine.synthesizeAllChunks(chunks, voice, rate, pitch, volume);
                sendBinaryResponse(out, "200 OK", "audio/mpeg", previewMp3, null);
            } catch (Exception e) {
                sendJsonError(out, "500 Internal Server Error", "Error en prueba de voz: " + e.getMessage());
            }
            return;
        }

        final String jobId = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        final JSONObject jobState = new JSONObject();
        jobState.put("job_id", jobId);
        jobState.put("status", "running");
        jobState.put("current_chunk", 0);
        jobState.put("total_chunks", chunks.size());
        synthJobs.put(jobId, jobState);

        // Keep CPU and Wi-Fi alive in background while synthesizing
        VozIABackgroundService.startSynthesisService(context, "Convirtiendo audio MP3 (0/" + chunks.size() + " bloques)...");

        executor.execute(() -> {
            String rawTitle = !title.isEmpty() ? title : (text.length() > 35 ? text.substring(0, 35) : text);
            String safeTitle = rawTitle.replaceAll("[^a-zA-Z0-9_\\-\\sáéíóúÁÉÍÓÚñÑ]", "").trim().replaceAll("\\s+", "_");
            if (safeTitle.length() > 40) safeTitle = safeTitle.substring(0, 40);
            if (safeTitle.isEmpty()) safeTitle = "audio_vozia";

            String filename = safeTitle + "_" + jobId + ".mp3";
            File outFile = new File(outputsDir, filename);
            long startMs = System.currentTimeMillis();

            try {
                EdgeTtsEngine.synthesizeToFile(chunks, voice, rate, pitch, volume, outFile, (currentChunk, totalChunks) -> {
                    try {
                        jobState.put("current_chunk", currentChunk);
                        VozIABackgroundService.startSynthesisService(context, "Convirtiendo bloque " + currentChunk + " de " + totalChunks + " a MP3...");
                    } catch (Exception ignored) {}
                });

                double elapsedSec = Math.round(((System.currentTimeMillis() - startMs) / 1000.0) * 100.0) / 100.0;
                double sizeKb = Math.round((outFile.length() / 1024.0) * 10.0) / 10.0;

                String displayTitle = !title.isEmpty() ? title : (text.length() > 55 ? text.substring(0, 55) + "..." : text);
                String preview = text.length() > 160 ? text.substring(0, 160) + "..." : text;
                String fullText = text.length() > 5000 ? text.substring(0, 5000) : text;
                int wordCount = text.split("\\s+").length;

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

                JSONObject record = new JSONObject();
                record.put("id", jobId);
                record.put("title", displayTitle);
                record.put("text_preview", preview);
                record.put("full_text", fullText);
                record.put("char_count", text.length());
                record.put("word_count", wordCount);
                record.put("chunks_count", chunks.size());
                record.put("voice", voice);
                record.put("rate", rate);
                record.put("pitch", pitch);
                record.put("volume", volume);
                record.put("filename", filename);
                record.put("url", "/api/audio/" + filename);
                record.put("download_url", "/api/download/" + filename);
                record.put("size_kb", sizeKb);
                record.put("elapsed_sec", elapsedSec);
                record.put("created_at", sdf.format(new Date()));

                JSONArray hist = loadHistory();
                JSONArray newHist = new JSONArray();
                newHist.put(record);
                for (int i = 0; i < hist.length() && i < 99; i++) {
                    newHist.put(hist.getJSONObject(i));
                }
                saveHistory(newHist);

                jobState.put("status", "done");
                jobState.put("record", record);
            } catch (Exception e) {
                if (outFile.exists()) outFile.delete();
                try {
                    jobState.put("status", "error");
                    jobState.put("error", "Error al generar el audio: " + e.getMessage());
                } catch (Exception ignored) {}
            } finally {
                VozIABackgroundService.stopSynthesisService(context);
            }
        });

        sendJsonResponse(out, "200 OK", jobState.toString());
    }

    private void handleExtractDocument(String contentType, byte[] body, OutputStream out) throws Exception {
        if (body == null || body.length == 0) {
            sendJsonError(out, "400 Bad Request", "El archivo subido está vacío.");
            return;
        }

        // Parse multipart/form-data boundary
        String boundary = null;
        if (contentType != null && contentType.contains("boundary=")) {
            boundary = contentType.substring(contentType.indexOf("boundary=") + 9).trim();
            if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                boundary = boundary.substring(1, boundary.length() - 1);
            }
        }
        if (boundary == null) {
            sendJsonError(out, "400 Bad Request", "Formato multipart inválido.");
            return;
        }

        MultipartFile part = parseMultipartFile(body, boundary);
        if (part == null || part.data.length == 0) {
            sendJsonError(out, "400 Bad Request", "No se pudo leer el archivo adjunto.");
            return;
        }

        String filename = part.filename != null ? part.filename : "documento.txt";
        String lowerName = filename.toLowerCase(Locale.US);
        String ext = lowerName.contains(".") ? lowerName.substring(lowerName.lastIndexOf(".")) : ".txt";

        String extractedText = "";
        int pagesCount = 1;

        try {
            if (".pdf".equals(ext)) {
                try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(part.data))) {
                    pagesCount = doc.getNumberOfPages();
                    PDFTextStripper stripper = new PDFTextStripper();
                    extractedText = stripper.getText(doc);
                }
            } else if (".docx".equals(ext)) {
                extractedText = extractDocxBytes(part.data);
            } else if (".txt".equals(ext) || ".md".equals(ext) || ".srt".equals(ext) || ".csv".equals(ext)) {
                extractedText = new String(part.data, StandardCharsets.UTF_8);
            } else {
                sendJsonError(out, "400 Bad Request", "Formato '" + ext + "' no soportado. Sube un archivo PDF (.pdf), Word (.docx) o Texto (.txt).");
                return;
            }
        } catch (Exception e) {
            sendJsonError(out, "400 Bad Request", "Error leyendo documento (" + filename + "): " + e.getMessage());
            return;
        }

        if (extractedText != null) {
            extractedText = extractedText.replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
        }
        if (extractedText == null || extractedText.isEmpty()) {
            sendJsonError(out, "400 Bad Request", "No se encontró texto seleccionable en el documento.");
            return;
        }

        String cleanTitle = filename.contains(".") ? filename.substring(0, filename.lastIndexOf(".")) : filename;
        int words = extractedText.split("\\s+").length;

        JSONObject resp = new JSONObject();
        resp.put("title", cleanTitle);
        resp.put("filename", filename);
        resp.put("extension", ext);
        resp.put("pages", pagesCount);
        resp.put("char_count", extractedText.length());
        resp.put("word_count", words);
        resp.put("text", extractedText);

        sendJsonResponse(out, "200 OK", resp.toString());
    }

    private String extractDocxBytes(byte[] docxBytes) throws Exception {
        List<String> paragraphs = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(docxBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    ByteArrayOutputStream xmlOut = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = zis.read(buf)) != -1) {
                        xmlOut.write(buf, 0, r);
                    }
                    XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                    factory.setNamespaceAware(true);
                    XmlPullParser parser = factory.newPullParser();
                    parser.setInput(new ByteArrayInputStream(xmlOut.toByteArray()), "UTF-8");

                    StringBuilder currentPara = new StringBuilder();
                    int eventType = parser.getEventType();
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG) {
                            String name = parser.getName();
                            if ("t".equals(name)) {
                                String txt = parser.nextText();
                                if (txt != null) currentPara.append(txt);
                            } else if ("tab".equals(name) || "br".equals(name)) {
                                currentPara.append(" ");
                            }
                        } else if (eventType == XmlPullParser.END_TAG) {
                            if ("p".equals(parser.getName())) {
                                String pStr = currentPara.toString().trim();
                                if (!pStr.isEmpty()) {
                                    paragraphs.add(pStr);
                                }
                                currentPara.setLength(0);
                            }
                        }
                        eventType = parser.next();
                    }
                    break;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (i > 0) sb.append("\n\n");
            sb.append(paragraphs.get(i));
        }
        return sb.toString();
    }

    private static class MultipartFile {
        String filename;
        byte[] data;
    }

    private MultipartFile parseMultipartFile(byte[] body, String boundary) {
        byte[] boundBytes = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int start = indexOf(body, boundBytes, 0);
        if (start == -1) return null;

        int headerStart = start + boundBytes.length + 2; // skip \r\n
        byte[] doubleCrlf = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        int headerEnd = indexOf(body, doubleCrlf, headerStart);
        if (headerEnd == -1) return null;

        String headers = new String(body, headerStart, headerEnd - headerStart, StandardCharsets.UTF_8);
        String filename = "documento.txt";
        int fnIdx = headers.indexOf("filename=\"");
        if (fnIdx != -1) {
            int fnEnd = headers.indexOf("\"", fnIdx + 10);
            if (fnEnd != -1) {
                filename = headers.substring(fnIdx + 10, fnEnd);
            }
        }

        int dataStart = headerEnd + 4;
        byte[] endBound = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int dataEnd = indexOf(body, endBound, dataStart);
        if (dataEnd == -1) dataEnd = body.length;

        MultipartFile mf = new MultipartFile();
        mf.filename = filename;
        mf.data = new byte[Math.max(0, dataEnd - dataStart)];
        System.arraycopy(body, dataStart, mf.data, 0, mf.data.length);
        return mf;
    }

    private int indexOf(byte[] source, byte[] target, int fromIndex) {
        outer:
        for (int i = fromIndex; i <= source.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    public boolean saveFileToPublicDownloads(File mp3File) {
        try {
            String fileName = mp3File.getName();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = context.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = resolver.openOutputStream(uri);
                         FileInputStream fis = new FileInputStream(mp3File)) {
                        if (os != null) {
                            byte[] buf = new byte[8192];
                            int r;
                            while ((r = fis.read(buf)) != -1) {
                                os.write(buf, 0, r);
                            }
                        }
                    }
                    return true;
                }
            } else {
                File pubDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!pubDir.exists()) pubDir.mkdirs();
                File dest = new File(pubDir, fileName);
                try (FileInputStream fis = new FileInputStream(mp3File);
                     FileOutputStream fos = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = fis.read(buf)) != -1) {
                        fos.write(buf, 0, r);
                    }
                }
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private synchronized JSONArray loadHistory() {
        if (!historyFile.exists()) return new JSONArray();
        try (FileInputStream fis = new FileInputStream(historyFile)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = fis.read(buf)) != -1) baos.write(buf, 0, r);
            JSONArray raw = new JSONArray(new String(baos.toByteArray(), StandardCharsets.UTF_8));
            JSONArray valid = new JSONArray();
            for (int i = 0; i < raw.length(); i++) {
                JSONObject item = raw.getJSONObject(i);
                File f = new File(outputsDir, item.optString("filename", ""));
                if (f.exists()) valid.put(item);
            }
            return valid;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private synchronized void saveHistory(JSONArray items) {
        try (FileOutputStream fos = new FileOutputStream(historyFile)) {
            fos.write(items.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private void serveAsset(OutputStream out, String assetPath, String mimeType) throws IOException {
        try (InputStream is = context.getAssets().open(assetPath)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
            sendBinaryResponse(out, "200 OK", mimeType, baos.toByteArray(), null);
        } catch (IOException e) {
            sendJsonError(out, "404 Not Found", "Asset no encontrado: " + assetPath);
        }
    }

    private void serveAudioFile(OutputStream out, File file, String rangeHeader, boolean isAttachment) throws IOException {
        long fileLen = file.length();
        long start = 0;
        long end = fileLen - 1;
        boolean partial = false;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String[] ranges = rangeHeader.substring(6).split("-");
            try {
                if (!ranges[0].isEmpty()) start = Long.parseLong(ranges[0]);
                if (ranges.length > 1 && !ranges[1].isEmpty()) end = Long.parseLong(ranges[1]);
                if (start < fileLen) partial = true;
            } catch (NumberFormatException ignored) {}
        }

        long contentLen = end - start + 1;
        StringBuilder hdr = new StringBuilder();
        hdr.append("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK").append("\r\n");
        hdr.append("Content-Type: audio/mpeg\r\n");
        hdr.append("Accept-Ranges: bytes\r\n");
        hdr.append("Content-Length: ").append(contentLen).append("\r\n");
        if (partial) {
            hdr.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(fileLen).append("\r\n");
        }
        if (isAttachment) {
            hdr.append("Content-Disposition: attachment; filename=\"").append(file.getName()).append("\"\r\n");
        }
        hdr.append("Connection: close\r\n\r\n");
        out.write(hdr.toString().getBytes(StandardCharsets.US_ASCII));

        try (FileInputStream fis = new FileInputStream(file)) {
            if (start > 0) fis.skip(start);
            byte[] buf = new byte[8192];
            long remaining = contentLen;
            while (remaining > 0) {
                int r = fis.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (r == -1) break;
                out.write(buf, 0, r);
                remaining -= r;
            }
        }
    }

    private void sendEmptyResponse(OutputStream out, String status) throws IOException {
        String resp = "HTTP/1.1 " + status + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: *\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.US_ASCII));
    }

    private void sendJsonResponse(OutputStream out, String status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        sendBinaryResponse(out, status, "application/json; charset=utf-8", bytes, null);
    }

    private void sendJsonError(OutputStream out, String status, String message) throws IOException {
        try {
            JSONObject err = new JSONObject();
            err.put("detail", message);
            sendJsonResponse(out, status, err.toString());
        } catch (Exception e) {
            sendJsonResponse(out, status, "{\"detail\":\"Error\"}");
        }
    }

    private void sendBinaryResponse(OutputStream out, String status, String contentType, byte[] data, String extraHeader) throws IOException {
        StringBuilder hdr = new StringBuilder();
        hdr.append("HTTP/1.1 ").append(status).append("\r\n");
        hdr.append("Content-Type: ").append(contentType).append("\r\n");
        hdr.append("Content-Length: ").append(data.length).append("\r\n");
        hdr.append("Access-Control-Allow-Origin: *\r\n");
        if (extraHeader != null) hdr.append(extraHeader).append("\r\n");
        hdr.append("Connection: close\r\n\r\n");
        out.write(hdr.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(data);
    }

    private String guessMimeType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                int next = in.read();
                if (next == '\n') break;
                baos.write(b);
                if (next != -1) baos.write(next);
            } else if (b == '\n') {
                break;
            } else {
                baos.write(b);
            }
        }
        if (b == -1 && baos.size() == 0) return null;
        return new String(baos.toByteArray(), StandardCharsets.US_ASCII);
    }

    private byte[] readExactBytes(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int total = 0;
        while (total < len) {
            int r = in.read(buf, total, len - total);
            if (r == -1) break;
            total += r;
        }
        return buf;
    }
}

