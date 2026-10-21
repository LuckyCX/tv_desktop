package com.gathin.tvhome;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * User dock override and user-hidden packages (Apple TV pin/unpin/hide).
 * Missing packages are skipped at display time — never render broken icons.
 */
final class DockStore {
    private static final String TAG = "TvHome.Dock";
    private static final String DOCK_FILE = "dock_override.txt";
    private static final String HIDDEN_FILE = "user_hidden.txt";

    private DockStore() {}

    /** Effective dock package order: override if present, else theme/config list. */
    static List<String> effectiveDock(Context ctx, List<String> configDock) {
        List<String> override = loadLines(ctx, DOCK_FILE);
        if (override == null) return configDock;
        return installedOnly(ctx, override);
    }

    static Set<String> userHidden(Context ctx) {
        List<String> lines = loadLines(ctx, HIDDEN_FILE);
        if (lines == null) return Collections.emptySet();
        return new LinkedHashSet<>(lines);
    }

    static boolean hasDockOverride(Context ctx) {
        return new File(ctx.getFilesDir(), DOCK_FILE).exists();
    }

    static void pinToDock(Context ctx, String pkg, List<String> configDock, int maxSlots) {
        if (pkg == null || pkg.isEmpty()) return;
        List<String> dock = new ArrayList<>(effectiveDock(ctx, configDock));
        dock.remove(pkg);
        dock.add(0, pkg);
        while (dock.size() > Math.max(1, maxSlots)) dock.remove(dock.size() - 1);
        // Unhide if pinning
        Set<String> hidden = new LinkedHashSet<>(userHidden(ctx));
        hidden.remove(pkg);
        saveLines(ctx, HIDDEN_FILE, new ArrayList<>(hidden));
        LibraryApps.save(ctx, pkg, true);
        saveLines(ctx, DOCK_FILE, dock);
    }

    static void unpinFromDock(Context ctx, String pkg, List<String> configDock) {
        if (pkg == null || pkg.isEmpty()) return;
        List<String> dock = new ArrayList<>(effectiveDock(ctx, configDock));
        dock.remove(pkg);
        saveLines(ctx, DOCK_FILE, dock);
    }

    static void hide(Context ctx, String pkg, List<String> configDock) {
        if (pkg == null || pkg.isEmpty()) return;
        unpinFromDock(ctx, pkg, configDock);
        Set<String> hidden = new LinkedHashSet<>(userHidden(ctx));
        hidden.add(pkg);
        saveLines(ctx, HIDDEN_FILE, new ArrayList<>(hidden));
        LibraryApps.save(ctx, pkg, false);
    }

    static void unhide(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        Set<String> hidden = new LinkedHashSet<>(userHidden(ctx));
        hidden.remove(pkg);
        saveLines(ctx, HIDDEN_FILE, new ArrayList<>(hidden));
        LibraryApps.save(ctx, pkg, true);
    }

    static List<String> installedOnly(Context ctx, List<String> pkgs) {
        List<String> out = new ArrayList<>();
        for (String p : pkgs) {
            if (p == null || p.isEmpty()) continue;
            try {
                ctx.getPackageManager().getApplicationInfo(p, 0);
                out.add(p);
            } catch (Exception ignored) {
                // skip uninstalled — no broken icon
            }
        }
        return out;
    }

    private static List<String> loadLines(Context ctx, String name) {
        File f = new File(ctx.getFilesDir(), name);
        if (!f.exists()) return null;
        AtomicFile af = new AtomicFile(f);
        try (FileInputStream in = af.openRead()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>();
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) lines.add(t);
            }
            return lines;
        } catch (Exception e) {
            Log.w(TAG, "read " + name, e);
            return null;
        }
    }

    private static void saveLines(Context ctx, String name, List<String> lines) {
        AtomicFile af = new AtomicFile(new File(ctx.getFilesDir(), name));
        FileOutputStream out = null;
        try {
            StringBuilder sb = new StringBuilder();
            LinkedHashSet<String> uniq = new LinkedHashSet<>();
            for (String l : lines) {
                if (l != null && !l.trim().isEmpty()) uniq.add(l.trim());
            }
            for (String l : uniq) sb.append(l).append('\n');
            out = af.startWrite();
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            af.finishWrite(out);
        } catch (Exception e) {
            if (out != null) af.failWrite(out);
            Log.w(TAG, "write " + name, e);
        }
    }
}
