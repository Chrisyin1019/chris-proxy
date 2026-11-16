package hk.chris.proxy;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

public final class PolicyStore {
    private static final String PREFS = "policy";
    private static final String MODE = "mode";
    private static final String SSIDS = "direct_ssids";
    private static final String DIRECT = "direct_domains";
    private static final String PROXY = "proxy_domains";
    private static final String BLOCKED = "blocked_domains";
    private static final String PRIVATE_DNS = "private_dns";

    private PolicyStore() {}

    public static Policy load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Policy.Mode mode;
        try {
            mode = Policy.Mode.valueOf(p.getString(MODE, "PROXY_BLACKLIST"));
        } catch (Exception ignored) {
            mode = Policy.Mode.PROXY_BLACKLIST;
        }
        return new Policy(mode, readSet(p, SSIDS), readSet(p, DIRECT),
                readSet(p, PROXY), readSet(p, BLOCKED), p.getString(PRIVATE_DNS, ""));
    }

    public static void save(Context context, Policy policy) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(MODE, policy.mode.name())
                .putString(SSIDS, join(policy.directSsids))
                .putString(DIRECT, join(policy.directDomains))
                .putString(PROXY, join(policy.proxyDomains))
                .putString(BLOCKED, join(policy.blockedDomains))
                .putString(PRIVATE_DNS, policy.privateDns)
                .apply();
    }

    private static Set<String> readSet(SharedPreferences p, String key) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        String raw = p.getString(key, "");
        if (raw != null) {
            for (String item : raw.split("\\n")) {
                if (!item.trim().isEmpty()) values.add(item.trim());
            }
        }
        return values;
    }

    private static String join(Set<String> values) {
        return String.join("\n", values);
    }
}
