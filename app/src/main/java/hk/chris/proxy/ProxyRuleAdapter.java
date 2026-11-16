package hk.chris.proxy;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Neutral rule layer in front of the existing sing-box agent.
 *
 * The production adapter exposes only enabled rules to {@link SingBoxConfig};
 * the same ordered matcher powers local dry-run. A dry-run sample is never
 * persisted or logged.
 */
final class ProxyRuleAdapter {
    private ProxyRuleAdapter() {}

    static List<RouteRule> enabledRules(Routing routing) {
        if (routing == null || routing.rules.isEmpty()) return Collections.emptyList();
        ArrayList<RouteRule> result = new ArrayList<>();
        for (RouteRule rule : routing.rules) if (rule.enabled) result.add(rule);
        return Collections.unmodifiableList(result);
    }

    static Result dryRun(Routing routing, Sample sample, Map<String, RuleListParser.Parsed> ruleSets) {
        if (routing.mode == Routing.Mode.DIRECT) return Result.finalTarget(RouteRule.TARGET_DIRECT, "全部直連模式");
        if (routing.mode == Routing.Mode.GLOBAL) return Result.finalTarget(RouteRule.TARGET_PROXY, "全局代理模式");
        for (int i = 0; i < routing.rules.size(); i++) {
            RouteRule rule = routing.rules.get(i);
            if (rule.enabled && matches(rule, sample, ruleSets)) return Result.matched(i, rule);
        }
        return Result.finalTarget(routing.finalTarget, "沒有規則符合，使用 FINAL");
    }

    static boolean matches(RouteRule rule, Sample sample, Map<String, RuleListParser.Parsed> ruleSets) {
        switch (rule.match) {
            case DOMAIN: return sample.domain.equals(rule.value);
            case DOMAIN_SUFFIX: return domainSuffix(sample.domain, rule.value);
            case DOMAIN_KEYWORD: return !sample.domain.isEmpty() && sample.domain.contains(rule.value);
            case PACKAGE: return sample.packageName.equals(rule.value);
            case PORT: return portMatches(rule.value, sample.port);
            case IP_CIDR: return cidrMatches(rule.value, sample.ip);
            case RULE_SET: {
                RuleListParser.Parsed set = ruleSets == null ? null : ruleSets.get(rule.value);
                if (set == null) return false;
                if (set.domains.contains(sample.domain)) return true;
                for (String suffix : set.suffixes) if (domainSuffix(sample.domain, suffix)) return true;
                for (String keyword : set.keywords) if (!sample.domain.isEmpty() && sample.domain.contains(keyword)) return true;
                for (String cidr : set.cidrs) if (cidrMatches(cidr, sample.ip)) return true;
                return false;
            }
            default: return false;
        }
    }

    private static boolean domainSuffix(String domain, String suffix) {
        return !domain.isEmpty() && (domain.equals(suffix) || domain.endsWith("." + suffix));
    }

    private static boolean portMatches(String expression, int port) {
        if (port < 1 || port > 65535) return false;
        for (String item : expression.split(",")) {
            String[] range = item.split("-", 2);
            int start = Integer.parseInt(range[0]);
            int end = range.length == 2 ? Integer.parseInt(range[1]) : start;
            if (port >= start && port <= end) return true;
        }
        return false;
    }

    private static boolean cidrMatches(String cidr, String candidate) {
        if (candidate.isEmpty()) return false;
        try {
            String[] parts = cidr.split("/", 2);
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            byte[] address = InetAddress.getByName(candidate).getAddress();
            if (network.length != address.length) return false;
            int prefix = Integer.parseInt(parts[1]);
            int bytes = prefix / 8;
            int bits = prefix % 8;
            for (int i = 0; i < bytes; i++) if (network[i] != address[i]) return false;
            if (bits == 0) return true;
            int mask = 0xff << (8 - bits);
            return (network[bytes] & mask) == (address[bytes] & mask);
        } catch (Exception e) {
            return false;
        }
    }

    static final class Sample {
        final String domain;
        final String ip;
        final String packageName;
        final int port;

        Sample(String domain, String ip, String packageName, int port) {
            this.domain = normalizeDomain(domain);
            this.ip = ip == null ? "" : ip.trim();
            this.packageName = packageName == null ? "" : packageName.trim();
            this.port = port;
        }

        private static String normalizeDomain(String raw) {
            String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (value.startsWith("http://") || value.startsWith("https://")) {
                try {
                    String host = java.net.URI.create(value).getHost();
                    return host == null ? value : host.toLowerCase(Locale.ROOT);
                }
                catch (Exception ignored) { return value; }
            }
            while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
            return value;
        }
    }

    static final class Result {
        final int ruleIndex;
        final RouteRule rule;
        final String target;
        final String explanation;

        private Result(int ruleIndex, RouteRule rule, String target, String explanation) {
            this.ruleIndex = ruleIndex;
            this.rule = rule;
            this.target = target;
            this.explanation = explanation;
        }

        static Result matched(int index, RouteRule rule) {
            return new Result(index, rule, rule.target, "符合第 " + (index + 1) + " 條規則");
        }

        static Result finalTarget(String target, String explanation) {
            return new Result(-1, null, target, explanation);
        }
    }
}
