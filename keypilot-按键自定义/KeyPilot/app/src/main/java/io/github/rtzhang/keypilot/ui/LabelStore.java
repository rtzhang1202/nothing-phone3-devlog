package io.github.rtzhang.keypilot.ui;

import android.content.Context;
import android.content.Intent;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.rtzhang.keypilot.config.ActionSpec;
import io.github.rtzhang.keypilot.config.ActionType;
import io.github.rtzhang.keypilot.config.KeyPilotConfig;

/**
 * Activity label store (docs/DESIGN.md §3.4 labels 协议).
 *
 * Two layers:
 *  - community: bundled asset community_labels.json (read-only built-in list),
 *  - user:      app-side cache files/keypilot_labels.json (source=user),
 * mirrored to /data/system/keypilot_labels.json through the CONFIG_UPDATE bridge
 * (command=LABELS_UPDATE, written by system_server ConfigBridge).
 *
 * Wire format (flat map, "_"-prefixed keys are metadata and ignored):
 *   { "com.tencent.mm/.plugin.scanner.ui.BaseScanUI":
 *         { "label": "扫一扫", "source": "user|community", "updatedAt": 1795411200000 } }
 * Component keys may use the Android shorthand "pkg/.Cls" and are normalised to the
 * fully qualified "pkg/full.Cls" form used by ComponentName.
 */
public final class LabelStore {

    public static final String SOURCE_USER = "user";
    public static final String SOURCE_COMMUNITY = "community";

    public static final String USER_FILENAME = "keypilot_labels.json";
    public static final String COMMUNITY_ASSET = "community_labels.json";

    public static final class Entry {
        public final String label;
        public final String source;
        public final long updatedAt;

        Entry(String label, String source, long updatedAt) {
            this.label = label;
            this.source = source;
            this.updatedAt = updatedAt;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("label", label);
            o.put("source", source);
            o.put("updatedAt", updatedAt);
            return o;
        }
    }

    /** Result of a JSON import merge. */
    public static final class ImportResult {
        public int added;
        public int updated;
        public int conflicts;
        public int malformed;
        public int total;
    }

    private static final LinkedHashMap<String, Entry> sUser = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Entry> sCommunity = new LinkedHashMap<>();
    private static boolean sLoaded = false;

    private LabelStore() {
    }

    // ------------------------------------------------------------- lifecycle

    private static void ensureLoadedLocked(Context ctx) {
        if (sLoaded) return;
        sLoaded = true;
        // 1) community asset (read-only)
        try {
            InputStream is = ctx.getAssets().open(COMMUNITY_ASSET);
            try {
                String raw = readAll(is);
                parseInto(raw, sCommunity, false, null);
            } finally {
                is.close();
            }
        } catch (Throwable ignored) {
            // asset missing/legacy build -> empty community list
        }
        // 2) user cache
        File f = new File(ctx.getFilesDir(), USER_FILENAME);
        if (f.exists() && f.length() > 0) {
            try {
                String raw = readAll(new FileInputStream(f));
                parseInto(raw, sUser, true, null);
            } catch (Throwable ignored) {
            }
        }
    }

    public static synchronized void ensureLoaded(Context ctx) {
        if (ctx == null) return;
        ensureLoadedLocked(ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx);
    }

    // ------------------------------------------------------------- lookups

    /** Normalises "pkg/.Cls" / "pkg/cls" / pkg+cls pair into canonical "pkg/full.Cls". */
    public static String normalizeComponent(String pkg, String cls) {
        if (pkg == null || cls == null) return null;
        String p = pkg.trim();
        String c = cls.trim();
        if (p.isEmpty() || c.isEmpty()) return null;
        if (c.startsWith(".")) c = p + c;
        return p + "/" + c;
    }

    /** Normalises a stored map key; returns null for malformed keys. */
    public static String normalizeKey(String key) {
        if (key == null) return null;
        String k = key.trim();
        int slash = k.indexOf('/');
        if (slash <= 0 || slash >= k.length() - 1) return null;
        return normalizeComponent(k.substring(0, slash), k.substring(slash + 1));
    }

    public static synchronized String getLabel(Context ctx, String component) {
        if (ctx == null || component == null) return null;
        ensureLoadedLocked(ctx);
        Entry u = sUser.get(component);
        if (u != null && u.label != null && !u.label.isEmpty()) return u.label;
        Entry c = sCommunity.get(component);
        if (c != null && c.label != null && !c.label.isEmpty()) return c.label;
        return null;
    }

    /** Display name for a launch_activity spec: user > community > spec.label > short class. */
    public static synchronized String displayLabel(Context ctx, ActionSpec spec) {
        if (spec == null || spec.type != ActionType.LAUNCH_ACTIVITY) return null;
        String comp = normalizeComponent(spec.packageName, spec.className);
        if (comp != null) {
            String named = getLabel(ctx, comp);
            if (named != null) return named;
        }
        if (spec.label != null && !spec.label.isEmpty()) return spec.label;
        if (spec.className == null) return null;
        return shortClass(spec.packageName, spec.className);
    }

    /** "com.foo.Bar$Baz" -> ".Bar$Baz" when the class lives inside the bound package. */
    public static String shortClass(String pkg, String cls) {
        if (cls == null) return null;
        if (pkg != null && !pkg.isEmpty() && cls.startsWith(pkg + ".")) {
            return cls.substring(pkg.length());
        }
        return cls;
    }

    public static synchronized List<Map.Entry<String, Entry>> userEntries(Context ctx) {
        if (ctx == null) return new ArrayList<>();
        ensureLoadedLocked(ctx);
        return new ArrayList<>(sUser.entrySet());
    }

    public static synchronized List<Map.Entry<String, Entry>> communityEntries(Context ctx) {
        if (ctx == null) return new ArrayList<>();
        ensureLoadedLocked(ctx);
        return new ArrayList<>(sCommunity.entrySet());
    }

    public static synchronized int userCount(Context ctx) {
        ensureLoadedLocked(ctx);
        return sUser.size();
    }

    public static synchronized int communityCount(Context ctx) {
        ensureLoadedLocked(ctx);
        return sCommunity.size();
    }

    // ------------------------------------------------------------- mutation

    /** Stores / overwrites a user label and mirrors it to /data/system. */
    public static synchronized void putUserLabel(Context ctx, String component, String label) {
        if (ctx == null || component == null || label == null || label.trim().isEmpty()) return;
        ensureLoadedLocked(ctx);
        sUser.put(component, new Entry(label.trim(), SOURCE_USER, System.currentTimeMillis()));
        persistAndMirror(ctx);
    }

    /** Removes a user label (falls back to the community name if one exists). */
    public static synchronized void removeUserLabel(Context ctx, String component) {
        if (ctx == null || component == null) return;
        ensureLoadedLocked(ctx);
        if (sUser.remove(component) != null) {
            persistAndMirror(ctx);
        }
    }

    private static void persistAndMirror(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), USER_FILENAME);
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(exportJson().getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Throwable ignored) {
        }
        try {
            Intent intent = new Intent(KeyPilotConfig.ACTION_CONFIG_UPDATE);
            intent.putExtra(KeyPilotConfig.EXTRA_COMMAND, KeyPilotConfig.CMD_LABELS_UPDATE);
            intent.putExtra(KeyPilotConfig.EXTRA_LABELS_JSON, exportJson());
            ctx.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------- json

    /** Exports user labels using the canonical labels wire format (pretty printed). */
    public static synchronized String exportJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("_meta", new JSONObject()
                    .put("format", "keypilot.labels.v1")
                    .put("exportedAt", System.currentTimeMillis())
                    .put("count", sUser.size()));
            for (Map.Entry<String, Entry> e : sUser.entrySet()) {
                out.put(e.getKey(), e.getValue().toJson());
            }
        } catch (JSONException ignored) {
        }
        try {
            return out.toString(2);
        } catch (JSONException e) {
            return out.toString();
        }
    }

    /**
     * Merges an imported labels document into the user layer.
     * fillGaps only: entries already present locally are counted as conflicts and kept
     * local unless overwriteConflicts is true (then the imported label wins).
     */
    public static synchronized ImportResult importJson(Context ctx, String raw,
                                                       boolean overwriteConflicts) {
        ImportResult res = new ImportResult();
        if (ctx == null || raw == null || raw.trim().isEmpty()) return res;
        ensureLoadedLocked(ctx);
        LinkedHashMap<String, Entry> incoming = new LinkedHashMap<>();
        parseInto(raw, incoming, true, res);
        res.total = incoming.size();
        for (Map.Entry<String, Entry> e : incoming.entrySet()) {
            String comp = e.getKey();
            Entry existing = sUser.get(comp);
            if (existing == null) {
                sUser.put(comp, new Entry(e.getValue().label, SOURCE_USER,
                        System.currentTimeMillis()));
                res.added++;
            } else if (!existing.label.equals(e.getValue().label)) {
                res.conflicts++;
                if (overwriteConflicts) {
                    sUser.put(comp, new Entry(e.getValue().label, SOURCE_USER,
                            System.currentTimeMillis()));
                    res.updated++;
                }
            }
        }
        if (res.added > 0 || (overwriteConflicts && res.updated > 0)) {
            persistAndMirror(ctx);
        }
        return res;
    }

    /** Parses a labels document into the given map (skips "_"-prefixed metadata keys). */
    private static void parseInto(String raw, LinkedHashMap<String, Entry> target,
                                  boolean skipMalformed, ImportResult stats) {
        if (raw == null) return;
        try {
            JSONObject obj = new JSONObject(raw);
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String key = it.next();
                if (key.startsWith("_")) continue;
                String comp = normalizeKey(key);
                if (comp == null) {
                    if (stats != null) stats.malformed++;
                    continue;
                }
                Object v = obj.opt(key);
                if (!(v instanceof JSONObject)) {
                    if (stats != null) stats.malformed++;
                    continue;
                }
                JSONObject vo = (JSONObject) v;
                String label = vo.optString("label", null);
                if (label == null || label.trim().isEmpty()) {
                    if (stats != null) stats.malformed++;
                    continue;
                }
                String source = vo.optString("source",
                        target == sCommunity ? SOURCE_COMMUNITY : SOURCE_USER);
                long updatedAt = vo.optLong("updatedAt", 0L);
                target.put(comp, new Entry(label.trim(), source, updatedAt));
            }
        } catch (Throwable ignored) {
            if (stats != null) stats.malformed++;
        }
    }

    private static String readAll(InputStream is) throws Exception {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { is.close(); } catch (Throwable ignored) { }
        }
    }
}
