package com.gathin.tvhome.push;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import com.gathin.tvhome.ApkInstaller;
import com.gathin.tvhome.LanAddress;
import com.gathin.tvhome.UserPrefs;
import com.gathin.tvhome.WallpaperStore;

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
 * Tiny embedded HTTP for LAN APK push.
 * 端口从 {@link #BASE_PORT}（5010）起绑；被占用则 +1 递增，直到可用。
 */
public final class PushHttpServer {
    private static final String TAG = "TvHome.PushHttp";
    /** 局域网推送固定起始端口。 */
    public static final int BASE_PORT = 5010;
    /** 最多尝试递增的端口个数，避免死循环。 */
    private static final int MAX_PORT_TRIES = 500;
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
        // 必须用 CAS，避免 onCreate/onStartCommand 并发启动绑出 5010+5011 两个僵尸口
        if (!running.compareAndSet(false, true)) return;
        pool.execute(() -> {
            ServerSocket local = null;
            try {
                // 忽略旧版随机端口缓存：统一从 5010 起找空闲口
                local = bindFromBase();
                server = local;
                port = local.getLocalPort();
                UserPrefs.setPushPort(app, port);
                Log.i(TAG, "listening on " + port);
                while (running.get()) {
                    try {
                        // 始终 accept 本地 socket，避免字段被并发覆盖后串口
                        Socket client = local.accept();
                        pool.execute(() -> handle(client));
                    } catch (IOException e) {
                        if (running.get()) Log.w(TAG, "accept", e);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "start failed", e);
                running.set(false);
            } finally {
                if (local != null) {
                    try { local.close(); } catch (IOException ignored) {}
                }
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

    /** 从 5010 起顺序尝试绑定，占用则端口 +1。 */
    private static ServerSocket bindFromBase() throws IOException {
        for (int i = 0; i < MAX_PORT_TRIES; i++) {
            int p = BASE_PORT + i;
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(p));
                return ss;
            } catch (IOException ignored) {
                // 被占用，试下一个
            }
        }
        throw new IOException("no free port from " + BASE_PORT);
    }

    private void handle(Socket socket) {
        try {
            // 手机上传大 APK 可能较慢
            socket.setSoTimeout(600_000);
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
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }
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
            // 手机浏览器打开 http://电视IP:端口/ 即可远程推送 APK（无需电脑 admin / OTA）
            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path) || "/push".equals(path))) {
                write(out, 200, "text/html; charset=utf-8", PushWebUi.html());
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
            // 壁纸：列表 / 推送图片 / 删除
            if ("GET".equals(method) && "/api/wallpapers".equals(path)) {
                JSONArray arr = new JSONArray();
                for (File f : WallpaperStore.list(app)) {
                    arr.put(new JSONObject()
                            .put("name", f.getName())
                            .put("size", f.length())
                            .put("time", f.lastModified()));
                }
                writeJson(out, 200, new JSONObject()
                        .put("ok", true)
                        .put("mode", UserPrefs.wallpaperRotate(app) ? UserPrefs.MODE_ROTATE : UserPrefs.MODE_FIXED)
                        .put("fixed", UserPrefs.wallpaperFixed(app))
                        .put("rotateMinutes", UserPrefs.wallpaperRotateMinutes(app))
                        .put("items", arr));
                return;
            }
            if ("POST".equals(method) && "/api/wallpaper".equals(path)) {
                boolean force = queryContains(query, "force=1") || queryContains(query, "force=true");
                handleWallpaperPush(in, contentType, contentLength, out, force);
                return;
            }
            if ("POST".equals(method) && "/api/wallpaper/delete".equals(path)) {
                JSONObject body = readJsonBody(in, contentLength);
                boolean ok = WallpaperStore.delete(app, body.optString("name"));
                if (ok) WallpaperStore.notifyChanged(app);
                writeJson(out, ok ? 200 : 404, new JSONObject().put("ok", ok));
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

    private static boolean queryContains(String query, String token) {
        if (query == null || query.isEmpty()) return false;
        for (String part : query.split("&")) {
            if (token.equalsIgnoreCase(part.trim())) return true;
        }
        return false;
    }

    /**
     * 解析 multipart 文件名：优先 filename*=UTF-8''…，再 filename=，
     * 并纠正浏览器把 UTF-8 字节当 Latin-1 传来的中文乱码。
     */
    static String parseMultipartFilename(String partHeaders, String fallback) {
        String star = null;
        String plain = null;
        for (String line : partHeaders.split("\r\n")) {
            String lower = line.toLowerCase(Locale.US);
            if (!lower.startsWith("content-disposition:")) continue;
            int starAt = indexOfIgnoreCase(line, "filename*=");
            if (starAt >= 0) {
                String v = line.substring(starAt + "filename*=".length()).trim();
                if (v.endsWith(";")) v = v.substring(0, v.length() - 1).trim();
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                    v = v.substring(1, v.length() - 1);
                }
                // RFC5987: UTF-8''percent-encoded
                int tick = v.indexOf("''");
                if (tick >= 0) v = v.substring(tick + 2);
                try {
                    star = java.net.URLDecoder.decode(v, "UTF-8");
                } catch (Exception e) {
                    star = v;
                }
            }
            // 普通 filename=（不要匹配到 filename*=）
            int fnAt = indexOfIgnoreCase(line, "filename=");
            if (fnAt >= 0 && (starAt < 0 || fnAt != starAt)) {
                // 若命中的是 filename*= 里的 filename= 子串则跳过
                if (fnAt > 0 && line.charAt(fnAt - 1) == '*') {
                    // filename*=
                } else {
                    String v = line.substring(fnAt + "filename=".length()).trim();
                    if (v.startsWith("\"")) {
                        int end = v.indexOf('"', 1);
                        plain = end > 0 ? v.substring(1, end) : v;
                    } else {
                        plain = v.split(";")[0].trim();
                    }
                }
            }
        }
        String name = star != null && !star.isEmpty() ? star : plain;
        if (name == null || name.trim().isEmpty()) return fallback;
        return fixUtf8Mojibake(name.trim());
    }

    private static int indexOfIgnoreCase(String s, String token) {
        return s.toLowerCase(Locale.US).indexOf(token.toLowerCase(Locale.US));
    }

    /** 浏览器常把 UTF-8 文件名按 ISO-8859-1 塞进 filename="…"，这里还原中文。 */
    public static String fixUtf8Mojibake(String name) {
        if (name == null || name.isEmpty()) return name;
        if (containsCjk(name)) return name;
        try {
            byte[] raw = name.getBytes(StandardCharsets.ISO_8859_1);
            String asUtf8 = new String(raw, StandardCharsets.UTF_8);
            if (asUtf8.indexOf('\uFFFD') >= 0) return name;
            if (containsCjk(asUtf8) || countPrintable(asUtf8) >= countPrintable(name)) {
                return asUtf8;
            }
        } catch (Exception ignored) {
        }
        return name;
    }

    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    private static int countPrintable(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != 0x7F) n++;
        }
        return n;
    }

    /** 推送壁纸图片；force=true 时强制固定为该张并只在首屏展示。 */
    private void handleWallpaperPush(InputStream in, String contentType, long contentLength,
                                     OutputStream out, boolean force) throws Exception {
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
        MultipartPart part = MultipartPart.readFilePartToTemp(in, boundary, contentLength, app.getCacheDir());
        if (part == null || part.temp == null || part.temp.length() == 0) {
            writeJson(out, 400, err("no file"));
            return;
        }
        try (java.io.InputStream fin = new java.io.FileInputStream(part.temp)) {
            File saved = WallpaperStore.add(app, part.filename, fin);
            if (force) {
                UserPrefs.setWallpaperMode(app, UserPrefs.MODE_FIXED);
                UserPrefs.setWallpaperFixed(app, saved.getName());
            }
            WallpaperStore.notifyChanged(app);
            writeJson(out, 200, new JSONObject()
                    .put("ok", true)
                    .put("name", saved.getName())
                    .put("force", force)
                    .put("size", saved.length()));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            part.temp.delete();
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
        // 流式落盘，避免手机上传大 APK 时整包进内存
        MultipartPart part = MultipartPart.readFilePartToTemp(in, boundary, contentLength, app.getCacheDir());
        if (part == null || part.temp == null || part.temp.length() == 0) {
            writeJson(out, 400, err("no file"));
            return;
        }
        try (java.io.InputStream fin = new java.io.FileInputStream(part.temp)) {
            PushStore.Item item = PushStore.add(app, part.filename, fin, part.temp.length());
            writeJson(out, 200, item.toJson().put("ok", true));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            part.temp.delete();
        }
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

    /** Multipart 单文件：读完 part 头后流式写入临时文件，直到 \r\n--boundary。 */
    private static final class MultipartPart {
        final String filename;
        final File temp;

        MultipartPart(String filename, File temp) {
            this.filename = filename;
            this.temp = temp;
        }

        static MultipartPart readFilePartToTemp(InputStream in, String boundary, long contentLength, File cacheDir)
                throws IOException {
            byte[] openMark = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
            byte[] endMark = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
            byte[] crlfcrlf = {'\r', '\n', '\r', '\n'};

            // 1) 读到第一个 part 头结束
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            boolean sawOpen = false;
            while (head.size() < 64 * 1024) {
                int b = in.read();
                if (b < 0) return null;
                head.write(b);
                byte[] bytes = head.toByteArray();
                if (!sawOpen && indexOf(bytes, openMark, 0) >= 0) sawOpen = true;
                if (sawOpen && bytes.length >= 4
                        && bytes[bytes.length - 4] == '\r' && bytes[bytes.length - 3] == '\n'
                        && bytes[bytes.length - 2] == '\r' && bytes[bytes.length - 1] == '\n') {
                    break;
                }
            }
            byte[] headerBytes = head.toByteArray();
            int headerStart = indexOf(headerBytes, openMark, 0);
            if (headerStart < 0) return null;
            int afterOpen = headerStart + openMark.length;
            if (afterOpen + 1 < headerBytes.length && headerBytes[afterOpen] == '\r') afterOpen += 2;
            int headerEnd = indexOf(headerBytes, crlfcrlf, afterOpen);
            if (headerEnd < 0) return null;
            String partHeaders = new String(headerBytes, afterOpen, headerEnd - afterOpen, StandardCharsets.ISO_8859_1);
            String filename = parseMultipartFilename(partHeaders, "upload.bin");
            int bodyPrefStart = headerEnd + 4;

            // 2) 流式写文件，滑动保留可能的 boundary 前缀
            File temp = File.createTempFile("push-", ".apk", cacheDir);
            try (java.io.OutputStream fos = new java.io.FileOutputStream(temp)) {
                ByteArrayOutputStream pending = new ByteArrayOutputStream();
                for (int i = bodyPrefStart; i < headerBytes.length; i++) pending.write(headerBytes[i]);
                byte[] buf = new byte[64 * 1024];
                while (true) {
                    byte[] p = pending.toByteArray();
                    int hit = indexOf(p, endMark, 0);
                    if (hit >= 0) {
                        fos.write(p, 0, hit);
                        return new MultipartPart(filename, temp);
                    }
                    int keep = Math.min(endMark.length - 1, p.length);
                    int flush = p.length - keep;
                    if (flush > 0) {
                        fos.write(p, 0, flush);
                        pending.reset();
                        pending.write(p, flush, keep);
                    }
                    int n = in.read(buf);
                    if (n < 0) {
                        byte[] rest = pending.toByteArray();
                        if (rest.length > 0) fos.write(rest);
                        return new MultipartPart(filename, temp);
                    }
                    pending.write(buf, 0, n);
                    // contentLength 仅作上限提示，不强制截断（部分浏览器不准）
                    if (contentLength > 0 && temp.length() > contentLength + 1024L) {
                        break;
                    }
                }
                return new MultipartPart(filename, temp);
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                throw e;
            }
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
