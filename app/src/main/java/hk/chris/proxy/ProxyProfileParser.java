package hk.chris.proxy;

import java.util.ArrayList;
import java.util.List;

public final class ProxyProfileParser {
    private ProxyProfileParser() {}

    public static ProxyProfile parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("代理內容不可為空");
        String normalized = raw.trim();
        ProxyProfile.Type type = ProxyProfile.detect(normalized);
        String label = type.name();
        int hash = normalized.indexOf('#');
        if (hash >= 0 && hash + 1 < normalized.length()) {
            String fragment = normalized.substring(hash + 1).trim();
            if (!fragment.isEmpty() && !fragment.contains("\n")) label = decodeLabel(fragment);
        }
        return new ProxyProfile(type, label, normalized);
    }

    /** Subscription links percent-encode names (e.g. #%E6%97%A5%E6%9C%AC); show them readable. */
    static String decodeLabel(String fragment) {
        if (!fragment.contains("%")) return fragment;
        try {
            return java.net.URLDecoder.decode(fragment.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return fragment;
        }
    }

    public static List<ProxyProfile> parseMany(String raw) {
        ArrayList<ProxyProfile> result = new ArrayList<>();
        if (raw == null) return result;
        StringBuilder wireguard = new StringBuilder();
        for (String line : raw.split("\\R")) {
            if (line.trim().equalsIgnoreCase("[Interface]") || line.trim().equalsIgnoreCase("[Peer]") ||
                    (wireguard.length() > 0 && !line.trim().isEmpty())) {
                wireguard.append(line).append('\n');
            } else if (line.trim().matches("(?i)(vless|ss|hy2|hysteria2)://.*")) {
                result.add(parse(line.trim()));
            }
        }
        if (wireguard.length() > 0) result.add(parse(wireguard.toString()));
        if (result.isEmpty()) result.add(parse(raw));
        return result;
    }
}
