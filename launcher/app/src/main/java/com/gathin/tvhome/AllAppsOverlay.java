package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV 风格「全部应用」二屏：
 * - 图标框内只显示 logo（不显示名称）
 * - 焦点仅放大（无选中框）
 * - 第一行按上键关闭并回到 Dock 原焦点
 * - 「已隐藏」用 logo 网格恢复，不用文字列表
 */
final class AllAppsOverlay {
    static final int COLUMNS = 6;
    private static final float FOCUS_SCALE = 1.14f;

    interface Host {
        List<String> configDock();
        int dockSlots();
        void onDockChanged();
        Map<String, String> launchActions();
        /** 二屏显示/隐藏时通知宿主（用于壁纸仅首屏展示）。 */
        void onAllAppsVisibility(boolean visible);
        /** 二屏关闭后，焦点应回到 Dock 原选中图标。 */
        void onAllAppsClosed();
    }

    private final Activity activity;
    private final Host host;
    private final FrameLayout panel;
    private final RecyclerView grid;
    private final Adapter adapter;
    private final float density;
    private boolean shown;
    private boolean showingHidden;

    AllAppsOverlay(Activity activity, FrameLayout root, Host host) {
        this.activity = activity;
        this.host = host;
        density = activity.getResources().getDisplayMetrics().density;

        panel = new FrameLayout(activity);
        panel.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel.setBackgroundColor(0xF00A1628);
        panel.setClickable(true);
        panel.setFocusable(false);
        panel.setVisibility(View.GONE);
        panel.setClipChildren(false);

        grid = new RecyclerView(activity);
        int padH = Math.round(48 * density);
        int padV = Math.round(40 * density);
        grid.setPadding(padH, padV, padH, padV);
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setLayoutManager(new GridLayoutManager(activity, COLUMNS));
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setItemAnimator(null);
        panel.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(panel);
    }

    boolean isShown() {
        return shown;
    }

    void show() {
        showingHidden = false;
        adapter.reload();
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.setScaleX(0.94f);
        panel.setScaleY(0.94f);
        panel.animate().cancel();
        panel.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(260)
                .setInterpolator(new OvershootInterpolator(0.9f))
                .start();
        shown = true;
        host.onAllAppsVisibility(true);
        grid.post(() -> focusPosition(0));
    }

    void hide() {
        if (!shown) return;
        shown = false;
        showingHidden = false;
        host.onAllAppsVisibility(false);
        panel.animate().cancel();
        panel.animate()
                .alpha(0f).scaleX(0.94f).scaleY(0.94f)
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    panel.setVisibility(View.GONE);
                    panel.setAlpha(1f);
                    panel.setScaleX(1f);
                    panel.setScaleY(1f);
                    host.onAllAppsClosed();
                })
                .start();
    }

    void refreshIfShown() {
        if (shown) adapter.reload();
    }

    private void focusPosition(int position) {
        if (position < 0 || position >= adapter.getItemCount()) return;
        RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(position);
        if (h != null) {
            View card = h.itemView.findViewById(R.id.card);
            if (card != null) card.requestFocus();
            return;
        }
        grid.scrollToPosition(position);
        grid.post(() -> {
            RecyclerView.ViewHolder h2 = grid.findViewHolderForAdapterPosition(position);
            if (h2 != null) {
                View card = h2.itemView.findViewById(R.id.card);
                if (card != null) card.requestFocus();
            }
        });
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        private final List<AppAdapter.Entry> items = new ArrayList<>();
        private Set<String> leanback = new HashSet<>();

        void reload() {
            if (showingHidden) {
                reloadHidden();
                return;
            }
            PackageManager pm = activity.getPackageManager();
            Set<String> hidden = new HashSet<>(Config.load(activity).hiddenApps);
            hidden.addAll(DockStore.userHidden(activity));

            Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(probe, 0));
            Set<String> tvPackages = new HashSet<>();
            for (ResolveInfo ri : found) {
                if (ri.activityInfo != null) tvPackages.add(ri.activityInfo.packageName);
            }
            leanback = tvPackages;
            Set<String> seenPkg = new HashSet<>(tvPackages);
            for (ResolveInfo ri : pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                if (ri.activityInfo != null && seenPkg.add(ri.activityInfo.packageName)) {
                    found.add(ri);
                }
            }

            List<AppAdapter.Entry> fresh = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Map<String, String> labels = Config.load(activity).appLabels;
            for (ResolveInfo ri : found) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(activity.getPackageName()) || !ri.activityInfo.exported) continue;
                if (hidden.contains(pkg)) continue;
                try {
                    pm.getApplicationInfo(pkg, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }
                ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
                String label = Cards.cleanLabel(
                        labels.containsKey(pkg) ? labels.get(pkg) : String.valueOf(ri.loadLabel(pm)), pkg);
                if (!seen.add(pkg)) continue;
                Drawable icon = ri.loadIcon(pm);
                // 图标框只放 logo；横幅易带字导致「两个名称」
                fresh.add(new AppAdapter.Entry(label, cn, null, icon));
            }
            Collections.sort(fresh, (a, b) -> a.label.compareToIgnoreCase(b.label));
            fresh.add(new AppAdapter.Entry(AppAdapter.Entry.HIDDEN,
                    activity.getString(R.string.hidden_apps_entry),
                    new ComponentName(activity, "hidden-apps-entry"),
                    null, activity.getDrawable(R.drawable.ic_hidden), null, 0));
            items.clear();
            items.addAll(fresh);
            notifyDataSetChanged();
        }

        /** 已隐藏应用：用 app logo 网格展示，点击恢复。 */
        void reloadHidden() {
            PackageManager pm = activity.getPackageManager();
            List<String> pkgs = DockStore.installedOnly(activity,
                    new ArrayList<>(DockStore.userHidden(activity)));
            List<AppAdapter.Entry> fresh = new ArrayList<>();
            for (String pkg : pkgs) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    String label = String.valueOf(pm.getApplicationLabel(ai));
                    Drawable icon = pm.getApplicationIcon(ai);
                    Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
                    if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
                    ComponentName cn = launch != null && launch.getComponent() != null
                            ? launch.getComponent()
                            : new ComponentName(pkg, pkg + ".Main");
                    fresh.add(new AppAdapter.Entry(label, cn, null, icon));
                } catch (Exception ignored) {
                }
            }
            Collections.sort(fresh, (a, b) -> a.label.compareToIgnoreCase(b.label));
            items.clear();
            items.addAll(fresh);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
            int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
            int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
            int w = AppAdapter.tileWidthPx(inner, COLUMNS, density);
            int h = AppAdapter.tileHeightPx(w);
            View card = v.findViewById(R.id.card);
            ViewGroup.LayoutParams cardLp = card.getLayoutParams();
            cardLp.width = w;
            cardLp.height = h;
            card.setLayoutParams(cardLp);
            View name = v.findViewById(R.id.name);
            ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
            nameLp.width = w;
            nameLp.topMargin = h + Math.round(8 * density);
            name.setLayoutParams(nameLp);
            // 放大时不被裁切
            ((ViewGroup) v).setClipChildren(false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            AppAdapter.Entry e = items.get(position);
            h.name.setText(e.label);
            h.name.setVisibility(View.VISIBLE);
            h.name.setAlpha(h.card.isFocused() ? 1f : 0f);
            // 图标框内只显示 logo，不显示名称
            h.label.setVisibility(View.GONE);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            // 隐藏入口 logo 稍大一点居中
            ViewGroup.LayoutParams ilp = h.icon.getLayoutParams();
            int iconSize = Math.round((e.kind == AppAdapter.Entry.HIDDEN ? 40 : 48) * density);
            ilp.width = iconSize;
            ilp.height = iconSize;
            h.icon.setLayoutParams(ilp);
            h.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            // 去掉底部文字占位，logo 垂直居中
            if (h.icon.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) h.icon.getLayoutParams();
                flp.gravity = android.view.Gravity.CENTER;
                flp.bottomMargin = 0;
                h.icon.setLayoutParams(flp);
            }

            h.card.setScaleX(h.card.isFocused() ? FOCUS_SCALE : 1f);
            h.card.setScaleY(h.card.isFocused() ? FOCUS_SCALE : 1f);

            h.card.setOnClickListener(v -> onActivate(e));
            if (e.kind == AppAdapter.Entry.APP || e.kind == AppAdapter.Entry.CARD) {
                h.card.setOnLongClickListener(v -> {
                    if (showingHidden) {
                        showHiddenActions(e);
                        return true;
                    }
                    showActions(e);
                    return true;
                });
            } else {
                h.card.setOnLongClickListener(null);
            }
            h.card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (showingHidden) {
                        showingHidden = false;
                        reload();
                        focusPosition(Math.max(0, getItemCount() - 1));
                    } else {
                        hide();
                    }
                    return true;
                }
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && pos < COLUMNS) {
                    // 第一行上键：隐藏模式回全部应用；否则关闭二屏回 Dock
                    if (showingHidden) {
                        showingHidden = false;
                        reload();
                        focusPosition(0);
                    } else {
                        hide();
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int target = neighbor(pos, keyCode);
                    if (target >= 0) {
                        focusPosition(target);
                        return true;
                    }
                    // 左右到边吞掉，避免焦点逃出
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        return true;
                    }
                }
                return false;
            });
        }

        private int neighbor(int pos, int keyCode) {
            int n = getItemCount();
            if (n == 0) return -1;
            int row = pos / COLUMNS;
            int col = pos % COLUMNS;
            int lastRow = (n - 1) / COLUMNS;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return col > 0 ? pos - 1 : -1;
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    int next = pos + 1;
                    return next < n && next / COLUMNS == row ? next : -1;
                }
                case KeyEvent.KEYCODE_DPAD_UP:
                    return row > 0 ? pos - COLUMNS : -1;
                case KeyEvent.KEYCODE_DPAD_DOWN: {
                    if (row >= lastRow) return -1;
                    int down = pos + COLUMNS;
                    return Math.min(down, n - 1);
                }
                default:
                    return -1;
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private void onActivate(AppAdapter.Entry e) {
            if (e.kind == AppAdapter.Entry.HIDDEN) {
                List<String> pkgs = DockStore.installedOnly(activity,
                        new ArrayList<>(DockStore.userHidden(activity)));
                if (pkgs.isEmpty()) {
                    Toast.makeText(activity, R.string.hidden_apps_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                showingHidden = true;
                reloadHidden();
                focusPosition(0);
                return;
            }
            // 隐藏区短按：直接打开应用，不自动移出；移出仅在长按菜单
            Intent i = new Intent(Intent.ACTION_MAIN)
                    .setComponent(e.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (leanback.contains(e.pkg)) i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            String action = host.launchActions().get(e.pkg);
            if (action != null) {
                Intent byAction = new Intent(action).setPackage(e.pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                if (activity.getPackageManager().resolveActivity(byAction, 0) != null) i = byAction;
            }
            try {
                activity.startActivity(i);
                hide();
            } catch (Exception ex) {
                Toast.makeText(activity, activity.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
            }
        }

        /** 已隐藏列表长按：移出到全部应用，或移出并上 Dock。 */
        private void showHiddenActions(AppAdapter.Entry e) {
            CharSequence[] items = new CharSequence[]{
                    activity.getString(R.string.hidden_unhide_to_pool),
                    activity.getString(R.string.hidden_unhide_to_dock)
            };
            final int fromPos = indexOfPkg(e.pkg);
            new AlertDialog.Builder(activity)
                    .setTitle(e.label)
                    .setItems(items, (d, which) -> {
                        if (which == 0) {
                            DockStore.unhide(activity, e.pkg);
                            host.onDockChanged();
                            Toast.makeText(activity, R.string.hidden_unhidden, Toast.LENGTH_SHORT).show();
                        } else {
                            List<String> dockNow = DockStore.effectiveDock(activity, host.configDock());
                            DockStore.pinToDock(activity, e.pkg, dockNow, host.dockSlots());
                            host.onDockChanged();
                            Toast.makeText(activity, R.string.dock_pin, Toast.LENGTH_SHORT).show();
                        }
                        // 仍留在隐藏区（若还有），焦点不逃回首屏
                        reloadHidden();
                        if (getItemCount() == 0) {
                            showingHidden = false;
                            reload();
                            focusPosition(0);
                        } else {
                            keepFocusOnOverlay(fromPos, true);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void showActions(AppAdapter.Entry e) {
            List<String> labels = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            List<String> dock = DockStore.effectiveDock(activity, host.configDock());
            boolean inDock = dock.contains(e.pkg);
            // 记住当前下标：隐藏后该项消失，焦点应留在二屏邻近位置，不能逃回首屏播放源
            final int fromPos = indexOfPkg(e.pkg);
            if (inDock) {
                labels.add(activity.getString(R.string.dock_unpin));
                actions.add(() -> {
                    DockStore.unpinFromDock(activity, e.pkg, dock);
                    host.onDockChanged();
                    reload();
                    keepFocusOnOverlay(fromPos, false);
                });
            } else {
                labels.add(activity.getString(R.string.dock_pin));
                actions.add(() -> {
                    DockStore.pinToDock(activity, e.pkg, dock, host.dockSlots());
                    host.onDockChanged();
                    reload();
                    keepFocusOnOverlay(fromPos, false);
                });
            }
            labels.add(activity.getString(R.string.dock_hide));
            actions.add(() -> {
                DockStore.hide(activity, e.pkg, dock);
                host.onDockChanged();
                reload();
                keepFocusOnOverlay(fromPos, true);
            });
            labels.add(activity.getString(R.string.library_app_info));
            actions.add(() -> {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + e.pkg));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    activity.startActivity(i);
                } catch (ActivityNotFoundException ignored) {
                }
            });
            new AlertDialog.Builder(activity)
                    .setTitle(e.label)
                    .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                    .setNegativeButton(android.R.string.cancel, null)
                    .setOnDismissListener(d -> {
                        // 取消菜单时也把焦点夺回二屏，防止落到播放源
                        if (shown) keepFocusOnOverlay(Math.max(0, fromPos), false);
                    })
                    .show();
        }

        private int indexOfPkg(String pkg) {
            if (pkg == null) return 0;
            for (int i = 0; i < items.size(); i++) {
                if (pkg.equals(items.get(i).pkg)) return i;
            }
            return 0;
        }

        /** 菜单关闭 / 隐藏后：焦点必须留在二屏网格。 */
        private void keepFocusOnOverlay(int preferred, boolean itemRemoved) {
            if (!shown) return;
            int n = getItemCount();
            if (n <= 0) return;
            int target = preferred;
            if (itemRemoved) {
                // 该项已移除：落在原位置（后面的前移）或末尾
                target = Math.min(preferred, n - 1);
            } else {
                target = Math.max(0, Math.min(preferred, n - 1));
            }
            final int pos = target;
            // 弹窗关闭焦点迁移是异步的，连续 post 两次确保压过系统默认落点
            grid.post(() -> focusPosition(pos));
            grid.postDelayed(() -> {
                if (shown) focusPosition(pos);
            }, 80);
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            AppAdapter.cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);
            card.setOnFocusChangeListener((view, focused) -> {
                view.animate().cancel();
                name.animate().cancel();
                float s = focused ? FOCUS_SCALE : 1f;
                view.animate()
                        .scaleX(s).scaleY(s)
                        .translationZ(focused ? 10f * density : 0f)
                        .setDuration(focused ? 200 : 140)
                        .setInterpolator(focused ? new OvershootInterpolator(1.1f) : new DecelerateInterpolator())
                        .start();
                name.animate().alpha(focused ? 1f : 0f).setDuration(140).start();
            });
        }
    }
}
