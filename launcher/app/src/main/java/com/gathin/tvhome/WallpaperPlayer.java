package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首屏壁纸：拉伸铺满；支持固定一张或按间隔轮换。
 * 二屏打开时调用 {@link #setHomeVisible(boolean)} 隐藏，仅首屏展示。
 */
final class WallpaperPlayer {
    private final Context context;
    private final FrameLayout root;
    private final ImageView image;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    private final int height;
    private final List<File> files = new ArrayList<>();
    private long interval;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private boolean homeVisible = true;
    private String displayed = "";

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.context = context.getApplicationContext();
        this.root = root;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        // 拉伸铺满全屏
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(image, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        reloadSources(config);
        if (!files.isEmpty()) showFirst();
        else image.setVisibility(View.GONE);
    }

    /** 重新读取用户壁纸与模式（设置变更后）。 */
    void reloadSources(Config config) {
        files.clear();
        List<File> user = WallpaperStore.list(context);
        if (!user.isEmpty()) {
            files.addAll(user);
        } else if (config != null && config.wallpaperEnabled && config.wallpapers != null) {
            files.addAll(config.wallpapers);
        }
        if (UserPrefs.wallpaperRotate(context)) {
            interval = UserPrefs.wallpaperRotateMinutes(context) * 60_000L;
        } else {
            interval = 0; // 固定模式不轮换
            String fixed = UserPrefs.wallpaperFixed(context);
            File f = WallpaperStore.find(context, fixed);
            if (f != null) {
                files.clear();
                files.add(f);
            } else if (!files.isEmpty() && fixed != null && !fixed.isEmpty()) {
                // 固定名失效时保留列表第一张
                File keep = files.get(0);
                files.clear();
                files.add(keep);
            }
        }
        if (files.isEmpty()) {
            image.setVisibility(View.GONE);
            displayed = "";
        } else if (homeVisible) {
            image.setVisibility(View.VISIBLE);
            showFirst();
        }
    }

    /** 仅首屏可见；二屏盖住时隐藏壁纸。 */
    void setHomeVisible(boolean visible) {
        homeVisible = visible;
        if (!visible || files.isEmpty()) {
            image.setVisibility(View.GONE);
        } else {
            image.setVisibility(View.VISIBLE);
        }
    }

    private void showFirst() {
        if (files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        File first;
        if (!UserPrefs.wallpaperRotate(context)) {
            String fixed = UserPrefs.wallpaperFixed(context);
            first = WallpaperStore.find(context, fixed);
            if (first == null) first = files.get(0);
        } else {
            first = WallpaperRotation.find(files, previous);
            if (first == null) first = WallpaperRotation.pick(files, previous, random);
        }
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                image.setImageBitmap(bitmap);
                displayed = first.getName();
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);
            first = files.isEmpty() ? null : files.get(0);
        }
        image.setVisibility(View.GONE);
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || !homeVisible || files.isEmpty()) return;
        if (!UserPrefs.wallpaperRotate(context) || interval <= 0) {
            // 固定模式：确保显示固定图
            String fixed = UserPrefs.wallpaperFixed(context);
            File f = WallpaperStore.find(context, fixed);
            if (f == null && !files.isEmpty()) f = files.get(0);
            if (f != null && !f.getName().equals(displayed)) {
                File next = f;
                loading = true;
                decoder.execute(() -> {
                    Bitmap bitmap = decode(next);
                    root.post(() -> {
                        loading = false;
                        if (closed || !active || bitmap == null) {
                            if (bitmap != null) bitmap.recycle();
                            return;
                        }
                        image.setImageBitmap(bitmap);
                        displayed = next.getName();
                    });
                });
            }
            return;
        }
        String previous = preferences.getString("image", "");
        File selected = WallpaperRotation.find(files, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(files, previous, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                image.setImageBitmap(bitmap);
                displayed = next.getName();
                preferences.edit().putString("image", displayed)
                        .putLong("changed", System.currentTimeMillis()).apply();
            });
        });
    }

    private Bitmap decode(File file) {
        // 统一走稳妥解码，避免个别 JPEG 在旧逻辑下被拒绝
        return WallpaperBitmaps.decodeForScreen(file.getPath(), width, height);
    }
}
