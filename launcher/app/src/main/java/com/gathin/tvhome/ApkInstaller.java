package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
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

/**
 * Download an APK from a URL (or install a local file) via PackageInstaller session.
 * Progress callbacks run on the main thread.
 *
 * 注意：commit() 之后绝不能再 close/abandon Session，否则会失败并提示 files still open。
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
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            File apk = null;
            try {
                post(cb, 0, "downloading");
                apk = File.createTempFile("ota-", ".apk", app.getCacheDir());
                HttpURLConnection conn = (HttpURLConnection) new URL(url.trim()).openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(120_000);
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
                long total = conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    long read = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        read += n;
                        if (total > 0) {
                            int pct = (int) Math.min(90, (read * 90) / total);
                            post(cb, pct, "downloading");
                        }
                    }
                    out.flush();
                }
                // 下载流已全部关闭，再提交安装，避免 files still open
                post(cb, 92, "installing");
                installFileLocked(app, apk, cb);
            } catch (Exception e) {
                Log.w(TAG, "download", e);
                postDone(cb, false, e.getMessage() == null ? "failed" : e.getMessage());
                if (apk != null) //noinspection ResultOfMethodCallIgnored
                    apk.delete();
            }
        });
    }

    public static void installFile(Context ctx, File apk, Progress cb) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            try {
                installFileLocked(app, apk, cb);
            } catch (Exception e) {
                Log.w(TAG, "install", e);
                postDone(cb, false, e.getMessage() == null ? "install failed" : e.getMessage());
            }
        });
    }

    /** 已在 IO 线程上执行；写完并关闭所有流后再 commit，且 commit 后不再 close Session。 */
    private static void installFileLocked(Context app, File apk, Progress cb) throws Exception {
        if (apk == null || !apk.isFile() || apk.length() <= 0) {
            postDone(cb, false, "apk missing");
            return;
        }
        // 自更新：进程会被杀掉，提前预约拉起，避免安装后黑屏/需手动设置
        final boolean selfUpdate = UpdateRelaunch.isSelfApk(app, apk);
        if (selfUpdate) UpdateRelaunch.prepareIfSelfApk(app, apk);

        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        // 尽量声明目标包名，便于系统按更新处理
        try {
            android.content.pm.PackageInfo info =
                    app.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (info != null && info.packageName != null && Build.VERSION.SDK_INT >= 34) {
                params.setAppPackageName(info.packageName);
            } else if (info != null && info.packageName != null) {
                params.setAppPackageName(info.packageName);
            }
        } catch (Exception ignored) {
        }
        int sessionId = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(sessionId);
        boolean committed = false;
        try {
            // 必须在 commit 前关掉 write 流，否则 PackageManager 报 files still open
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                session.fsync(out);
            }

            Intent callback = new Intent(ACTION_STATUS).setPackage(app.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent piIntent = PendingIntent.getBroadcast(app, sessionId, callback, flags);

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                            PackageInstaller.STATUS_FAILURE);
                    String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            try {
                                c.startActivity(confirm);
                                post(cb, 95, "confirm");
                                // 仍等最终 SUCCESS/FAILURE，暂不 unregister
                                return;
                            } catch (Exception e) {
                                try {
                                    c.unregisterReceiver(this);
                                } catch (Exception ignored) {
                                }
                                postDone(cb, false, e.getMessage());
                                return;
                            }
                        }
                    }
                    try {
                        c.unregisterReceiver(this);
                    } catch (Exception ignored) {
                    }
                    boolean ok = status == PackageInstaller.STATUS_SUCCESS;
                    // 若进程还活着（少见），安装成功后立刻拉起桌面
                    if (ok && selfUpdate) {
                        UpdateRelaunch.schedule(c.getApplicationContext(), 500L);
                        UpdateRelaunch.launchHome(c.getApplicationContext());
                    }
                    postDone(cb, ok, msg == null ? (ok ? "ok" : "install failed status=" + status) : msg);
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_STATUS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            session.commit(piIntent.getIntentSender());
            committed = true;
            post(cb, 98, "committing");
        } finally {
            // commit 成功后不能再 close（会 abandon，导致 files still open / 安装失败）
            if (!committed) {
                try {
                    session.abandon();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void post(Progress cb, int pct, String msg) {
        MAIN.post(() -> cb.onProgress(pct, msg));
    }

    private static void postDone(Progress cb, boolean ok, String msg) {
        MAIN.post(() -> cb.onDone(ok, msg));
    }
}
