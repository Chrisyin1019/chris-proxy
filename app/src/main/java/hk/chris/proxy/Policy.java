package hk.chris.proxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * App-wide policy: Wi-Fi networks that bypass the VPN, the ad-block domain
 * list and the in-app private DNS. Per-traffic routing lives in {@link Routing};
 * mode/direct/proxy domains are kept only to migrate old installs.
 */
public final class Policy {
    public enum Mode {
        DIRECT_WHITELIST, // 白名單：清單內直連，其餘代理
        PROXY_BLACKLIST,  // 黑名單：清單內代理，其餘直連
        CUSTOM            // 自定義：逐條指定，未命中預設直連
    }

    public final Mode mode;
    public final Set<String> directSsids;
    public final Set<String> directDomains;
    public final Set<String> proxyDomains;
    public final Set<String> blockedDomains;
    public final String privateDns;

    public Policy(Mode mode,
                  Set<String> directSsids,
                  Set<String> directDomains,
                  Set<String> proxyDomains,
                  Set<String> blockedDomains) {
        this(mode, directSsids, directDomains, proxyDomains, blockedDomains, "");
    }

    public Policy(Mode mode,
                  Set<String> directSsids,
                  Set<String> directDomains,
                  Set<String> proxyDomains,
                  Set<String> blockedDomains,
                  String privateDns) {
        this.mode = mode == null ? Mode.PROXY_BLACKLIST : mode;
        this.directSsids = immutableNormalized(directSsids, false);
        this.directDomains = immutableNormalized(directDomains, true);
        this.proxyDomains = immutableNormalized(proxyDomains, true);
        this.blockedDomains = immutableNormalized(blockedDomains, true);
        this.privateDns = privateDns == null ? "" : privateDns.trim();
    }

    public static Policy defaults() {
        return new Policy(Mode.PROXY_BLACKLIST,
                new LinkedHashSet<>(), new LinkedHashSet<>(),
                new LinkedHashSet<>(), new LinkedHashSet<>(), "");
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String s = value.trim().toLowerCase(Locale.ROOT);
        while (s.startsWith(".")) s = s.substring(1);
        if (s.startsWith("http://")) s = s.substring(7);
        if (s.startsWith("https://")) s = s.substring(8);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        return s;
    }

    private static Set<String> immutableNormalized(Set<String> source, boolean domain) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (source != null) {
            for (String item : source) {
                String normalized = domain ? normalize(item) : item == null ? "" : item.trim();
                if (!normalized.isEmpty() && !normalized.startsWith("#")) result.add(normalized);
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
