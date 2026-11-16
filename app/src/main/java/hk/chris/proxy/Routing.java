package hk.chris.proxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Surge-style routing state: an outbound mode, an ordered rule list where the
 * first match wins, and a FINAL target for everything that matched nothing.
 */
public final class Routing {
    public enum Mode {
        RULE("規則模式"),
        GLOBAL("全局代理"),
        DIRECT("全部直連");

        public final String label;
        Mode(String label) { this.label = label; }
    }

    public final Mode mode;
    public final List<RouteRule> rules;
    public final String finalTarget;

    public Routing(Mode mode, List<RouteRule> rules, String finalTarget) {
        this.mode = mode == null ? Mode.RULE : mode;
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules == null ? Collections.emptyList() : rules));
        this.finalTarget = RouteRule.normalizeTarget(
                finalTarget == null ? RouteRule.TARGET_DIRECT : finalTarget);
    }

    public static Routing defaults() {
        return new Routing(Mode.RULE, new ArrayList<>(), RouteRule.TARGET_DIRECT);
    }

    public Routing withMode(Mode value) { return new Routing(value, rules, finalTarget); }
    public Routing withRules(List<RouteRule> value) { return new Routing(mode, value, finalTarget); }
    public Routing withFinal(String value) { return new Routing(mode, rules, value); }

    /** Converts the old whitelist/blacklist domain sets into ordered rules. */
    static Routing fromLegacy(Policy policy) {
        ArrayList<RouteRule> rules = new ArrayList<>();
        for (String domain : policy.directDomains) {
            rules.add(new RouteRule(RouteRule.Match.DOMAIN_SUFFIX, domain, RouteRule.TARGET_DIRECT));
        }
        for (String domain : policy.proxyDomains) {
            rules.add(new RouteRule(RouteRule.Match.DOMAIN_SUFFIX, domain, RouteRule.TARGET_PROXY));
        }
        String fallback = policy.mode == Policy.Mode.DIRECT_WHITELIST
                ? RouteRule.TARGET_PROXY : RouteRule.TARGET_DIRECT;
        return new Routing(Mode.RULE, rules, fallback);
    }
}
