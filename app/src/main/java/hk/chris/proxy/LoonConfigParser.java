package hk.chris.proxy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Imports the useful, non-MITM subset of a Loon/Surge-style [Rule] section as
 * ordered rules. Named policies other than DIRECT/REJECT map to the node in
 * use; scripts, rewrites, MITM and remote rule sets are ignored.
 */
public final class LoonConfigParser {
    private LoonConfigParser() {}

    public static final class Result {
        public final List<RouteRule> rules;
        public final String finalTarget;
        public final int skipped;

        Result(List<RouteRule> rules, String finalTarget, int skipped) {
            this.rules = rules;
            this.finalTarget = finalTarget;
            this.skipped = skipped;
        }
    }

    public static Result parse(Reader reader) throws IOException {
        ArrayList<RouteRule> rules = new ArrayList<>();
        String finalTarget = null;
        int skipped = 0;
        String section = "";
        BufferedReader buffered = new BufferedReader(reader);
        String line;
        while ((line = buffered.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("//")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                continue;
            }
            if ("host".equals(section)) {
                String[] parts = line.split("=", 2);
                if (parts.length == 2 && "direct".equalsIgnoreCase(parts[1].trim())) {
                    try {
                        rules.add(new RouteRule(RouteRule.Match.DOMAIN, parts[0], RouteRule.TARGET_DIRECT));
                    } catch (IllegalArgumentException e) {
                        skipped++;
                    }
                }
                continue;
            }
            if (!"rule".equals(section)) continue;
            String[] parts = line.split(",");
            String kind = parts[0].trim().toUpperCase(Locale.ROOT);
            if ("FINAL".equals(kind) && parts.length >= 2) {
                finalTarget = target(parts[1]);
                continue;
            }
            if (parts.length < 3) { skipped++; continue; }
            RouteRule.Match match = match(kind);
            if (match == null) { skipped++; continue; }
            try {
                rules.add(new RouteRule(match, parts[1], target(parts[2])));
            } catch (IllegalArgumentException e) {
                skipped++;
            }
        }
        return new Result(rules, finalTarget, skipped);
    }

    private static RouteRule.Match match(String kind) {
        switch (kind) {
            case "DOMAIN": return RouteRule.Match.DOMAIN;
            case "DOMAIN-SUFFIX": return RouteRule.Match.DOMAIN_SUFFIX;
            case "DOMAIN-KEYWORD": return RouteRule.Match.DOMAIN_KEYWORD;
            case "IP-CIDR":
            case "IP-CIDR6": return RouteRule.Match.IP_CIDR;
            case "DST-PORT":
            case "DEST-PORT": return RouteRule.Match.PORT;
            default: return null;
        }
    }

    private static String target(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if ("DIRECT".equals(value)) return RouteRule.TARGET_DIRECT;
        if (value.startsWith("REJECT")) return RouteRule.TARGET_BLOCK;
        return RouteRule.TARGET_PROXY;
    }
}
