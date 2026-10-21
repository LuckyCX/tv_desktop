package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/** Extra user choices beyond layout columns (update URL, default-open, push port). */
public final class UserPrefs {
    public static final String KEY_UPDATE_URL = "update_url";
    public static final String KEY_DEFAULT_OPEN_ENABLED = "default_open_enabled";
    public static final String KEY_DEFAULT_OPEN_PKG = "default_open_pkg";
    public static final String KEY_PUSH_PORT = "push_http_port";
    public static final String KEY_PUSH_ENABLED = "push_http_enabled";
    /** 壁纸模式：rotate=轮换，fixed=固定一张。 */
    public static final String KEY_WALLPAPER_MODE = "wallpaper_mode";
    public static final String KEY_WALLPAPER_FIXED = "wallpaper_fixed";
    public static final String KEY_WALLPAPER_ROTATE_MIN = "wallpaper_rotate_min";
    /** 自更新安装中：进程被杀后由 Receiver / Alarm 拉起。 */
    public static final String KEY_PENDING_RELAUNCH = "pending_relaunch_after_update";
    public static final String MODE_ROTATE = "rotate";
    public static final String MODE_FIXED = "fixed";

    private UserPrefs() {}

    public static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    public static String updateUrl(Context ctx) {
        return sp(ctx).getString(KEY_UPDATE_URL, "");
    }

    public static void setUpdateUrl(Context ctx, String url) {
        sp(ctx).edit().putString(KEY_UPDATE_URL, url == null ? "" : url.trim()).apply();
    }

    /** Default-open switch: on by default per product plan. */
    public static boolean defaultOpenEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_DEFAULT_OPEN_ENABLED, true);
    }

    public static void setDefaultOpenEnabled(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_DEFAULT_OPEN_ENABLED, on).apply();
    }

    public static String defaultOpenPkg(Context ctx) {
        return sp(ctx).getString(KEY_DEFAULT_OPEN_PKG, "");
    }

    public static void setDefaultOpenPkg(Context ctx, String pkg) {
        sp(ctx).edit().putString(KEY_DEFAULT_OPEN_PKG, pkg == null ? "" : pkg).apply();
    }

    public static void clearDefaultOpenPkg(Context ctx) {
        sp(ctx).edit().remove(KEY_DEFAULT_OPEN_PKG).apply();
    }

    public static boolean pushEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_PUSH_ENABLED, true);
    }

    public static void setPushEnabled(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_PUSH_ENABLED, on).apply();
    }

    public static int pushPort(Context ctx) {
        return sp(ctx).getInt(KEY_PUSH_PORT, 0);
    }

    public static void setPushPort(Context ctx, int port) {
        sp(ctx).edit().putInt(KEY_PUSH_PORT, port).apply();
    }

    public static boolean wallpaperRotate(Context ctx) {
        return MODE_ROTATE.equals(sp(ctx).getString(KEY_WALLPAPER_MODE, MODE_ROTATE));
    }

    public static void setWallpaperMode(Context ctx, String mode) {
        sp(ctx).edit().putString(KEY_WALLPAPER_MODE,
                MODE_FIXED.equals(mode) ? MODE_FIXED : MODE_ROTATE).apply();
    }

    public static String wallpaperFixed(Context ctx) {
        return sp(ctx).getString(KEY_WALLPAPER_FIXED, "");
    }

    public static void setWallpaperFixed(Context ctx, String name) {
        sp(ctx).edit().putString(KEY_WALLPAPER_FIXED, name == null ? "" : name).apply();
    }

    /** 轮换间隔（分钟），默认 15。 */
    public static int wallpaperRotateMinutes(Context ctx) {
        return sp(ctx).getInt(KEY_WALLPAPER_ROTATE_MIN, 15);
    }

    public static void setWallpaperRotateMinutes(Context ctx, int minutes) {
        sp(ctx).edit().putInt(KEY_WALLPAPER_ROTATE_MIN, Math.max(1, minutes)).apply();
    }

    public static boolean pendingRelaunch(Context ctx) {
        return sp(ctx).getBoolean(KEY_PENDING_RELAUNCH, false);
    }

    public static void setPendingRelaunch(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_PENDING_RELAUNCH, on).apply();
    }
}
