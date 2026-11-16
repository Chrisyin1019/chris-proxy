package hk.chris.proxy;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class RoutingStore {
    private static final String PREFS = "routing";
    private static final String MODE = "mode";
    private static final String RULES = "rules";
    private static final String FINAL = "final";
    private static final String MIGRATED = "migrated_from_policy_v1";
    private static final String VERSION = "config_version";
    private static final String HISTORY = "config_history";
    private static final int MAX_HISTORY = 20;

    private RoutingStore() {}

    public static synchronized Routing load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(MIGRATED, false)) return migrate(context);
        return readCurrent(context, p);
    }

    private static Routing readCurrent(Context context, SharedPreferences p) {
        Routing.Mode mode;
        try {
            mode = Routing.Mode.valueOf(p.getString(MODE, Routing.Mode.RULE.name()));
        } catch (IllegalArgumentException e) {
            mode = Routing.Mode.RULE;
        }
        ArrayList<RouteRule> rules = new ArrayList<>();
        String raw = p.getString(RULES, "");
        if (raw != null) {
            for (String line : raw.split("\n")) {
                if (line.isEmpty()) continue;
                try {
                    rules.add(RouteRule.deserialize(line));
                } catch (IllegalArgumentException e) {
                    AppLogger.log(context, "WARN", "略過一條無法解析的已保存規則");
                }
            }
        }
        String fallback;
        try {
            fallback = RouteRule.normalizeTarget(p.getString(FINAL, RouteRule.TARGET_DIRECT));
        } catch (IllegalArgumentException e) {
            fallback = RouteRule.TARGET_DIRECT;
        }
        return new Routing(mode, rules, fallback);
    }

    public static synchronized void save(Context context, Routing routing) {
        save(context, routing, "設定變更");
    }

    public static synchronized void save(Context context, Routing routing, String reason) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(MIGRATED, false)) {
            migrate(context);
            p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        Routing previous = readCurrent(context, p);
        if (RoutingCodec.encode(previous).equals(RoutingCodec.encode(routing))) return;
        long previousVersion = Math.max(1L, p.getLong(VERSION, 1L));
        ArrayList<Version> history = new ArrayList<>(readHistory(p));
        history.add(0, new Version(previousVersion, System.currentTimeMillis(),
                reason == null || reason.trim().isEmpty() ? "設定變更" : reason.trim(), previous));
        trim(history);
        writeCurrent(p, routing, previousVersion + 1, history);
    }

    public static synchronized long currentVersion(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(MIGRATED, false)) migrate(context);
        return Math.max(1L, p.getLong(VERSION, 1L));
    }

    public static synchronized List<Version> history(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(MIGRATED, false)) migrate(context);
        return new ArrayList<>(readHistory(p));
    }

    /** Restores a local rules-only snapshot and keeps the pre-rollback state recoverable. */
    public static synchronized Routing rollback(Context context, long version) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(MIGRATED, false)) migrate(context);
        ArrayList<Version> history = new ArrayList<>(readHistory(p));
        Version target = null;
        for (Version item : history) if (item.version == version) { target = item; break; }
        if (target == null) throw new IllegalArgumentException("找不到規則版本 v" + version);

        Routing current = readCurrent(context, p);
        long currentVersion = Math.max(1L, p.getLong(VERSION, 1L));
        history.removeIf(item -> item.version == version);
        history.add(0, new Version(currentVersion, System.currentTimeMillis(), "回退前自動快照", current));
        trim(history);
        writeCurrent(p, target.routing, currentVersion + 1, history);
        AppLogger.log(context, "INFO", "分流規則已回退到版本 v" + version);
        return target.routing;
    }

    private static void writeCurrent(SharedPreferences p, Routing routing, long version, List<Version> history) {
        StringBuilder rules = new StringBuilder();
        for (RouteRule rule : routing.rules) {
            if (rules.length() > 0) rules.append('\n');
            rules.append(rule.serialize());
        }
        p.edit()
                .putString(MODE, routing.mode.name())
                .putString(RULES, rules.toString())
                .putString(FINAL, routing.finalTarget)
                .putBoolean(MIGRATED, true)
                .putLong(VERSION, version)
                .putString(HISTORY, encodeHistory(history))
                .apply();
    }

    private static List<Version> readHistory(SharedPreferences p) {
        ArrayList<Version> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(p.getString(HISTORY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                result.add(new Version(item.getLong("version"), item.optLong("savedAt"),
                        item.optString("reason", "設定變更"),
                        RoutingCodec.decode(item.getJSONObject("routing").toString())));
            }
        } catch (Exception ignored) {
            // A broken history must never stop the active proxy configuration from loading.
        }
        return result;
    }

    private static String encodeHistory(List<Version> history) {
        JSONArray array = new JSONArray();
        try {
            for (Version item : history) {
                array.put(new JSONObject()
                        .put("version", item.version)
                        .put("savedAt", item.savedAt)
                        .put("reason", item.reason)
                        .put("routing", RoutingCodec.toJson(item.routing)));
            }
        } catch (Exception e) {
            throw new IllegalStateException("無法保存規則版本", e);
        }
        return array.toString();
    }

    private static void trim(ArrayList<Version> history) {
        while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
    }

    /** One-time move of the old whitelist/blacklist domain sets into ordered rules. */
    private static Routing migrate(Context context) {
        Policy legacy = PolicyStore.load(context);
        Routing routing = Routing.fromLegacy(legacy);
        writeCurrent(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), routing, 1L, new ArrayList<>());
        PolicyStore.save(context, new Policy(legacy.mode, legacy.directSsids,
                new LinkedHashSet<>(), new LinkedHashSet<>(), legacy.blockedDomains, legacy.privateDns));
        if (!routing.rules.isEmpty()) {
            AppLogger.log(context, "INFO", "舊版白名單/黑名單已轉為分流規則，條數=" + routing.rules.size());
        }
        return routing;
    }

    public static final class Version {
        public final long version;
        public final long savedAt;
        public final String reason;
        public final Routing routing;

        Version(long version, long savedAt, String reason, Routing routing) {
            this.version = version;
            this.savedAt = savedAt;
            this.reason = reason;
            this.routing = routing;
        }
    }

    /** Rules pointing at a node that no longer exists, for UI warnings. */
    public static List<RouteRule> orphaned(Routing routing, List<ProxyProfile> profiles) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ProxyProfile profile : profiles) ids.add(profile.id());
        ArrayList<RouteRule> result = new ArrayList<>();
        for (RouteRule rule : routing.rules) {
            if (rule.enabled && rule.nodeId() != null && !ids.contains(rule.nodeId())) result.add(rule);
        }
        return result;
    }
}
