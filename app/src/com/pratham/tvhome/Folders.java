package com.pratham.tvhome;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App folders, stored as lines of "Name|pkg1,pkg2" in the "folders" preference. A folder shows up
 * on the home screen under the key "@folder:Name".
 */
final class Folders {
    static final String PREFIX = "@folder:";
    static final String[] PRESETS = {"Streaming", "Music", "TV & Media", "Utilities", "Games"};

    private Folders() {}

    static String key(String name) {
        return PREFIX + name;
    }

    static boolean isFolder(String key) {
        return key.startsWith(PREFIX);
    }

    static String name(String key) {
        return key.substring(PREFIX.length());
    }

    static LinkedHashMap<String, List<String>> read(SharedPreferences p) {
        LinkedHashMap<String, List<String>> out = new LinkedHashMap<>();
        String raw = p.getString("folders", "");
        for (String line : raw.split("\n")) {
            int bar = line.indexOf('|');
            if (bar <= 0) continue;
            List<String> pkgs = new ArrayList<>();
            for (String k : line.substring(bar + 1).split(",")) if (!k.isEmpty()) pkgs.add(k);
            out.put(line.substring(0, bar), pkgs);
        }
        return out;
    }

    static void write(SharedPreferences p, Map<String, List<String>> folders) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> f : folders.entrySet()) {
            if (f.getValue().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(f.getKey()).append('|').append(String.join(",", f.getValue()));
        }
        p.edit().putString("folders", sb.toString()).apply();
    }

    /** Replaces {@code from} with {@code to} in a comma-separated key list stored under {@code prefKey}. */
    static void replaceKey(SharedPreferences p, String prefKey, String from, String to) {
        String raw = p.getString(prefKey, null);
        if (raw == null) return;
        List<String> keys = new ArrayList<>(Arrays.asList(raw.split(",")));
        int i = keys.indexOf(from);
        if (i < 0) return;
        if (to == null || keys.contains(to)) keys.remove(i);
        else keys.set(i, to);
        p.edit().putString(prefKey, String.join(",", keys)).apply();
    }

    /** Inserts {@code key} right after {@code anchor} in the stored order (or at the end). */
    static void insertAfter(SharedPreferences p, String prefKey, String anchor, String key) {
        List<String> keys = new ArrayList<>(Arrays.asList(p.getString(prefKey, "").split(",")));
        keys.remove(key);
        int i = keys.indexOf(anchor);
        keys.add(i < 0 ? keys.size() : i + 1, key);
        p.edit().putString(prefKey, String.join(",", keys)).apply();
    }
}
