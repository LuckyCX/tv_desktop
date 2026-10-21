package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;

/**
 * 自更新安装后自动拉起桌面。
 * PackageInstaller 替换自身时旧进程会被杀掉，动态 Receiver 到不了 SUCCESS；
 * 因此用 Alarm 兜底 + {@link UpdateRelaunchReceiver} 监听 MY_PACKAGE_REPLACED。
 */
public final class UpdateRelaunch {
    private static final String TAG = "TvHome.Relaunch";
    static final String ACTION_RELAUNCH = "com.gathin.tvhome.ACTION_RELAUNCH_AFTER_UPDATE";
    private static final int REQ_ALARM = 0x71C0;
    private static final long DELAY_MS = 2_500L;

    private UpdateRelaunch() {}

    /** 若 APK 是本包，安装前写入标记并预约拉起。 */
    public static void prepareIfSelfApk(Context ctx, File apk) {
        if (!isSelfApk(ctx, apk)) return;
        Context app = ctx.getApplicationContext();
        UserPrefs.setPendingRelaunch(app, true);
        schedule(app, DELAY_MS);
        // 再预约一次稍晚的，覆盖部分机型安装偏慢
        schedule(app, DELAY_MS + 4_000L);
        Log.i(TAG, "prepared self-update relaunch");
    }

    public static boolean isSelfApk(Context ctx, File apk) {
        if (apk == null || !apk.isFile()) return false;
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            return info != null && ctx.getPackageName().equals(info.packageName);
        } catch (Exception e) {
            Log.w(TAG, "read apk package", e);
            return false;
        }
    }

    public static void schedule(Context ctx, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = pendingRelaunch(ctx);
            long at = SystemClock.elapsedRealtime() + Math.max(500L, delayMs);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
                } else {
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
                }
            } catch (SecurityException se) {
                // 无精确闹钟权限时退化为普通闹钟
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Exception e) {
            Log.w(TAG, "schedule", e);
        }
    }

    /** 拉起主桌面（并尽量回到 Home 任务）。 */
    public static void launchHome(Context ctx) {
        Context app = ctx.getApplicationContext();
        try {
            Intent home = new Intent(app, MainActivity.class);
            home.setAction(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addCategory(Intent.CATEGORY_DEFAULT);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            app.startActivity(home);
            // 同时保活推送服务，避免更新后推送口断掉
            try {
                com.gathin.tvhome.push.PushService.ensureStarted(app);
            } catch (Exception ignored) {
            }
            UserPrefs.setPendingRelaunch(app, false);
            Log.i(TAG, "launched MainActivity after update");
        } catch (Exception e) {
            Log.w(TAG, "launchHome failed", e);
            // 失败则再约一次
            if (UserPrefs.pendingRelaunch(app)) schedule(app, 3_000L);
        }
    }

    private static PendingIntent pendingRelaunch(Context ctx) {
        Intent i = new Intent(ACTION_RELAUNCH).setPackage(ctx.getPackageName());
        i.setClass(ctx, UpdateRelaunchReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(ctx, REQ_ALARM, i, flags);
    }
}
