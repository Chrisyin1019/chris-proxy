package hk.chris.proxy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/** Stable rules-only format used by local version history. It never contains node secrets. */
final class RoutingCodec {
    private RoutingCodec() {}

    static JSONObject toJson(Routing routing) throws JSONException {
        JSONArray rules = new JSONArray();
        for (RouteRule rule : routing.rules) rules.put(rule.serialize());
        return new JSONObject()
                .put("mode", routing.mode.name())
                .put("final", routing.finalTarget)
                .put("rules", rules);
    }

    static String encode(Routing routing) {
        try {
            return toJson(routing).toString();
        } catch (JSONException e) {
            throw new IllegalStateException("無法保存分流規則", e);
        }
    }

    static Routing decode(String raw) {
        try {
            JSONObject object = new JSONObject(raw);
            Routing.Mode mode;
            try {
                mode = Routing.Mode.valueOf(object.optString("mode", Routing.Mode.RULE.name()));
            } catch (IllegalArgumentException e) {
                mode = Routing.Mode.RULE;
            }
            ArrayList<RouteRule> rules = new ArrayList<>();
            JSONArray array = object.optJSONArray("rules");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    try {
                        rules.add(RouteRule.deserialize(array.getString(i)));
                    } catch (IllegalArgumentException ignored) {
                        // Forward compatibility: one unknown rule must not lose the whole snapshot.
                    }
                }
            }
            String target;
            try {
                target = RouteRule.normalizeTarget(object.optString("final", RouteRule.TARGET_DIRECT));
            } catch (IllegalArgumentException e) {
                target = RouteRule.TARGET_DIRECT;
            }
            return new Routing(mode, rules, target);
        } catch (JSONException e) {
            throw new IllegalArgumentException("規則版本格式錯誤", e);
        }
    }
}
