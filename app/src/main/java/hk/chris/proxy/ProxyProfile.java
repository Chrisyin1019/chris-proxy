package hk.chris.proxy;

import java.util.Locale;

public final class ProxyProfile {
    public enum Type { VLESS, SHADOWSOCKS, HY2, WIREGUARD }

    public final Type type;
    public final String label;
    public final String rawConfig;

    public ProxyProfile(Type type, String label, String rawConfig) {
        this.type = type;
        this.label = label == null || label.trim().isEmpty() ? type.name() : label.trim();
        this.rawConfig = rawConfig.trim();
    }

    public String summary() {
        return type.name() + " · " + label;
    }

    /**
     * Stable identifier used by routing rules and sing-box outbound tags.
     * Derived from a truncated SHA-256 of the raw config, so it survives
     * reordering and never exposes the node secrets themselves.
     */
    public String id() {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(rawConfig.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Type detect(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("vless://")) return Type.VLESS;
        if (value.startsWith("ss://")) return Type.SHADOWSOCKS;
        if (value.startsWith("hy2://") || value.startsWith("hysteria2://")) return Type.HY2;
        if (value.startsWith("wg://") || value.contains("[interface]")) return Type.WIREGUARD;
        throw new IllegalArgumentException("只接受 VLESS、ss、HY2/Hysteria2 URI 或 WireGuard 設定");
    }
}
