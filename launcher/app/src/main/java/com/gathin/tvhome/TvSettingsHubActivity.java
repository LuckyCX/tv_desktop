package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.gathin.tvhome.push.PushHttpServer;
import com.gathin.tvhome.push.PushHistoryActivity;
import com.gathin.tvhome.push.PushService;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Settings center: system / switch home / update URL / default-open / backup / wallpaper / push. */
public final class TvSettingsHubActivity extends Activity {
    private EditText updateUrl;
    private TextView updateProgress;
    private TextView defaultOpenToggle;
    private TextView pushAddress;
    private TextView wallpaperState;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_tv_settings_hub);
        PushService.ensureStarted(this);

        updateUrl = findViewById(R.id.hubUpdateUrl);
        updateProgress = findViewById(R.id.hubUpdateProgress);
        defaultOpenToggle = findViewById(R.id.hubDefaultOpenToggle);
        pushAddress = findViewById(R.id.hubPushAddress);
        wallpaperState = findViewById(R.id.hubWallpaperState);
        updateUrl.setText(UserPrefs.updateUrl(this));

        findViewById(R.id.hubSystemSettings).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));
        findViewById(R.id.hubSwitchHome).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_vidda_hint, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.hubLauncherSettings).setOnClickListener(v ->
                startActivity(new Intent(this, LauncherSettingsActivity.class)));

        // 点「更新」时自动保存 URL，不再单独保存/清空
        findViewById(R.id.hubUpdateNow).setOnClickListener(v -> startUpdate());

        // 一行完成：点击选择默认应用（含关闭）
        defaultOpenToggle.setOnClickListener(v -> pickDefaultOpenApp());

        findViewById(R.id.hubBackupCreate).setOnClickListener(v -> {
            try {
                String id = LocalBackup.create(this);
                Toast.makeText(this, getString(R.string.backup_created, id), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        findViewById(R.id.hubBackupList).setOnClickListener(v -> showBackupList());
        findViewById(R.id.hubPushHistory).setOnClickListener(v ->
                startActivity(new Intent(this, PushHistoryActivity.class)));
        findViewById(R.id.hubWallpaperManage).setOnClickListener(v ->
                startActivityForResult(new Intent(this, WallpaperManageActivity.class), 71));

        refreshDefaultOpen();
        refreshPushAddress();
        refreshWallpaperState();
        // PushService binds asynchronously; refresh IP:port a moment later.
        pushAddress.postDelayed(this::refreshPushAddress, 800);
        findViewById(R.id.hubSystemSettings).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPushAddress();
        refreshWallpaperState();
    }

    private void refreshWallpaperState() {
        if (wallpaperState == null) return;
        int count = WallpaperStore.list(this).size();
        String mode;
        if (UserPrefs.wallpaperRotate(this)) {
            mode = getString(R.string.wallpaper_mode_rotate, UserPrefs.wallpaperRotateMinutes(this));
        } else {
            String fixed = UserPrefs.wallpaperFixed(this);
            mode = (fixed == null || fixed.isEmpty())
                    ? getString(R.string.wallpaper_mode_fixed_none)
                    : getString(R.string.wallpaper_mode_fixed, fixed);
        }
        wallpaperState.setText(mode + " · " + count + " 张");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 71 && resultCode == RESULT_OK) {
            // 壁纸页有改动：回传给桌面刷新，并更新本页状态
            setResult(RESULT_OK, data != null ? data : new Intent().putExtra("wallpaper_only", true));
            refreshWallpaperState();
        }
    }

    private void refreshDefaultOpen() {
        boolean on = UserPrefs.defaultOpenEnabled(this);
        String pkg = UserPrefs.defaultOpenPkg(this);
        String label = pkg;
        if (!TextUtils.isEmpty(pkg)) {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                label = String.valueOf(getPackageManager().getApplicationLabel(ai));
            } catch (Exception e) {
                label = pkg + " (" + getString(R.string.default_open_missing) + ")";
            }
        } else {
            label = getString(R.string.default_open_none);
        }
        defaultOpenToggle.setText(getString(R.string.default_open_state,
                getString(on ? R.string.on : R.string.off), label));
        TextView sub = findViewById(R.id.hubDefaultOpenPick);
        if (sub != null) {
            sub.setText(on
                    ? getString(R.string.default_open_pick)
                    : getString(R.string.off));
        }
    }

    private void refreshPushAddress() {
        PushHttpServer server = PushHttpServer.get(this);
        int port = server.isRunning() ? server.getPort() : UserPrefs.pushPort(this);
        String ip = LanAddress.ipv4(this);
        if (port <= 0) {
            pushAddress.setText(getString(R.string.push_address_waiting));
        } else {
            // 手机浏览器直接打开此地址即可推送 APK
            pushAddress.setText("http://" + ip + ":" + port);
        }
    }

    private void startUpdate() {
        String url = updateUrl.getText().toString().trim();
        if (url.isEmpty()) url = UserPrefs.updateUrl(this);
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.update_url_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        UserPrefs.setUpdateUrl(this, url);
        updateUrl.setText(url);
        updateProgress.setVisibility(View.VISIBLE);
        updateProgress.setText(getString(R.string.update_progress, 0, "…"));
        final String finalUrl = url;
        ApkInstaller.downloadAndInstall(this, finalUrl, new ApkInstaller.Progress() {
            @Override
            public void onProgress(int percent, String message) {
                updateProgress.setText(getString(R.string.update_progress, percent, message));
            }

            @Override
            public void onDone(boolean ok, String message) {
                updateProgress.setText(ok
                        ? getString(R.string.update_ok)
                        : getString(R.string.update_fail, message));
                if (!ok) {
                    new AlertDialog.Builder(TvSettingsHubActivity.this)
                            .setTitle(R.string.update_now)
                            .setMessage(getString(R.string.update_fail, message))
                            .setPositiveButton(R.string.update_retry, (d, w) -> startUpdate())
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                } else {
                    Toast.makeText(TvSettingsHubActivity.this, R.string.update_ok, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void pickDefaultOpenApp() {
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<String> labels = new ArrayList<>();
        List<String> pkgs = new ArrayList<>();
        labels.add(getString(R.string.default_open_none));
        pkgs.add("");
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            pkgs.add(pkg);
            labels.add(String.valueOf(ri.loadLabel(pm)));
        }
        String current = UserPrefs.defaultOpenPkg(this);
        int checked = 0;
        for (int i = 0; i < pkgs.size(); i++) {
            if (pkgs.get(i).equals(current)) { checked = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.default_open_pick)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, which) -> {
                    d.dismiss();
                    String pkg = pkgs.get(which);
                    if (pkg.isEmpty()) {
                        UserPrefs.clearDefaultOpenPkg(this);
                        UserPrefs.setDefaultOpenEnabled(this, false);
                    } else {
                        UserPrefs.setDefaultOpenPkg(this, pkg);
                        UserPrefs.setDefaultOpenEnabled(this, true);
                    }
                    refreshDefaultOpen();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showBackupList() {
        List<LocalBackup.Entry> entries = LocalBackup.list(this);
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.backup_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            labels[i] = entries.get(i).id + "  ·  " + fmt.format(new Date(entries.get(i).timeMs));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_list)
                .setItems(labels, (d, which) -> {
                    LocalBackup.Entry e = entries.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle(e.id)
                            .setItems(new CharSequence[]{
                                    getString(R.string.backup_restore),
                                    getString(R.string.backup_delete)
                            }, (d2, action) -> {
                                if (action == 0) {
                                    try {
                                        LocalBackup.restore(this, e.id);
                                        Toast.makeText(this, R.string.backup_restored, Toast.LENGTH_SHORT).show();
                                        setResult(RESULT_OK);
                                        refreshDefaultOpen();
                                    } catch (Exception ex) {
                                        Toast.makeText(this, ex.getMessage(), Toast.LENGTH_LONG).show();
                                    }
                                } else {
                                    LocalBackup.delete(this, e.id);
                                    Toast.makeText(this, R.string.backup_deleted, Toast.LENGTH_SHORT).show();
                                }
                            })
                            .show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
