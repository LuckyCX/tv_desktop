package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 用户通过远程推送保存的壁纸，目录：files/user_wallpapers/。
 * 仅首屏展示（二屏盖住主屏时不显示）。
 */
public final class WallpaperStore {
    private static final String TAG = "TvHome.WallStore";
    private static final String DIR = "user_wallpapers";
    /** 推送/设置变更后通知首屏刷新壁纸。 */
    public static final String ACTION_CHANGED = "com.gathin.tvhome.WALLPAPER_CHANGED";

    private WallpaperStore() {}

    public static void notifyChanged(Context ctx) {
        try {
            Intent i = new Intent(ACTION_CHANGED);
            i.setPackage(ctx.getPackageName());
            ctx.sendBroadcast(i);
        } catch (Exception e) {
            Log.w(TAG, "notifyChanged", e);
        }
    }

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static List<File> list(Context ctx) {
        File[] files = dir(ctx).listFiles((f, name) -> {
            String n = name.toLowerCase(Locale.US);
            return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                    || n.endsWith(".webp") || n.endsWith(".bmp");
        });
        if (files == null || files.length == 0) return Collections.emptyList();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        List<File> out = new ArrayList<>();
        Collections.addAll(out, files);
        return out;
    }

    public static File find(Context ctx, String name) {
        if (name == null || name.isEmpty()) return null;
        File f = new File(dir(ctx), name);
        return f.isFile() ? f : null;
    }

    /** 保存推送图片；重名自动加后缀。返回保存后的文件。 */
    public static File add(Context ctx, String originalName, InputStream body) throws Exception {
        String base = sanitize(originalName);
        // 统一存成 .jpg，便于 TV 端规范化解码
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        base = stem + ".jpg";
        String name = uniqueName(ctx, base);
        File dest = new File(dir(ctx), name);
        File raw = File.createTempFile("wall-raw-", ".img", ctx.getCacheDir());
        try {
            try (OutputStream out = new FileOutputStream(raw)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = body.read(buf)) != -1) out.write(buf, 0, n);
            }
            if (raw.length() <= 0) throw new IllegalStateException("empty image");
            // 规范化为标准 JPEG，修复个别原图在电视上解不开的问题
            boolean normalized = WallpaperBitmaps.normalizeToJpeg(raw, dest, 2560);
            if (!normalized) {
                // 规范化失败则原样拷贝，仍保留文件供列表管理
                try (InputStream in = new java.io.FileInputStream(raw);
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
            }
            if (dest.length() <= 0) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                throw new IllegalStateException("empty image");
            }
            return dest;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }

    public static boolean delete(Context ctx, String name) {
        File f = find(ctx, name);
        if (f == null) return false;
        boolean ok = f.delete();
        if (ok && name.equals(UserPrefs.wallpaperFixed(ctx))) {
            UserPrefs.setWallpaperFixed(ctx, "");
        }
        return ok;
    }

    /** 尝试把已有壁纸重新规范成可解码 JPEG（修复个别图显示不了）。 */
    public static boolean repair(Context ctx, String name) {
        File src = find(ctx, name);
        if (src == null) return false;
        File tmp = new File(dir(ctx), "._repair_" + System.currentTimeMillis() + ".jpg");
        try {
            if (!WallpaperBitmaps.normalizeToJpeg(src, tmp, 2560)) return false;
            if (!src.delete()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            // 尽量保持原文件名（扩展名改 jpg）
            String stem = name;
            int dot = name.lastIndexOf('.');
            if (dot > 0) stem = name.substring(0, dot);
            File dest = new File(dir(ctx), uniqueName(ctx, stem + ".jpg"));
            if (!tmp.renameTo(dest)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            if (name.equals(UserPrefs.wallpaperFixed(ctx))) {
                UserPrefs.setWallpaperFixed(ctx, dest.getName());
            }
            return true;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            Log.w(TAG, "repair", e);
            return false;
        }
    }

    private static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) return "wallpaper.jpg";
        // 先纠正 UTF-8 被当成 Latin-1 的中文文件名
        String n = com.gathin.tvhome.push.PushHttpServer.fixUtf8Mojibake(name.trim());
        n = n.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        // 只去掉文件系统非法字符，保留中文等正常文字
        n = n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "wallpaper.jpg";
        String lower = n.toLowerCase(Locale.US);
        String ext = ".jpg";
        if (lower.endsWith(".jpeg")) ext = ".jpeg";
        else if (lower.endsWith(".png")) ext = ".png";
        else if (lower.endsWith(".webp")) ext = ".webp";
        else if (lower.endsWith(".bmp")) ext = ".bmp";
        else if (lower.endsWith(".jpg")) ext = ".jpg";
        else n = n + ext;
        // 若仍几乎全是下划线（历史乱码），改用可读时间名
        int dot = n.lastIndexOf('.');
        String stem = dot > 0 ? n.substring(0, dot) : n;
        if (stem.replace("_", "").trim().isEmpty()) {
            n = "wallpaper_" + System.currentTimeMillis() + ext;
        }
        return n;
    }

    private static String uniqueName(Context ctx, String base) {
        File d = dir(ctx);
        if (!new File(d, base).exists()) return base;
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : ".jpg";
        for (int i = 2; i < 1000; i++) {
            String cand = stem + "_" + i + ext;
            if (!new File(d, cand).exists()) return cand;
        }
        return stem + "_" + System.currentTimeMillis() + ext;
    }
}
