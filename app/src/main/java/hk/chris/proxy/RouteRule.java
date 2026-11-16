package hk.chris.proxy;

import java.util.Locale;

/**
 * One ordered, user-defined routing rule: "traffic matching X goes to target Y".
 * Targets are DIRECT, BLOCK, PROXY (the node currently in use) or a specific
 * saved node referenced by its stable id, so different traffic can leave via
 * different upstream proxies.
 */
public final class RouteRule {
    public enum Match {
        DOMAIN("完整網域"),
        DOMAIN_SUFFIX("網域後綴"),
        DOMAIN_KEYWORD("網域關鍵字"),
        IP_CIDR("IP/CIDR"),
        PACKAGE("App 套件名"),
        PORT("目的埠"),
        RULE_SET("規則集");

        public final String label;
        Match(String label) { this.label = label; }
    }

    public static final String TARGET_DIRECT = "DIRECT";
    public static final String TARGET_BLOCK = "BLOCK";
    public static final String TARGET_PROXY = "PROXY";
    private static final String NODE_PREFIX = "node:";

    public final Match match;
    public final String value;
    public final String target;
    /** Disabled rules stay in their ordered position but are not sent to the proxy core. */
    public final boolean enabled;

    public RouteRule(Match match, String value, String target) {
        this(match, value, target, true);
    }

    public RouteRule(Match match, String value, String target, boolean enabled) {
        if (match == null) throw new IllegalArgumentException("缺少比對類型");
        this.match = match;
        this.value = normalizeValue(match, value);
        this.target = normalizeTarget(target);
        this.enabled = enabled;
    }

    public static String nodeTarget(String nodeId) {
        return NODE_PREFIX + nodeId;
    }

    /** Returns the node id when the target is a specific node, otherwise null. */
    public String nodeId() {
        return target.startsWith(NODE_PREFIX) ? target.substring(NODE_PREFIX.length()) : null;
    }

    public String serialize() {
        return match.name() + "\t" + value + "\t" + target + "\t" + (enabled ? "1" : "0");
    }

    public static RouteRule deserialize(String line) {
        String[] parts = line.split("\t", -1);
        // Three-column records were written by <= 1.2.0 and are enabled by default.
        if (parts.length != 3 && parts.length != 4) throw new IllegalArgumentException("規則格式錯誤");
        boolean enabled = parts.length == 3 || "1".equals(parts[3]) || "true".equalsIgnoreCase(parts[3]);
        return new RouteRule(Match.valueOf(parts[0]), parts[1], parts[2], enabled);
    }

    public RouteRule withEnabled(boolean value) {
        return new RouteRule(match, this.value, target, value);
    }

    private static String normalizeValue(Match match, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("規則內容不可為空");
        if (value.contains("\t") || value.contains("\n")) throw new IllegalArgumentException("規則內容含有非法字元");
        switch (match) {
            case DOMAIN:
            case DOMAIN_SUFFIX: {
                String domain = Policy.normalize(value);
                if (!domain.matches("[a-z0-9_*-]+(\\.[a-z0-9_-]+)*")) throw new IllegalArgumentException("網域格式錯誤：" + value);
                return domain;
            }
            case DOMAIN_KEYWORD: {
                String keyword = value.toLowerCase(Locale.ROOT);
                if (keyword.contains(" ")) throw new IllegalArgumentException("關鍵字不可含空白");
                return keyword;
            }
            case IP_CIDR:
                return normalizeCidr(value);
            case PACKAGE:
                if (!value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) {
                    throw new IllegalArgumentException("App 套件名格式錯誤：" + value);
                }
                return value;
            case PORT:
                return normalizePorts(value);
            case RULE_SET:
                if (!value.matches("[0-9a-f]{12}")) throw new IllegalArgumentException("規則集 ID 錯誤");
                return value;
            default:
                throw new IllegalArgumentException("未支援的比對類型");
        }
    }

    private static String normalizeCidr(String value) {
        String[] parts = value.split("/", 2);
        String address = parts[0];
        boolean v6 = address.contains(":");
        if (!v6 && !address.matches("\\d{1,3}(\\.\\d{1,3}){3}")) throw new IllegalArgumentException("IP 格式錯誤：" + value);
        if (!v6) {
            for (String octet : address.split("\\.")) {
                if (Integer.parseInt(octet) > 255) throw new IllegalArgumentException("IP 格式錯誤：" + value);
            }
        } else if (!address.matches("[0-9A-Fa-f:.]+")) {
            throw new IllegalArgumentException("IPv6 格式錯誤：" + value);
        }
        int max = v6 ? 128 : 32;
        int prefix = max;
        if (parts.length == 2) {
            try {
                prefix = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("CIDR 前綴錯誤：" + value);
            }
            if (prefix < 0 || prefix > max) throw new IllegalArgumentException("CIDR 前綴錯誤：" + value);
        }
        return address.toLowerCase(Locale.ROOT) + "/" + prefix;
    }

    /** Accepts "443", "8000-8080" or "80,443". */
    private static String normalizePorts(String value) {
        StringBuilder out = new StringBuilder();
        for (String item : value.split(",")) {
            String part = item.trim();
            String[] range = part.split("-", 2);
            int start = port(range[0], value);
            int end = range.length == 2 ? port(range[1], value) : start;
            if (end < start) throw new IllegalArgumentException("埠範圍錯誤：" + value);
            if (out.length() > 0) out.append(',');
            out.append(range.length == 2 ? start + "-" + end : String.valueOf(start));
        }
        return out.toString();
    }

    private static int port(String raw, String whole) {
        try {
            int port = Integer.parseInt(raw.trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("埠號錯誤：" + whole);
        }
    }

    static String normalizeTarget(String raw) {
        String target = raw == null ? "" : raw.trim();
        if (TARGET_DIRECT.equals(target) || TARGET_BLOCK.equals(target) || TARGET_PROXY.equals(target)) return target;
        if (target.startsWith(NODE_PREFIX) && target.length() > NODE_PREFIX.length()
                && target.substring(NODE_PREFIX.length()).matches("[0-9a-f]+")) {
            return target;
        }
        throw new IllegalArgumentException("規則目標錯誤");
    }
}
