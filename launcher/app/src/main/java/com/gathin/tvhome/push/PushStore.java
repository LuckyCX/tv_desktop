package com.gathin.tvhome.push;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Persists pushed APK metadata under files/pushes/. */
final class PushStore {
    private static final String TAG = "TvHome.PushStore";
    private static final String INDEX = "index.json";

    static final class Item {
        final String id;
        final String name;
        final long size;
        final String path;
        final long time;

        Item(String id, String name, long size, String path, long time) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.path = path;
            this.time = time;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("size", size)
                    .put("path", path)
                    .put("time", time);
        }

        static Item fromJson(JSONObject o) {
            return new Item(
                    o.optString("id"),
                    o.optString("name"),
                    o.optLong("size"),
                    o.optString("path"),
                    o.optLong("time"));
        }
    }

    private PushStore() {}

    static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "pushes");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static synchronized List<Item> list(Context ctx) {
        List<Item> out = new ArrayList<>();
        try {
            File f = new File(dir(ctx), INDEX);
            if (!f.isFile()) return out;
            JSONArray arr = new JSONArray(readText(f));
            for (int i = 0; i < arr.length(); i++) out.add(Item.fromJson(arr.getJSONObject(i)));
            Collections.reverse(out); // newest first for UI
        } catch (Exception e) {
            Log.w(TAG, "list", e);
        }
        return out;
    }

    /** Save uploaded bytes; rename on name conflict. Returns new item. */
    static synchronized Item add(Context ctx, String originalName, InputStream body, long knownSize) throws Exception {
        String base = sanitizeName(originalName);
        String name = uniqueName(ctx, base);
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        File apk = new File(dir(ctx), id + ".apk");
        long size = 0;
        try (OutputStream out = new FileOutputStream(apk)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = body.read(buf)) != -1) {
                out.write(buf, 0, n);
                size += n;
            }
        }
        if (knownSize > 0 && size == 0) size = knownSize;
        Item item = new Item(id, name, size, apk.getAbsolutePath(), System.currentTimeMillis());
        List<Item> all = loadRaw(ctx);
        all.add(item);
        saveRaw(ctx, all);
        return item;
    }

    static synchronized Item get(Context ctx, String id) {
        for (Item i : loadRaw(ctx)) if (i.id.equals(id)) return i;
        return null;
    }

    static synchronized boolean delete(Context ctx, String id) {
        List<Item> all = loadRaw(ctx);
        Item found = null;
        for (Item i : all) if (i.id.equals(id)) { found = i; break; }
        if (found == null) return false;
        all.remove(found);
        //noinspection ResultOfMethodCallIgnored
        new File(found.path).delete();
        try {
            saveRaw(ctx, all);
        } catch (Exception e) {
            Log.w(TAG, "delete save", e);
        }
        return true;
    }

    private static List<Item> loadRaw(Context ctx) {
        List<Item> out = new ArrayList<>();
        try {
            File f = new File(dir(ctx), INDEX);
            if (!f.isFile()) return out;
            JSONArray arr = new JSONArray(readText(f));
            for (int i = 0; i < arr.length(); i++) out.add(Item.fromJson(arr.getJSONObject(i)));
        } catch (Exception e) {
            Log.w(TAG, "load", e);
        }
        return out;
    }

    private static void saveRaw(Context ctx, List<Item> items) throws Exception {
        JSONArray arr = new JSONArray();
        for (Item i : items) arr.put(i.toJson());
        writeText(new File(dir(ctx), INDEX), arr.toString(2));
    }

    private static String sanitizeName(String name) {
        if (name == null || name.trim().isEmpty()) return "app.apk";
        String n = PushHttpServer.fixUtf8Mojibake(name.trim()).replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        // 只去掉非法路径字符，保留中文
        n = n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_").trim();
        if (n.isEmpty()) n = "app.apk";
        if (!n.toLowerCase(Locale.US).endsWith(".apk")) n = n + ".apk";
        return n;
    }

    private static String uniqueName(Context ctx, String base) {
        boolean clash = false;
        for (Item i : loadRaw(ctx)) {
            if (i.name.equalsIgnoreCase(base)) { clash = true; break; }
        }
        if (!clash) return base;
        String stem = base.substring(0, base.length() - 4);
        for (int n = 2; n < 1000; n++) {
            String cand = stem + "_" + n + ".apk";
            boolean ok = true;
            for (Item i : loadRaw(ctx)) {
                if (i.name.equalsIgnoreCase(cand)) { ok = false; break; }
            }
            if (ok) return cand;
        }
        return stem + "_" + System.currentTimeMillis() + ".apk";
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
}
