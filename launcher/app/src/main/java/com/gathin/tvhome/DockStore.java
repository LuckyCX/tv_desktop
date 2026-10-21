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

    /** Effective dock package order: override if present且非空, else theme/config list. */
    static List<String> effectiveDock(Context ctx, List<String> configDock) {
        List<String> override = loadLines(ctx, DOCK_FILE);
        if (override == null) return configDock == null ? Collections.emptyList() : configDock;
        List<String> installed = installedOnly(ctx, override);
        // 空覆盖视为无效（否则程序池会整行消失）
        if (installed.isEmpty()) {
            clearDockOverride(ctx);
            return configDock == null ? Collections.emptyList() : configDock;
        }
        return installed;
    }

    static Set<String> userHidden(Context ctx) {
        List<String> lines = loadLines(ctx, HIDDEN_FILE);
        if (lines == null) return Collections.emptySet();
        return new LinkedHashSet<>(lines);
    }

    static boolean hasDockOverride(Context ctx) {
        File f = new File(ctx.getFilesDir(), DOCK_FILE);
        if (!f.exists()) return false;
        List<String> lines = loadLines(ctx, DOCK_FILE);
        return lines != null && !installedOnly(ctx, lines).isEmpty();
    }

    static void clearDockOverride(Context ctx) {
        File f = new File(ctx.getFilesDir(), DOCK_FILE);
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "failed to delete empty dock override");
        }
    }

    /**
     * 上屏。currentDock 为当前 Dock 上已显示的包名顺序（含 fill_dock 填入的），
     * 避免尚未有 override 文件时写空列表导致下屏无效。
     */
    static void pinToDock(Context ctx, String pkg, List<String> currentDock, int maxSlots) {
        if (pkg == null || pkg.isEmpty()) return;
        List<String> dock = new ArrayList<>();
        if (currentDock != null) {
            for (String p : currentDock) {
                if (p != null && !p.isEmpty() && !p.equals(pkg)) dock.add(p);
            }
        }
        dock.add(0, pkg);
        while (dock.size() > Math.max(1, maxSlots)) dock.remove(dock.size() - 1);
        Set<String> hidden = new LinkedHashSet<>(userHidden(ctx));
        hidden.remove(pkg);
        saveLines(ctx, HIDDEN_FILE, new ArrayList<>(hidden));
        LibraryApps.save(ctx, pkg, true);
        saveLines(ctx, DOCK_FILE, dock);
    }

    /** 下屏：从当前 Dock 列表移除并持久化 override。 */
    static void unpinFromDock(Context ctx, String pkg, List<String> currentDock) {
        if (pkg == null || pkg.isEmpty()) return;
        List<String> dock = new ArrayList<>();
        if (currentDock != null) {
            for (String p : currentDock) {
                if (p != null && !p.isEmpty() && !p.equals(pkg)) dock.add(p);
            }
        }
        // 下光后若 Dock 为空，删掉 override，让 fill_dock 重新填程序池
        if (dock.isEmpty()) clearDockOverride(ctx);
        else saveLines(ctx, DOCK_FILE, dock);
    }

    static void hide(Context ctx, String pkg, List<String> currentDock) {
        if (pkg == null || pkg.isEmpty()) return;
        unpinFromDock(ctx, pkg, currentDock);
        Set<String> hidden = new LinkedHashSet<>(userHidden(ctx));
        hidden.add(pkg);
        saveLines(ctx, HIDDEN_FILE, new ArrayList<>(hidden));
        LibraryApps.save(ctx, pkg, false);
    }

    /** 仅移出隐藏，进入全部应用池，不上屏。 */
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
