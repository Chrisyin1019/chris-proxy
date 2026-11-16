package hk.chris.proxy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The whole configuration as one JSON document for phone-to-phone transfer:
 * nodes, the node in use, outbound mode, ordered rules, FINAL, rule sets
 * (with their entries), Wi-Fi exceptions, private DNS, ad-block list and
 * theme. It only ever leaves the app encrypted (live session or sealed file).
 */
public final class TransferBundle {
    static final String FORMAT = "chris-proxy-transfer";
    static final int VERSION = 1;

    private TransferBundle() {}

    public static String build(Context context) {
        try {
            JSONObject root = new JSONObject().put("format", FORMAT).put("version", VERSION)
                    .put("createdAt", System.currentTimeMillis());
            JSONArray nodes = new JSONArray();
            for (ProxyProfile profile : ProxyProfileStore.load(context)) nodes.put(profile.rawConfig);
            root.put("nodes", nodes);
            ProxyProfile selected = ProxyProfileStore.selected(context);
            root.put("selected", selected == null ? "" : selected.id());

            Routing routing = RoutingStore.load(context);
            JSONArray rules = new JSONArray();
            for (RouteRule rule : routing.rules) rules.put(rule.serialize());
            root.put("routing", new JSONObject().put("mode", routing.mode.name())
                    .put("final", routing.finalTarget).put("rules", rules));

            Policy policy = PolicyStore.load(context);
            root.put("policy", new JSONObject()
                    .put("ssids", new JSONArray(policy.directSsids))
                    .put("privateDns", policy.privateDns)
                    .put("blocked", new JSONArray(policy.blockedDomains)));

            JSONArray sets = new JSONArray();
            for (RuleSet set : RuleSetStore.list(context)) {
                sets.put(new JSONObject().put("id", set.id).put("name", set.name).put("url", set.url)
                        .put("updatedAt", set.updatedAt).put("entries", RuleSetStore.readRaw(context, set.id)));
            }
            root.put("ruleSets", sets);
            JSONArray subs = new JSONArray();
            for (SubscriptionStore.Subscription sub : SubscriptionStore.list(context)) {
                subs.put(new JSONObject().put("id", sub.id).put("name", sub.name).put("url", sub.url)
                        .put("updatedAt", sub.updatedAt).put("nodes", new JSONArray(sub.nodeIds)));
            }
            root.put("subscriptions", subs);
            root.put("ui", new JSONObject().put("theme",
                    context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("theme_mode", "system")));
            return root.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What the importing phone shows before anything is written. */
    public static final class Summary {
        public final int nodes;
        public final int rules;
        public final int ruleSets;
        public final int ssids;
        public final int blocked;
        public final boolean privateDns;
        public final String mode;

        Summary(JSONObject root) throws JSONException {
            nodes = root.getJSONArray("nodes").length();
            JSONObject routing = root.getJSONObject("routing");
            rules = routing.getJSONArray("rules").length();
            ruleSets = root.optJSONArray("ruleSets") == null ? 0 : root.getJSONArray("ruleSets").length();
            JSONObject policy = root.getJSONObject("policy");
            ssids = policy.getJSONArray("ssids").length();
            blocked = policy.optJSONArray("blocked") == null ? 0 : policy.getJSONArray("blocked").length();
            privateDns = !policy.optString("privateDns").isEmpty();
            Routing.Mode parsed;
            try { parsed = Routing.Mode.valueOf(routing.optString("mode")); } catch (IllegalArgumentException e) { parsed = Routing.Mode.RULE; }
            mode = parsed.label;
        }
    }

    public static JSONObject parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (!FORMAT.equals(root.optString("format")) || root.optInt("version") > VERSION) {
            throw new JSONException("不是這個版本能讀取的 Chris Proxy 設定");
        }
        return root;
    }

    public static Summary summarize(JSONObject root) throws JSONException {
        return new Summary(root);
    }

    /**
     * Writes an imported bundle. Replace makes this phone identical to the
     * source; merge only adds nodes, rules, sets, Wi-Fi exceptions and blocked
     * domains that are not here yet and keeps local mode, FINAL and DNS.
     */
    public static void apply(Context context, JSONObject root, boolean replace) throws Exception {
        List<ProxyProfile> nodes = replace ? new ArrayList<>() : new ArrayList<>(ProxyProfileStore.load(context));
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ProxyProfile p : nodes) ids.add(p.id());
        JSONArray rawNodes = root.getJSONArray("nodes");
        for (int i = 0; i < rawNodes.length(); i++) {
            try {
                ProxyProfile profile = ProxyProfileParser.parse(rawNodes.getString(i));
                if (ids.add(profile.id())) nodes.add(profile);
            } catch (IllegalArgumentException ignored) {
                // Skip a node this version cannot read rather than abort the whole import.
            }
        }
        ProxyProfileStore.replace(context, nodes);
        String selected = root.optString("selected");
        if (replace) {
            for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).id().equals(selected)) ProxyProfileStore.setSelectedIndex(context, i);
        }

        if (replace) RuleSetStore.deleteAll(context);
        JSONArray sets = root.optJSONArray("ruleSets");
        LinkedHashSet<String> existingSets = new LinkedHashSet<>();
        for (RuleSet set : RuleSetStore.list(context)) existingSets.add(set.id);
        if (sets != null) {
            for (int i = 0; i < sets.length(); i++) {
                JSONObject o = sets.getJSONObject(i);
                if (existingSets.contains(o.getString("id"))) continue;
                RuleSetStore.restore(context, new RuleSet(o.getString("id"), o.optString("name"),
                        o.optString("url"), 0, o.optLong("updatedAt"), ""), o.optString("entries"));
            }
        }

        JSONArray subsJson = root.optJSONArray("subscriptions");
        if (subsJson != null) {
            List<SubscriptionStore.Subscription> subs = replace ? new ArrayList<>() : SubscriptionStore.list(context);
            LinkedHashSet<String> known = new LinkedHashSet<>();
            for (SubscriptionStore.Subscription sub : subs) known.add(sub.id);
            for (int i = 0; i < subsJson.length(); i++) {
                JSONObject o = subsJson.getJSONObject(i);
                if (!known.add(o.getString("id"))) continue;
                ArrayList<String> owned = new ArrayList<>();
                JSONArray n = o.optJSONArray("nodes");
                if (n != null) for (int j = 0; j < n.length(); j++) owned.add(n.getString(j));
                subs.add(new SubscriptionStore.Subscription(o.getString("id"), o.optString("name"),
                        o.optString("url"), o.optLong("updatedAt"), "", owned));
            }
            SubscriptionStore.saveAll(context, subs);
        } else if (replace) {
            SubscriptionStore.saveAll(context, new ArrayList<>());
        }

        JSONObject routingJson = root.getJSONObject("routing");
        Routing current = RoutingStore.load(context);
        ArrayList<RouteRule> rules = replace ? new ArrayList<>() : new ArrayList<>(current.rules);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (RouteRule rule : rules) seen.add(rule.serialize());
        JSONArray rawRules = routingJson.getJSONArray("rules");
        for (int i = 0; i < rawRules.length(); i++) {
            try {
                RouteRule rule = RouteRule.deserialize(rawRules.getString(i));
                if (seen.add(rule.serialize())) rules.add(rule);
            } catch (IllegalArgumentException ignored) {
                // Unknown rule type from a newer version.
            }
        }
        Routing routing;
        if (replace) {
            Routing.Mode mode;
            try { mode = Routing.Mode.valueOf(routingJson.optString("mode")); } catch (IllegalArgumentException e) { mode = Routing.Mode.RULE; }
            String fallback;
            try { fallback = RouteRule.normalizeTarget(routingJson.optString("final")); } catch (IllegalArgumentException e) { fallback = RouteRule.TARGET_DIRECT; }
            routing = new Routing(mode, rules, fallback);
        } else {
            routing = current.withRules(rules);
        }
        RoutingStore.save(context, routing);

        JSONObject policyJson = root.getJSONObject("policy");
        Policy policy = PolicyStore.load(context);
        LinkedHashSet<String> ssids = replace ? new LinkedHashSet<>() : new LinkedHashSet<>(policy.directSsids);
        JSONArray rawSsids = policyJson.getJSONArray("ssids");
        for (int i = 0; i < rawSsids.length(); i++) ssids.add(rawSsids.getString(i));
        LinkedHashSet<String> blocked = replace ? new LinkedHashSet<>() : new LinkedHashSet<>(policy.blockedDomains);
        JSONArray rawBlocked = policyJson.optJSONArray("blocked");
        if (rawBlocked != null) for (int i = 0; i < rawBlocked.length(); i++) blocked.add(rawBlocked.getString(i));
        String dns = replace || policy.privateDns.isEmpty() ? policyJson.optString("privateDns") : policy.privateDns;
        PolicyStore.save(context, new Policy(Policy.Mode.PROXY_BLACKLIST, ssids, new LinkedHashSet<>(),
                new LinkedHashSet<>(), blocked, dns));

        if (replace) {
            JSONObject ui = root.optJSONObject("ui");
            if (ui != null) {
                context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit()
                        .putString("theme_mode", ui.optString("theme", "system")).apply();
            }
        }
        AppLogger.log(context, "INFO", "設定已匯入（" + (replace ? "取代" : "合併") + "）節點=" + nodes.size()
                + " 規則=" + rules.size());
    }
}
