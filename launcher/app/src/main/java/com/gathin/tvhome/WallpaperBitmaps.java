package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 壁纸解码 / 规范化：部分 JPEG（特殊编码、损坏分片）在 TV 上 RGB_565 直解会失败，
 * 这里用更稳妥的采样与 ARGB 回退，入库时尽量转成标准 JPEG。
 */
final class WallpaperBitmaps {
    private static final String TAG = "TvHome.WallBmp";

    private WallpaperBitmaps() {}

    /** 按屏幕尺寸采样解码；失败返回 null。 */
    static Bitmap decodeForScreen(String path, int screenW, int screenH) {
        if (path == null) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int w = bounds.outWidth;
            int h = bounds.outHeight;
            if (w <= 0 || h <= 0) {
                // 再试流式读（个别文件 decodeFile 边界失败）
                try (FileInputStream in = new FileInputStream(path)) {
                    BitmapFactory.decodeStream(in, null, bounds);
                    w = bounds.outWidth;
                    h = bounds.outHeight;
                }
            }
            if (w <= 0 || h <= 0) return null;

            int sample = sampleForFit(w, h, Math.max(1, screenW), Math.max(1, screenH));
            Bitmap bmp = decodeWithSample(path, sample, Bitmap.Config.RGB_565);
            if (bmp == null) bmp = decodeWithSample(path, sample, Bitmap.Config.ARGB_8888);
            if (bmp == null && sample > 1) {
                bmp = decodeWithSample(path, sample * 2, Bitmap.Config.ARGB_8888);
            }
            if (bmp == null) {
                // 最后一搏：强制更大采样
                for (int s = 2; s <= 16 && bmp == null; s *= 2) {
                    bmp = decodeWithSample(path, s, Bitmap.Config.ARGB_8888);
                }
            }
            return bmp;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "decode fail " + path, e);
            return null;
        }
    }

    /** FIT_XY：控制像素总量，不必「铺满两边」才降采样。 */
    static int sampleForFit(int w, int h, int screenW, int screenH) {
        int sample = 1;
        // 目标不超过约 2× 屏像素，且不超过 WallpaperSize.MAX_PIXELS
        long maxPixels = Math.min(WallpaperSize.MAX_PIXELS,
                Math.max(1L, (long) screenW * screenH * 2L));
        while ((long) (w / sample) * (h / sample) > maxPixels) {
            sample *= 2;
            if (sample >= 64) break;
        }
        return sample;
    }

    private static Bitmap decodeWithSample(String path, int sample, Bitmap.Config config) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = Math.max(1, sample);
            opts.inPreferredConfig = config;
            opts.inDither = true;
            Bitmap bmp = BitmapFactory.decodeFile(path, opts);
            if (bmp != null) return bmp;
            try (FileInputStream in = new FileInputStream(path)) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * 将推送图片规范成 TV 可稳妥解码的 JPEG。
     * 成功返回 true（dest 已写入）；失败则调用方应保留原始拷贝。
     */
    static boolean normalizeToJpeg(File src, File dest, int maxEdge) {
        Bitmap bmp = decodeForScreen(src.getAbsolutePath(), maxEdge, maxEdge);
        if (bmp == null) return false;
        try (FileOutputStream out = new FileOutputStream(dest)) {
            boolean ok = bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.flush();
            return ok && dest.length() > 0;
        } catch (Exception e) {
            Log.w(TAG, "normalize", e);
            return false;
        } finally {
            if (!bmp.isRecycled()) bmp.recycle();
        }
    }
}
