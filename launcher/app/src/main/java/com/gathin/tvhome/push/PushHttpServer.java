package com.gathin.tvhome.push;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import com.gathin.tvhome.ApkInstaller;
import com.gathin.tvhome.LanAddress;
import com.gathin.tvhome.UserPrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tiny embedded HTTP for LAN APK push. Binds a random port in 10000–60000 (or cached).
 */
public final class PushHttpServer {
    private static final String TAG = "TvHome.PushHttp";
    private static PushHttpServer instance;

    private final Context app;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket server;
    private int port;

    public static synchronized PushHttpServer get(Context ctx) {
        if (instance == null) instance = new PushHttpServer(ctx.getApplicationContext());
        return instance;
    }

    private PushHttpServer(Context app) {
        this.app = app;
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running.get();
    }

    public synchronized void start() {
        if (running.get()) return;
        pool.execute(() -> {
            try {
                int prefer = UserPrefs.pushPort(app);
                server = bind(prefer);
                port = server.getLocalPort();
                UserPrefs.setPushPort(app, port);
                running.set(true);
                Log.i(TAG, "listening on " + port);
                while (running.get()) {
                    try {
                        Socket client = server.accept();
                        pool.execute(() -> handle(client));
                    } catch (IOException e) {
                        if (running.get()) Log.w(TAG, "accept", e);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "start failed", e);
                running.set(false);
            }
        });
    }

    public synchronized void stop() {
        running.set(false);
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
    }

    private static ServerSocket bind(int prefer) throws IOException {
        // Prefer cached port from last run when still free.
        if (prefer >= 10000 && prefer <= 60000) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(prefer));
                return ss;
            } catch (IOException ignored) {
            }
        }
        for (int i = 0; i < 40; i++) {
            int p = 10000 + (int) (Math.random() * 50001);
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(p));
                return ss;
            } catch (IOException ignored) {
            }
        }
        ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress(0));
        return ss;
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(120_000);
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
            int prev = 0;
            while (true) {
                int b = in.read();
                if (b < 0) return;
                headBuf.write(b);
                if (prev == '\r' && b == '\n') {
                    byte[] bytes = headBuf.toByteArray();
                    if (bytes.length >= 4
                            && bytes[bytes.length - 4] == '\r'
                            && bytes[bytes.length - 3] == '\n'
                            && bytes[bytes.length - 2] == '\r'
                            && bytes[bytes.length - 1] == '\n') break;
                }
                prev = b;
                if (headBuf.size() > 64 * 1024) return;
            }
            String header = headBuf.toString(StandardCharsets.ISO_8859_1.name());
            String[] lines = header.split("\r\n");
            if (lines.length == 0) return;
            String[] req = lines[0].split(" ");
            if (req.length < 2) return;
            String method = req[0].toUpperCase(Locale.US);
            String path = req[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            long contentLength = 0;
            String contentType = "";
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int c = line.indexOf(':');
                if (c < 0) continue;
                String k = line.substring(0, c).trim().toLowerCase(Locale.US);
                String v = line.substring(c + 1).trim();
                if ("content-length".equals(k)) contentLength = Long.parseLong(v);
                if ("content-type".equals(k)) contentType = v;
            }
            OutputStream out = socket.getOutputStream();
            if ("OPTIONS".equals(method)) {
                write(out, 204, "application/json", new byte[0]);
                return;
            }
            if ("GET".equals(method) && "/api/info".equals(path)) {
                String version = "?";
                try {
                    version = app.getPackageManager()
                            .getPackageInfo(app.getPackageName(), 0).versionName;
                } catch (Exception ignored) {
                }
                JSONObject info = new JSONObject()
                        .put("ip", LanAddress.ipv4(app))
                        .put("port", port)
                        .put("name", "CleanYourTV")
                        .put("version", version)
                        .put("package", app.getPackageName());
                writeJson(out, 200, info);
                return;
            }
            if ("GET".equals(method) && "/api/pushes".equals(path)) {
                JSONArray arr = new JSONArray();
                for (PushStore.Item item : PushStore.list(app)) arr.put(item.toJson());
                writeJson(out, 200, arr);
                return;
            }
            if ("POST".equals(method) && "/api/push".equals(path)) {
                handlePush(in, contentType, contentLength, out);
                return;
            }
            if ("POST".equals(method) && "/api/install".equals(path)) {
                JSONObject body = readJsonBody(in, contentLength);
                String id = body.optString("id");
                PushStore.Item item = PushStore.get(app, id);
                if (item == null) {
                    writeJson(out, 404, err("not found"));
                    return;
                }
                File apk = new File(item.path);
                if (!apk.isFile()) {
                    writeJson(out, 404, err("file missing"));
                    return;
                }
                ApkInstaller.installFile(app, apk, new ApkInstaller.Progress() {
                    @Override public void onProgress(int percent, String message) {}
                    @Override public void onDone(boolean ok, String message) {
                        Log.i(TAG, "install " + id + " ok=" + ok + " " + message);
                    }
                });
                writeJson(out, 200, new JSONObject().put("ok", true).put("id", id));
                return;
            }
            if ("POST".equals(method) && "/api/delete".equals(path)) {
                JSONObject body = readJsonBody(in, contentLength);
                boolean ok = PushStore.delete(app, body.optString("id"));
                writeJson(out, ok ? 200 : 404, new JSONObject().put("ok", ok));
                return;
            }
            if ("POST".equals(method) && "/api/uninstall".equals(path)) {
                JSONObject body = readJsonBody(in, contentLength);
                String pkg = body.optString("packageName");
                if (pkg.isEmpty()) pkg = body.optString("package");
                boolean ok = uninstall(pkg);
                writeJson(out, 200, new JSONObject().put("ok", ok).put("packageName", pkg));
                return;
            }
            writeJson(out, 404, err("not found"));
        } catch (Exception e) {
            Log.w(TAG, "handle", e);
            try {
                writeJson(socket.getOutputStream(), 500, err(e.getMessage()));
            } catch (Exception ignored) {
            }
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void handlePush(InputStream in, String contentType, long contentLength, OutputStream out)
            throws Exception {
        String boundary = null;
        if (contentType != null && contentType.contains("boundary=")) {
            boundary = contentType.substring(contentType.indexOf("boundary=") + 9).trim();
            if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                boundary = boundary.substring(1, boundary.length() - 1);
            }
        }
        if (boundary == null || boundary.isEmpty()) {
            writeJson(out, 400, err("multipart required"));
            return;
        }
        MultipartPart part = MultipartPart.readFilePart(in, boundary, contentLength);
        if (part == null || part.data.length == 0) {
            writeJson(out, 400, err("no file"));
            return;
        }
        PushStore.Item item = PushStore.add(app, part.filename,
                new java.io.ByteArrayInputStream(part.data), part.data.length);
        writeJson(out, 200, item.toJson().put("ok", true));
    }

    private boolean uninstall(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            app.getPackageManager().getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "uninstall", e);
            return false;
        }
    }

    private static JSONObject err(String msg) throws Exception {
        return new JSONObject().put("ok", false).put("error", msg == null ? "error" : msg);
    }

    private static JSONObject readJsonBody(InputStream in, long len) throws Exception {
        byte[] buf = readExact(in, len > 0 && len < 1_000_000 ? (int) len : 64 * 1024);
        if (buf.length == 0) return new JSONObject();
        return new JSONObject(new String(buf, StandardCharsets.UTF_8));
    }

    private static byte[] readExact(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        while (total < max) {
            int n = in.read(buf, 0, Math.min(buf.length, max - total));
            if (n < 0) break;
            bos.write(buf, 0, n);
            total += n;
            if (n == 0) break;
        }
        return bos.toByteArray();
    }

    private static void writeJson(OutputStream out, int code, Object json) throws IOException {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        write(out, code, "application/json; charset=utf-8", body);
    }

    private static void write(OutputStream out, int code, String type, byte[] body) throws IOException {
        String reason = code == 200 ? "OK" : (code == 204 ? "No Content" : (code == 404 ? "Not Found" : "Error"));
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        if (body.length > 0) out.write(body);
        out.flush();
    }

    /** Minimal multipart file-part reader (single file field). */
    private static final class MultipartPart {
        final String filename;
        final byte[] data;

        MultipartPart(String filename, byte[] data) {
            this.filename = filename;
            this.data = data;
        }

        static MultipartPart readFilePart(InputStream in, String boundary, long contentLength) throws IOException {
            byte[] raw = readExact(in, contentLength > 0 && contentLength < 512 * 1024 * 1024
                    ? (int) contentLength : 256 * 1024 * 1024);
            byte[] mark = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
            int pos = indexOf(raw, mark, 0);
            if (pos < 0) return null;
            pos += mark.length;
            if (pos + 1 < raw.length && raw[pos] == '-' && raw[pos + 1] == '-') return null;
            if (pos + 1 < raw.length && raw[pos] == '\r' && raw[pos + 1] == '\n') pos += 2;
            int headerEnd = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), pos);
            if (headerEnd < 0) return null;
            String partHeaders = new String(raw, pos, headerEnd - pos, StandardCharsets.ISO_8859_1);
            String filename = "app.apk";
            for (String line : partHeaders.split("\r\n")) {
                if (line.toLowerCase(Locale.US).startsWith("content-disposition:")) {
                    int fn = line.indexOf("filename=");
                    if (fn >= 0) {
                        String v = line.substring(fn + 9).trim();
                        if (v.startsWith("\"")) {
                            int end = v.indexOf('"', 1);
                            filename = end > 0 ? v.substring(1, end) : v;
                        } else {
                            filename = v.split(";")[0].trim();
                        }
                    }
                }
            }
            int dataStart = headerEnd + 4;
            byte[] endMark = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
            int dataEnd = indexOf(raw, endMark, dataStart);
            if (dataEnd < 0) dataEnd = raw.length;
            byte[] data = new byte[dataEnd - dataStart];
            System.arraycopy(raw, dataStart, data, 0, data.length);
            return new MultipartPart(filename, data);
        }

        private static int indexOf(byte[] data, byte[] pattern, int from) {
            outer:
            for (int i = from; i <= data.length - pattern.length; i++) {
                for (int j = 0; j < pattern.length; j++) {
                    if (data[i + j] != pattern[j]) continue outer;
                }
                return i;
            }
            return -1;
        }
    }
}
