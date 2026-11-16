package hk.chris.proxy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Node subscriptions: a URL returning a node list (plain or base64, one link
 * per line). Each subscription remembers which node ids it contributed, so an
 * update adds new nodes and removes the ones the provider dropped without
 * touching manually added nodes. URLs are stored Keystore-encrypted.
 */
public final class SubscriptionStore {
    private static final String PREFS = "subscriptions";
    private static final String KEY = "list";

    private SubscriptionStore() {}

    public static final class Subscription {
        public final String id;
        public final String name;
        public final String url;
        public final long updatedAt;
        public final String error;
        public final List<String> nodeIds;

        Subscription(String id, String name, String url, long updatedAt, String error, List<String> nodeIds) {
            this.id = id;
            this.name = name == null || name.trim().isEmpty() ? hostOf(url) : name.trim();
            this.url = url;
            this.updatedAt = updatedAt;
            this.error = error == null ? "" : error;
            this.nodeIds = nodeIds;
        }

        public boolean stale(long now) { return now - updatedAt > 24L * 60 * 60 * 1000; }
    }

    /** Result of reading one subscription body. */
    public static final class Parsed {
        public final List<ProxyProfile> nodes = new ArrayList<>();
        public int unsupported;
    }

    /** Accepts base64 (standard or URL-safe) or plain text; one node link per line. */
    public static Parsed parse(String body) {
        String text = body == null ? "" : body.trim();
        if (!text.contains("://")) {
            try {
                String compact = text.replaceAll("\\s", "");
                String decoded = new String(Base64.getMimeDecoder().decode(
                        compact.replace('-', '+').replace('_', '/')), StandardCharsets.UTF_8);
                if (decoded.contains("://")) text = decoded;
            } catch (IllegalArgumentException ignored) {
                // Not base64; parse as is.
            }
        }
        Parsed parsed = new Parsed();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            String item = line.trim();
            if (item.isEmpty() || item.startsWith("#") || !item.contains("://")) continue;
            String scheme = item.substring(0, item.indexOf("://")).toLowerCase(Locale.ROOT);
            if (!(scheme.equals("vless") || scheme.equals("ss") || scheme.equals("hy2") || scheme.equals("hysteria2"))) {
                parsed.unsupported++;
                continue;
            }
            try {
                ProxyProfile profile = ProxyProfileParser.parse(item);
                if (seen.add(profile.id())) parsed.nodes.add(profile);
            } catch (IllegalArgumentException e) {
                parsed.unsupported++;
            }
        }
        return parsed;
    }

    public static synchronized List<Subscription> list(Context context) {
        ArrayList<Subscription> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                ArrayList<String> ids = new ArrayList<>();
                JSONArray nodes = o.optJSONArray("nodes");
                if (nodes != null) for (int j = 0; j < nodes.length(); j++) ids.add(nodes.getString(j));
                result.add(new Subscription(o.getString("id"), o.optString("name"),
                        ProxyProfileStore.unseal(o.optString("url")), o.optLong("updatedAt"), o.optString("error"), ids));
            }
        } catch (Exception e) {
            AppLogger.log(context, "WARN", "訂閱清單無法讀取=" + e.getClass().getSimpleName());
        }
        return result;
    }

    static synchronized void saveAll(Context context, List<Subscription> subs) {
        JSONArray array = new JSONArray();
        try {
            for (Subscription sub : subs) {
                array.put(new JSONObject().put("id", sub.id).put("name", sub.name)
                        .put("url", ProxyProfileStore.seal(sub.url)).put("updatedAt", sub.updatedAt)
                        .put("error", sub.error).put("nodes", new JSONArray(sub.nodeIds)));
            }
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply();
    }

    public static Subscription add(Context context, String name, String url) {
        String id = TransferServer.b64(TransferCrypto.random(6)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "x");
        Subscription sub = new Subscription(id, name, url.trim(), 0, "", new ArrayList<>());
        List<Subscription> subs = list(context);
        subs.add(sub);
        saveAll(context, subs);
        return sub;
    }

    public static void rename(Context context, String id, String name) {
        List<Subscription> subs = list(context);
        for (int i = 0; i < subs.size(); i++) {
            Subscription s = subs.get(i);
            if (s.id.equals(id)) subs.set(i, new Subscription(s.id, name, s.url, s.updatedAt, s.error, s.nodeIds));
        }
        saveAll(context, subs);
    }

    /** Removes the subscription and, if asked, the nodes it contributed. */
    public static void delete(Context context, String id, boolean removeNodes) {
        List<Subscription> subs = list(context);
        Subscription target = null;
        for (Subscription s : subs) if (s.id.equals(id)) target = s;
        if (target == null) return;
        subs.remove(target);
        saveAll(context, subs);
        if (removeNodes) replaceNodes(context, target.id, target.nodeIds, new ArrayList<>(), subs);
    }

    /** Which subscription a node came from, or null for manual nodes. */
    public static String sourceOf(List<Subscription> subs, String nodeId) {
        for (Subscription s : subs) if (s.nodeIds.contains(nodeId)) return s.name;
        return null;
    }

    /** Outcome of one refresh, for toasts and logs. */
    public static final class Outcome {
        public int added;
        public int removed;
        public int total;
        public int unsupported;
        public String error = "";
    }

    /** Downloads and applies one subscription. Blocking; call off the UI thread. */
    public static Outcome refresh(Context context, Subscription sub) {
        Outcome outcome = new Outcome();
        try {
            Parsed parsed = parse(new String(RuleSetStore.downloadBytes(sub.url), StandardCharsets.UTF_8));
            if (parsed.nodes.isEmpty()) {
                throw new java.io.IOException(parsed.unsupported > 0
                        ? "只有不支援的節點類型（" + parsed.unsupported + " 個）" : "訂閱內容沒有節點");
            }
            synchronized (SubscriptionStore.class) {
                List<Subscription> subs = list(context);
                int[] counts = replaceNodes(context, sub.id, ownedIds(subs, sub.id), parsed.nodes, subs);
                outcome.added = counts[0];
                outcome.removed = counts[1];
                ArrayList<String> ids = new ArrayList<>();
                for (ProxyProfile p : parsed.nodes) ids.add(p.id());
                for (int i = 0; i < subs.size(); i++) {
                    if (subs.get(i).id.equals(sub.id)) {
                        subs.set(i, new Subscription(sub.id, subs.get(i).name, sub.url, System.currentTimeMillis(), "", ids));
                    }
                }
                saveAll(context, subs);
            }
            outcome.total = parsed.nodes.size();
            outcome.unsupported = parsed.unsupported;
            AppLogger.log(context, "INFO", "訂閱已更新=" + sub.id + " 節點=" + outcome.total
                    + " 新增=" + outcome.added + " 移除=" + outcome.removed + " 不支援=" + outcome.unsupported);
        } catch (Exception e) {
            outcome.error = e instanceof java.io.IOException && e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            List<Subscription> subs = list(context);
            for (int i = 0; i < subs.size(); i++) {
                Subscription s = subs.get(i);
                if (s.id.equals(sub.id)) subs.set(i, new Subscription(s.id, s.name, s.url, s.updatedAt, outcome.error, s.nodeIds));
            }
            saveAll(context, subs);
            AppLogger.log(context, "WARN", "訂閱更新失敗=" + sub.id + " " + outcome.error);
        }
        return outcome;
    }

    /**
     * Swaps the nodes a subscription owns. Nodes still claimed by another
     * subscription stay; the node in use keeps its selection if it survives.
     * Returns {added, removed}.
     */
    private static int[] replaceNodes(Context context, String ownerId, List<String> oldIds,
                                      List<ProxyProfile> fresh, List<Subscription> others) {
        ProxyProfile selected = ProxyProfileStore.selected(context);
        LinkedHashSet<String> keep = new LinkedHashSet<>();
        for (Subscription s : others) if (!s.id.equals(ownerId)) keep.addAll(s.nodeIds);
        LinkedHashSet<String> freshIds = new LinkedHashSet<>();
        for (ProxyProfile p : fresh) freshIds.add(p.id());
        ArrayList<ProxyProfile> nodes = new ArrayList<>();
        LinkedHashSet<String> present = new LinkedHashSet<>();
        int removed = 0;
        for (ProxyProfile p : ProxyProfileStore.load(context)) {
            if (oldIds.contains(p.id()) && !freshIds.contains(p.id()) && !keep.contains(p.id())) { removed++; continue; }
            nodes.add(p);
            present.add(p.id());
        }
        int added = 0;
        for (ProxyProfile p : fresh) {
            if (present.add(p.id())) { nodes.add(p); added++; }
        }
        ProxyProfileStore.replace(context, nodes);
        int index = 0;
        if (selected != null) {
            for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).id().equals(selected.id())) index = i;
        }
        ProxyProfileStore.setSelectedIndex(context, index);
        return new int[]{added, removed};
    }

    private static List<String> ownedIds(List<Subscription> subs, String id) {
        for (Subscription s : subs) if (s.id.equals(id)) return s.nodeIds;
        return new ArrayList<>();
    }

    private static String hostOf(String url) {
        try {
            String host = new java.net.URI(url).getHost();
            return host == null ? "訂閱" : host;
        } catch (Exception e) {
            return "訂閱";
        }
    }
}
