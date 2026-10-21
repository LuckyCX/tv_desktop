package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Download an APK from a URL (or install a local file) via PackageInstaller session.
 * Progress callbacks run on the main thread.
 */
public final class ApkInstaller {
    private static final String TAG = "TvHome.ApkInstall";
    private static final String ACTION_STATUS = "com.gathin.tvhome.APK_INSTALL_STATUS";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Progress {
        void onProgress(int percent, String message);
        void onDone(boolean ok, String message);
    }

    private ApkInstaller() {}

    public static void downloadAndInstall(Context ctx, String url, Progress cb) {
        if (url == null || url.trim().isEmpty()) {
            cb.onDone(false, "empty url");
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean(false);
        IO.execute(() -> {
            File apk = null;
            try {
                post(cb, 0, "downloading");
                apk = File.createTempFile("ota-", ".apk", ctx.getCacheDir());
                HttpURLConnection conn = (HttpURLConnection) new URL(url.trim()).openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(60_000);
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
                long total = conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    long read = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (cancelled.get()) return;
                        out.write(buf, 0, n);
                        read += n;
                        if (total > 0) {
                            int pct = (int) Math.min(90, (read * 90) / total);
                            post(cb, pct, "downloading");
                        }
                    }
                }
                post(cb, 92, "installing");
                installFile(ctx, apk, cb);
            } catch (Exception e) {
                Log.w(TAG, "download", e);
                postDone(cb, false, e.getMessage() == null ? "failed" : e.getMessage());
                if (apk != null) //noinspection ResultOfMethodCallIgnored
                    apk.delete();
            }
        });
    }

    public static void installFile(Context ctx, File apk, Progress cb) {
        IO.execute(() -> {
            try {
                PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int sessionId = pi.createSession(params);
                try (PackageInstaller.Session session = pi.openSession(sessionId);
                     InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    session.fsync(out);
                    Intent callback = new Intent(ACTION_STATUS).setPackage(ctx.getPackageName());
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent piIntent = PendingIntent.getBroadcast(ctx, sessionId, callback, flags);
                    BroadcastReceiver receiver = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context c, Intent intent) {
                            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                                    PackageInstaller.STATUS_FAILURE);
                            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                            try {
                                c.unregisterReceiver(this);
                            } catch (Exception ignored) {
                            }
                            boolean ok = status == PackageInstaller.STATUS_SUCCESS;
                            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                                if (confirm != null) {
                                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    try {
                                        c.startActivity(confirm);
                                        post(cb, 95, "confirm");
                                        return;
                                    } catch (Exception e) {
                                        ok = false;
                                        msg = e.getMessage();
                                    }
                                }
                            }
                            postDone(cb, ok, msg == null ? (ok ? "ok" : "install failed") : msg);
                        }
                    };
                    IntentFilter filter = new IntentFilter(ACTION_STATUS);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                    } else {
                        ctx.registerReceiver(receiver, filter);
                    }
                    session.commit(piIntent.getIntentSender());
                    post(cb, 98, "committing");
                }
            } catch (Exception e) {
                Log.w(TAG, "install", e);
                postDone(cb, false, e.getMessage() == null ? "install failed" : e.getMessage());
            }
        });
    }

    private static void post(Progress cb, int pct, String msg) {
        MAIN.post(() -> cb.onProgress(pct, msg));
    }

    private static void postDone(Progress cb, boolean ok, String msg) {
        MAIN.post(() -> cb.onDone(ok, msg));
    }
}
