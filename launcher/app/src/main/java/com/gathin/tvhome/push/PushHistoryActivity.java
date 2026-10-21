package com.gathin.tvhome.push;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.gathin.tvhome.ApkInstaller;
import com.gathin.tvhome.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** On-TV list of pushed APKs: install / delete. */
public final class PushHistoryActivity extends Activity {
    private LinearLayout rows;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF101419);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (48 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText(R.string.push_history);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(34);
        root.addView(title);
        status = new TextView(this);
        status.setTextColor(0xFFACB8C1);
        status.setTextSize(14);
        status.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, 0);
        root.addView(status);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        root.addView(rows);
        scroll.addView(root);
        setContentView(scroll);
        refresh();
    }

    private void refresh() {
        rows.removeAllViews();
        List<PushStore.Item> items = PushStore.list(this);
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.push_empty);
            empty.setTextColor(0xFFACB8C1);
            empty.setTextSize(18);
            empty.setPadding(0, (int) (24 * getResources().getDisplayMetrics().density), 0, 0);
            rows.addView(empty);
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        float density = getResources().getDisplayMetrics().density;
        for (PushStore.Item item : items) {
            TextView row = new TextView(this);
            row.setText(item.name + "\n" + fmt.format(new Date(item.time))
                    + " · " + (item.size / 1024) + " KB");
            row.setTextColor(0xFFFFFFFF);
            row.setTextSize(18);
            row.setPadding((int) (16 * density), (int) (18 * density),
                    (int) (16 * density), (int) (18 * density));
            row.setBackgroundResource(R.drawable.settings_choice_bg);
            row.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (10 * density);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> showActions(item));
            rows.addView(row);
        }
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
    }

    private void showActions(PushStore.Item item) {
        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(new CharSequence[]{
                        getString(R.string.push_install),
                        getString(R.string.push_delete)
                }, (d, which) -> {
                    if (which == 0) install(item);
                    else {
                        PushStore.delete(this, item.id);
                        Toast.makeText(this, R.string.push_deleted, Toast.LENGTH_SHORT).show();
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void install(PushStore.Item item) {
        File apk = new File(item.path);
        if (!apk.isFile()) {
            Toast.makeText(this, R.string.push_file_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        status.setText(getString(R.string.update_progress, 0, "installing"));
        ApkInstaller.installFile(this, apk, new ApkInstaller.Progress() {
            @Override
            public void onProgress(int percent, String message) {
                status.setText(getString(R.string.update_progress, percent, message));
            }

            @Override
            public void onDone(boolean ok, String message) {
                status.setText(ok ? getString(R.string.update_ok)
                        : getString(R.string.update_fail, message));
                if (!ok) {
                    new AlertDialog.Builder(PushHistoryActivity.this)
                            .setMessage(getString(R.string.update_fail, message))
                            .setPositiveButton(R.string.update_retry, (d, w) -> install(item))
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }
            }
        });
    }
}
