package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Local-only backup of settings + wallpaper assets (no upload). */
final class LocalBackup {
    private static final String TAG = "TvHome.Backup";

    static final class Entry {
        final String id;
        final File dir;
        final long timeMs;

        Entry(String id, File dir, long timeMs) {
            this.id = id;
            this.dir = dir;
            this.timeMs = timeMs;
        }
    }

    private LocalBackup() {}

    static File root(Context ctx) {
        return new File(ctx.getFilesDir(), "backups");
    }

    static List<Entry> list(Context ctx) {
        File root = root(ctx);
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) return Collections.emptyList();
        Arrays.sort(dirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        List<Entry> out = new ArrayList<>();
        for (File d : dirs) {
            out.add(new Entry(d.getName(), d, d.lastModified()));
        }
        return out;
    }

    static String create(Context ctx) throws Exception {
        String id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File dir = new File(root(ctx), id);
        if (!dir.mkdirs()) throw new IllegalStateException("mkdir backup");

        JSONObject json = new JSONObject();
        SharedPreferences sp = UserPrefs.sp(ctx);
        JSONObject settings = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) settings.put(e.getKey(), (Boolean) v);
            else if (v instanceof Integer) settings.put(e.getKey(), (Integer) v);
            else if (v instanceof Long) settings.put(e.getKey(), (Long) v);
            else if (v instanceof Float) settings.put(e.getKey(), (Float) v);
            else if (v instanceof String) settings.put(e.getKey(), (String) v);
        }
        json.put("settings", settings);

        File dock = new File(ctx.getFilesDir(), "dock_override.txt");
        if (dock.exists()) json.put("dock_override", readText(dock));
        File hidden = new File(ctx.getFilesDir(), "user_hidden.txt");
        if (hidden.exists()) json.put("user_hidden", readText(hidden));
        File lib = new File(ctx.getFilesDir(), "library_apps.txt");
        if (lib.exists()) json.put("library_apps", readText(lib));
        File rem = new File(ctx.getFilesDir(), "library_home_removed.txt");
        if (rem.exists()) json.put("library_home_removed", readText(rem));

        writeText(new File(dir, "settings.json"), json.toString(2));

        // Wallpaper assets from external files dir
        File themeDir = Config.dir(ctx);
        if (themeDir != null && themeDir.isDirectory()) {
            File wall = new File(themeDir, Config.WALLPAPER_NAME);
            if (wall.isFile()) copyFile(wall, new File(dir, Config.WALLPAPER_NAME));
            File walls = new File(themeDir, "wallpapers");
            if (walls.isDirectory()) {
                File destWalls = new File(dir, "wallpapers");
                //noinspection ResultOfMethodCallIgnored
                destWalls.mkdirs();
                File[] imgs = walls.listFiles();
                if (imgs != null) {
                    for (File img : imgs) {
                        if (img.isFile()) copyFile(img, new File(destWalls, img.getName()));
                    }
                }
            }
            File cfg = new File(themeDir, Config.FILE_NAME);
            if (cfg.isFile()) copyFile(cfg, new File(dir, Config.FILE_NAME));
        }
        return id;
    }

    static void restore(Context ctx, String id) throws Exception {
        File dir = new File(root(ctx), id);
        if (!dir.isDirectory()) throw new IllegalArgumentException("missing backup");
        File settingsFile = new File(dir, "settings.json");
        if (settingsFile.isFile()) {
            JSONObject json = new JSONObject(readText(settingsFile));
            JSONObject settings = json.optJSONObject("settings");
            if (settings != null) {
                SharedPreferences.Editor ed = UserPrefs.sp(ctx).edit();
                ed.clear();
                JSONArray names = settings.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String k = names.getString(i);
                        Object v = settings.get(k);
                        if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
                        else if (v instanceof Integer) ed.putInt(k, (Integer) v);
                        else if (v instanceof Long) ed.putLong(k, (Long) v);
                        else if (v instanceof Double) ed.putFloat(k, ((Double) v).floatValue());
                        else ed.putString(k, String.valueOf(v));
                    }
                }
                ed.commit();
            }
            if (json.has("dock_override")) writeText(new File(ctx.getFilesDir(), "dock_override.txt"), json.getString("dock_override"));
            if (json.has("user_hidden")) writeText(new File(ctx.getFilesDir(), "user_hidden.txt"), json.getString("user_hidden"));
            if (json.has("library_apps")) writeText(new File(ctx.getFilesDir(), "library_apps.txt"), json.getString("library_apps"));
            if (json.has("library_home_removed")) writeText(new File(ctx.getFilesDir(), "library_home_removed.txt"), json.getString("library_home_removed"));
        }

        File themeDir = Config.dir(ctx);
        if (themeDir != null) {
            //noinspection ResultOfMethodCallIgnored
            themeDir.mkdirs();
            File wall = new File(dir, Config.WALLPAPER_NAME);
            if (wall.isFile()) copyFile(wall, new File(themeDir, Config.WALLPAPER_NAME));
            File walls = new File(dir, "wallpapers");
            if (walls.isDirectory()) {
                File dest = new File(themeDir, "wallpapers");
                //noinspection ResultOfMethodCallIgnored
                dest.mkdirs();
                File[] imgs = walls.listFiles();
                if (imgs != null) {
                    for (File img : imgs) {
                        if (img.isFile()) copyFile(img, new File(dest, img.getName()));
                    }
                }
            }
            File cfg = new File(dir, Config.FILE_NAME);
            if (cfg.isFile()) copyFile(cfg, new File(themeDir, Config.FILE_NAME));
        }
    }

    static boolean delete(Context ctx, String id) {
        File dir = new File(root(ctx), id);
        return deleteRecursive(dir);
    }

    private static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        return f.delete();
    }

    private static String readText(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File f, String text) throws Exception {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copyFile(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }
}
