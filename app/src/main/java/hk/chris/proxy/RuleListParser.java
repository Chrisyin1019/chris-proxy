package hk.chris.proxy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * Reads the domain/IP lists people actually share and normalizes them into
 * four sets. Accepted per line:
 * <ul>
 *   <li>plain {@code example.com}, {@code .example.com}, {@code *.example.com}, or a full URL → suffix</li>
 *   <li>Surge/Clash/Loon classical {@code DOMAIN-SUFFIX,example.com[,policy]} and DOMAIN, DOMAIN-KEYWORD, IP-CIDR(6)</li>
 *   <li>Clash YAML {@code payload:} items, incl. {@code '+.example.com'}</li>
 *   <li>Adblock {@code ||example.com^} and hosts files {@code 0.0.0.0 example.com}</li>
 *   <li>bare IPv4/IPv6 addresses or CIDRs</li>
 * </ul>
 * Anything else (regex, user agents, process rules, allow-list exceptions) is
 * counted as skipped, never guessed at.
 */
public final class RuleListParser {
    public static final int MAX_ENTRIES = 200_000;

    private RuleListParser() {}

    public static final class Parsed {
        public final LinkedHashSet<String> domains = new LinkedHashSet<>();
        public final LinkedHashSet<String> suffixes = new LinkedHashSet<>();
        public final LinkedHashSet<String> keywords = new LinkedHashSet<>();
        public final LinkedHashSet<String> cidrs = new LinkedHashSet<>();
        public int skipped;
        public boolean truncated;

        public int size() {
            return domains.size() + suffixes.size() + keywords.size() + cidrs.size();
        }

        /** Short Chinese breakdown for previews, e.g. "後綴 120 · IP 4". */
        public String breakdown() {
            StringBuilder out = new StringBuilder();
            append(out, "網域後綴", suffixes.size());
            append(out, "完整網域", domains.size());
            append(out, "關鍵字", keywords.size());
            append(out, "IP/CIDR", cidrs.size());
            return out.length() == 0 ? "沒有可用項目" : out.toString();
        }

        private static void append(StringBuilder out, String label, int count) {
            if (count == 0) return;
            if (out.length() > 0) out.append(" · ");
            out.append(label).append(' ').append(count);
        }
    }

    public static Parsed parse(Reader reader) throws IOException {
        Parsed parsed = new Parsed();
        BufferedReader buffered = new BufferedReader(reader);
        String line;
        while ((line = buffered.readLine()) != null) {
            if (parsed.size() >= MAX_ENTRIES) { parsed.truncated = true; break; }
            parseLine(line, parsed);
        }
        return parsed;
    }

    public static Parsed parse(String text) {
        try {
            return parse(new java.io.StringReader(text == null ? "" : text));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void parseLine(String raw, Parsed out) {
        String line = raw.trim();
        if (!line.isEmpty() && line.charAt(0) == 0xFEFF) line = line.substring(1).trim();
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") || line.startsWith("//")
                || line.startsWith(";") || line.startsWith("[")) return;
        if (line.equalsIgnoreCase("payload:")) return;
        // Clash YAML list item.
        if (line.startsWith("- ")) line = line.substring(2).trim();
        line = stripQuotes(line);
        // Trailing inline comment.
        int hash = line.indexOf(" #");
        if (hash > 0) line = line.substring(0, hash).trim();
        if (line.isEmpty()) return;

        if (line.contains(",")) {
            classical(line, out);
            return;
        }
        if (line.startsWith("@@")) { out.skipped++; return; }
        if (line.startsWith("||")) {
            String value = line.substring(2);
            int end = indexOfAny(value, "^/$");
            if (end >= 0) value = value.substring(0, end);
            add(out.suffixes, RouteRule.Match.DOMAIN_SUFFIX, value, out);
            return;
        }
        String[] words = line.split("\\s+");
        if (words.length == 2 && isHostsAddress(words[0])) {
            if (isLocalHostName(words[1])) return;
            add(out.suffixes, RouteRule.Match.DOMAIN_SUFFIX, words[1], out);
            return;
        }
        if (words.length != 1) { out.skipped++; return; }
        if (line.startsWith("+.") || line.startsWith("*.")) {
            add(out.suffixes, RouteRule.Match.DOMAIN_SUFFIX, line.substring(2), out);
        } else if (looksLikeIp(line)) {
            add(out.cidrs, RouteRule.Match.IP_CIDR, line, out);
        } else if (!line.contains(".")) {
            // Single words are usually stray tokens (e.g. "localhost", YAML keys).
            out.skipped++;
        } else {
            // Policy.normalize strips a scheme/path and a leading dot.
            add(out.suffixes, RouteRule.Match.DOMAIN_SUFFIX, line, out);
        }
    }

    private static void classical(String line, Parsed out) {
        String[] parts = line.split(",");
        String kind = parts[0].trim().toUpperCase(Locale.ROOT);
        String value = parts.length > 1 ? stripQuotes(parts[1].trim()) : "";
        switch (kind) {
            case "DOMAIN": add(out.domains, RouteRule.Match.DOMAIN, value, out); break;
            case "DOMAIN-SUFFIX":
            case "HOST-SUFFIX": add(out.suffixes, RouteRule.Match.DOMAIN_SUFFIX, value, out); break;
            case "DOMAIN-KEYWORD":
            case "HOST-KEYWORD": add(out.keywords, RouteRule.Match.DOMAIN_KEYWORD, value, out); break;
            case "IP-CIDR":
            case "IP-CIDR6":
            case "IP6-CIDR": add(out.cidrs, RouteRule.Match.IP_CIDR, value, out); break;
            default: out.skipped++;
        }
    }

    private static void add(LinkedHashSet<String> target, RouteRule.Match match, String value, Parsed out) {
        try {
            target.add(new RouteRule(match, value, RouteRule.TARGET_DIRECT).value);
        } catch (IllegalArgumentException e) {
            out.skipped++;
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && (value.charAt(0) == '\'' || value.charAt(0) == '"')
                && value.charAt(value.length() - 1) == value.charAt(0)) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static int indexOfAny(String value, String chars) {
        for (int i = 0; i < value.length(); i++) if (chars.indexOf(value.charAt(i)) >= 0) return i;
        return -1;
    }

    private static boolean isHostsAddress(String value) {
        return value.equals("0.0.0.0") || value.equals("127.0.0.1") || value.equals("::") || value.equals("::1");
    }

    private static boolean isLocalHostName(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        return v.equals("localhost") || v.equals("localhost.localdomain") || v.equals("local")
                || v.equals("broadcasthost") || v.equals("0.0.0.0") || v.startsWith("ip6-");
    }

    private static boolean looksLikeIp(String value) {
        return value.matches("\\d{1,3}(\\.\\d{1,3}){3}(/\\d{1,2})?")
                || (value.contains(":") && value.matches("[0-9A-Fa-f:.]+(/\\d{1,3})?"));
    }
}
