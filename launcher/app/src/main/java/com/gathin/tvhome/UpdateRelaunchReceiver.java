package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 自更新完成后自动启动桌面：
 * - {@link Intent#ACTION_MY_PACKAGE_REPLACED}：系统替换本包后投递（最可靠）
 * - {@link UpdateRelaunch#ACTION_RELAUNCH}：安装前提前 Alarm 兜底
 */
public final class UpdateRelaunchReceiver extends BroadcastReceiver {
    private static final String TAG = "TvHome.RelaunchRx";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Log.i(TAG, "onReceive " + action);
        boolean replaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        boolean alarm = UpdateRelaunch.ACTION_RELAUNCH.equals(action);
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        // 开机只在「更新后尚未成功拉起」时补一次，避免每次开机抢默认桌面
        if (boot && !UserPrefs.pendingRelaunch(context)) return;
        if (!replaced && !alarm && !boot) return;

        // 替换瞬间 PMS 可能尚未完全就绪，稍延迟再启动
        final PendingResult pending = goAsync();
        long delay = replaced ? 800L : (boot ? 1_500L : 200L);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                UpdateRelaunch.launchHome(context.getApplicationContext());
            } finally {
                try {
                    pending.finish();
                } catch (Exception ignored) {
                }
            }
        }, delay);
    }
}
