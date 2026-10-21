package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 壁纸管理页：左侧菜单列表，右侧预览；选中后可删除或设为壁纸。
 */
public final class WallpaperManageActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ListView list;
    private ImageView preview;
    private TextView previewHint;
    private TextView modeHint;
    private TextView empty;
    private final List<File> files = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private int selected = 0;
    private Bitmap previewBmp;
    private String previewName = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_wallpaper_manage);
        list = findViewById(R.id.wallpaperList);
        preview = findViewById(R.id.wallpaperPreview);
        previewHint = findViewById(R.id.wallpaperPreviewHint);
        modeHint = findViewById(R.id.wallpaperModeHint);
        empty = findViewById(R.id.wallpaperEmpty);
        // 拉伸铺满预览区，与桌面壁纸一致
        preview.setScaleType(ImageView.ScaleType.FIT_XY);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_activated_1, new ArrayList<>());
        list.setAdapter(adapter);
        list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selected = position;
                showPreview(position);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        // 遥控器 OK / 点击：弹出删除 / 设为壁纸
        list.setOnItemClickListener((parent, view, position, id) -> {
            selected = position;
            showPreview(position);
            showItemActions(position);
        });

        findViewById(R.id.wallpaperRotateBtn).setOnClickListener(v -> pickWallpaperMode());
        reload();
        list.requestFocus();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (previewBmp != null && !previewBmp.isRecycled()) previewBmp.recycle();
        super.onDestroy();
    }

    private void reload() {
        files.clear();
        files.addAll(WallpaperStore.list(this));
        List<String> labels = new ArrayList<>();
        String fixed = UserPrefs.wallpaperFixed(this);
        for (File f : files) {
            String mark = f.getName().equals(fixed) && !UserPrefs.wallpaperRotate(this) ? "  ✓" : "";
            labels.add(displayName(f.getName()) + mark);
        }
        adapter.clear();
        adapter.addAll(labels);
        adapter.notifyDataSetChanged();

        boolean has = !files.isEmpty();
        list.setVisibility(has ? View.VISIBLE : View.GONE);
        empty.setVisibility(has ? View.GONE : View.VISIBLE);
        refreshModeHint();

        if (!has) {
            preview.setImageDrawable(null);
            previewHint.setVisibility(View.VISIBLE);
            previewHint.setText(R.string.wallpaper_empty);
            return;
        }
        if (selected >= files.size()) selected = 0;
        list.setSelection(selected);
        list.setItemChecked(selected, true);
        showPreview(selected);
    }

    private void refreshModeHint() {
        if (UserPrefs.wallpaperRotate(this)) {
            modeHint.setText(getString(R.string.wallpaper_mode_rotate,
                    UserPrefs.wallpaperRotateMinutes(this)));
        } else {
            String fixed = UserPrefs.wallpaperFixed(this);
            if (fixed == null || fixed.isEmpty()) {
                modeHint.setText(R.string.wallpaper_mode_fixed_none);
            } else {
                modeHint.setText(getString(R.string.wallpaper_mode_fixed, displayName(fixed)));
            }
        }
    }

    /** 下划线乱码名显示为「壁纸 N」。 */
    private String displayName(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        if (stem.replace("_", "").trim().isEmpty()) {
            return getString(R.string.wallpaper_unnamed, Math.abs(name.hashCode() % 1000));
        }
        return name;
    }

    private void showPreview(int position) {
        if (position < 0 || position >= files.size()) return;
        File f = files.get(position);
        if (f.getName().equals(previewName) && previewBmp != null) return;
        previewHint.setVisibility(View.VISIBLE);
        previewHint.setText(R.string.wallpaper_preview_loading);
        final String name = f.getName();
        final String path = f.getAbsolutePath();
        io.execute(() -> {
            Bitmap bmp = WallpaperBitmaps.decodeForScreen(path,
                    preview.getWidth() > 0 ? preview.getWidth() : 1280,
                    preview.getHeight() > 0 ? preview.getHeight() : 720);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                if (previewBmp != null && previewBmp != bmp && !previewBmp.isRecycled()) {
                    previewBmp.recycle();
                }
                previewBmp = bmp;
                previewName = name;
                if (bmp == null) {
                    preview.setImageDrawable(null);
                    previewHint.setVisibility(View.VISIBLE);
                    previewHint.setText(R.string.wallpaper_preview_fail);
                } else {
                    preview.setImageBitmap(bmp);
                    previewHint.setVisibility(View.GONE);
                }
            });
        });
    }

    private void showItemActions(int position) {
        if (position < 0 || position >= files.size()) return;
        File f = files.get(position);
        String title = displayName(f.getName());
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(new CharSequence[]{
                        getString(R.string.wallpaper_set_as),
                        getString(R.string.wallpaper_repair),
                        getString(R.string.wallpaper_delete)
                }, (d, which) -> {
                    if (which == 0) {
                        UserPrefs.setWallpaperMode(this, UserPrefs.MODE_FIXED);
                        UserPrefs.setWallpaperFixed(this, f.getName());
                        markChanged();
                        Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                        reload();
                    } else if (which == 1) {
                        boolean ok = WallpaperStore.repair(this, f.getName());
                        Toast.makeText(this, ok ? R.string.wallpaper_repair_ok : R.string.wallpaper_preview_fail,
                                Toast.LENGTH_SHORT).show();
                        if (ok) {
                            previewName = "";
                            markChanged();
                            reload();
                        }
                    } else {
                        confirmDelete(f);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_delete)
                .setMessage(displayName(f.getName()))
                .setPositiveButton(R.string.wallpaper_delete, (d, w) -> {
                    if (WallpaperStore.delete(this, f.getName())) {
                        Toast.makeText(this, R.string.wallpaper_deleted, Toast.LENGTH_SHORT).show();
                        previewName = "";
                        markChanged();
                        reload();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 壁纸模式：固定一张（当前选中）或按间隔轮换。
     * 第一项为固定，后面为轮换时长。
     */
    private void pickWallpaperMode() {
        final int[] mins = {5, 10, 15, 30, 60, 120};
        CharSequence[] labels = new CharSequence[1 + mins.length];
        labels[0] = getString(R.string.wallpaper_fixed_current);
        int checked = 0;
        boolean rotate = UserPrefs.wallpaperRotate(this);
        int cur = UserPrefs.wallpaperRotateMinutes(this);
        for (int i = 0; i < mins.length; i++) {
            labels[1 + i] = getString(R.string.wallpaper_mode_rotate_label) + " · " + mins[i] + " 分钟";
            if (rotate && mins[i] == cur) checked = 1 + i;
        }
        if (!rotate) checked = 0;

        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_mode_pick_title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    if (which == 0) {
                        // 固定当前左侧选中的壁纸
                        if (files.isEmpty() || selected < 0 || selected >= files.size()) {
                            Toast.makeText(this, R.string.wallpaper_fixed_need_select, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        File f = files.get(selected);
                        UserPrefs.setWallpaperMode(this, UserPrefs.MODE_FIXED);
                        UserPrefs.setWallpaperFixed(this, f.getName());
                        markChanged();
                        refreshModeHint();
                        reload();
                        Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    } else {
                        int minutes = mins[which - 1];
                        UserPrefs.setWallpaperMode(this, UserPrefs.MODE_ROTATE);
                        UserPrefs.setWallpaperRotateMinutes(this, minutes);
                        markChanged();
                        refreshModeHint();
                        reload();
                        Toast.makeText(this, getString(R.string.wallpaper_mode_rotate, minutes),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void markChanged() {
        Intent data = new Intent();
        data.putExtra("wallpaper_only", true);
        setResult(RESULT_OK, data);
        WallpaperStore.notifyChanged(this);
    }
}
