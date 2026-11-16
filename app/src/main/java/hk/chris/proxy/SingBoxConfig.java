package hk.chris.proxy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts saved nodes, the Surge-style rule list and app policy into a
 * sing-box JSON config. Every saved node becomes its own outbound
 * ("node-&lt;id&gt;"), and "proxy" is a selector defaulting to the node in use,
 * so a rule can send specific traffic through a specific upstream proxy.
 */
final class SingBoxConfig {
    static final String DIRECT = "direct";
    static final String PROXY = "proxy";

    private SingBoxConfig() {}

    static String build(List<ProxyProfile> profiles, ProxyProfile selected, Policy policy, Routing routing) {
        return build(profiles, selected, policy, routing, java.util.Collections.emptyMap());
    }

    static String build(List<ProxyProfile> profiles, ProxyProfile selected, Policy policy, Routing routing,
                        Map<String, RuleListParser.Parsed> ruleSets) {
        if (profiles.isEmpty() || selected == null) throw new IllegalArgumentException("沒有可用節點");
        try {
            JSONObject root = new JSONObject();
            root.put("log", new JSONObject().put("level", "info").put("timestamp", true));
            root.put("inbounds", new JSONArray().put(new JSONObject()
                    .put("type", "tun")
                    .put("tag", "tun-in")
                    .put("address", new JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
                    .put("auto_route", true)
                    .put("strict_route", false)
                    .put("stack", "gvisor")));

            JSONArray outbounds = new JSONArray();
            JSONArray endpoints = new JSONArray();
            JSONArray selectorMembers = new JSONArray();
            Set<String> nodeIds = new LinkedHashSet<>();
            for (ProxyProfile profile : profiles) {
                String id = profile.id();
                if (!nodeIds.add(id)) continue;
                JSONObject node = outbound(profile, nodeTag(id));
                if (profile.type == ProxyProfile.Type.WIREGUARD) endpoints.put(node); else outbounds.put(node);
                selectorMembers.put(nodeTag(id));
            }
            outbounds.put(new JSONObject()
                    .put("type", "selector")
                    .put("tag", PROXY)
                    .put("outbounds", selectorMembers)
                    .put("default", nodeTag(selected.id())));
            outbounds.put(new JSONObject().put("type", "direct").put("tag", DIRECT));
            root.put("outbounds", outbounds);
            if (endpoints.length() > 0) root.put("endpoints", endpoints);

            root.put("route", route(policy, routing, nodeIds, ruleSets));
            root.put("dns", splitDns(policy, routing, nodeIds, ruleSets));
            return root.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("無法產生 sing-box 設定", e);
        }
    }

    /**
     * Config for the in-app latency engine: every node as an outbound plus the
     * "proxy" selector, no inbounds and no TUN, so it touches no other traffic.
     */
    static String buildTestConfig(List<ProxyProfile> profiles) {
        if (profiles.isEmpty()) throw new IllegalArgumentException("沒有可用節點");
        try {
            JSONObject root = new JSONObject();
            // debug: the URL-test failure reason ("unavailable: …") is a debug line. Not written to disk.
            root.put("log", new JSONObject().put("level", "debug"));
            root.put("dns", new JSONObject()
                    .put("servers", new JSONArray().put(new JSONObject().put("type", "local").put("tag", "local")))
                    .put("final", "local"));
            JSONArray outbounds = new JSONArray();
            JSONArray endpoints = new JSONArray();
            JSONArray members = new JSONArray();
            Set<String> ids = new LinkedHashSet<>();
            for (ProxyProfile profile : profiles) {
                if (!ids.add(profile.id())) continue;
                JSONObject node = outbound(profile, nodeTag(profile.id()));
                if (profile.type == ProxyProfile.Type.WIREGUARD) endpoints.put(node); else outbounds.put(node);
                members.put(nodeTag(profile.id()));
            }
            outbounds.put(new JSONObject().put("type", "selector").put("tag", PROXY).put("outbounds", members));
            outbounds.put(new JSONObject().put("type", "direct").put("tag", DIRECT));
            root.put("outbounds", outbounds);
            if (endpoints.length() > 0) root.put("endpoints", endpoints);
            root.put("route", new JSONObject().put("auto_detect_interface", true)
                    .put("default_domain_resolver", "local").put("final", DIRECT));
            return root.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static String nodeTag(String id) {
        return "node-" + id;
    }

    private static JSONObject route(Policy policy, Routing routing, Set<String> nodeIds,
                                    Map<String, RuleListParser.Parsed> ruleSets) throws JSONException {
        JSONArray rules = new JSONArray();
        // Sniff first so domain rules also match connections made to raw IPs.
        rules.put(new JSONObject().put("action", "sniff"));
        rules.put(new JSONObject().put("protocol", "dns").put("action", "hijack-dns"));
        // LAN, router admin pages and printers never leave through a proxy.
        rules.put(new JSONObject().put("ip_is_private", true).put("action", "route").put("outbound", DIRECT));
        // Wi-Fi exceptions: on these networks everything goes direct, in every mode.
        // The VPN stays up, so leaving the Wi-Fi switches back to proxying by itself.
        if (!policy.directSsids.isEmpty()) {
            rules.put(new JSONObject().put("wifi_ssid", new JSONArray(policy.directSsids))
                    .put("action", "route").put("outbound", DIRECT));
        }

        String finalOutbound;
        switch (routing.mode) {
            case DIRECT:
                finalOutbound = DIRECT;
                break;
            case GLOBAL:
                addBlocklist(rules, policy);
                finalOutbound = PROXY;
                break;
            default:
                addBlocklist(rules, policy);
                for (RouteRule rule : ProxyRuleAdapter.enabledRules(routing)) {
                    JSONObject json = rule(rule, nodeIds, ruleSets);
                    if (json != null) rules.put(json);
                }
                finalOutbound = outboundFor(routing.finalTarget, nodeIds);
                if (finalOutbound == null) {
                    // FINAL = BLOCK: everything unmatched is rejected.
                    rules.put(new JSONObject().put("action", "reject"));
                    finalOutbound = DIRECT;
                }
        }
        return new JSONObject()
                .put("auto_detect_interface", true)
                .put("default_domain_resolver", "local")
                .put("rules", rules)
                .put("final", finalOutbound);
    }

    private static void addBlocklist(JSONArray rules, Policy policy) throws JSONException {
        if (policy.blockedDomains.isEmpty()) return;
        // One rule with an array: sing-box builds a suffix trie, thousands of
        // single-domain rules would be evaluated linearly.
        rules.put(new JSONObject()
                .put("domain_suffix", new JSONArray(policy.blockedDomains))
                .put("action", "reject"));
    }

    /** Returns null when the rule can never match (e.g. an empty or missing rule set). */
    static JSONObject rule(RouteRule rule, Set<String> nodeIds,
                           Map<String, RuleListParser.Parsed> ruleSets) throws JSONException {
        JSONObject json = new JSONObject();
        switch (rule.match) {
            case RULE_SET: {
                RuleListParser.Parsed set = ruleSets.get(rule.value);
                if (set == null || set.size() == 0) return null;
                // Fields inside one sing-box rule are OR-ed across domain/ip
                // types, so one rule matches "any entry of the list".
                if (!set.domains.isEmpty()) json.put("domain", new JSONArray(set.domains));
                if (!set.suffixes.isEmpty()) json.put("domain_suffix", new JSONArray(set.suffixes));
                if (!set.keywords.isEmpty()) json.put("domain_keyword", new JSONArray(set.keywords));
                if (!set.cidrs.isEmpty()) json.put("ip_cidr", new JSONArray(set.cidrs));
                break;
            }
            case DOMAIN: json.put("domain", new JSONArray().put(rule.value)); break;
            case DOMAIN_SUFFIX: json.put("domain_suffix", new JSONArray().put(rule.value)); break;
            case DOMAIN_KEYWORD: json.put("domain_keyword", new JSONArray().put(rule.value)); break;
            case IP_CIDR: json.put("ip_cidr", new JSONArray().put(rule.value)); break;
            case PACKAGE: json.put("package_name", new JSONArray().put(rule.value)); break;
            case PORT: {
                JSONArray ports = new JSONArray();
                JSONArray ranges = new JSONArray();
                for (String item : rule.value.split(",")) {
                    if (item.contains("-")) ranges.put(item.replace('-', ':'));
                    else ports.put(Integer.parseInt(item));
                }
                if (ports.length() > 0) json.put("port", ports);
                if (ranges.length() > 0) json.put("port_range", ranges);
                break;
            }
            default: throw new IllegalArgumentException("未支援的規則類型");
        }
        String outbound = outboundFor(rule.target, nodeIds);
        if (outbound == null) return json.put("action", "reject");
        return json.put("action", "route").put("outbound", outbound);
    }

    /** Returns the outbound tag for a rule target, or null for BLOCK. */
    static String outboundFor(String target, Set<String> nodeIds) {
        if (RouteRule.TARGET_BLOCK.equals(target)) return null;
        if (RouteRule.TARGET_DIRECT.equals(target)) return DIRECT;
        if (target.startsWith("node:")) {
            String id = target.substring("node:".length());
            // A rule pointing at a deleted node falls back to the node in use.
            return nodeIds.contains(id) ? nodeTag(id) : PROXY;
        }
        return PROXY;
    }

    /**
     * Traffic that leaves through a node also resolves through that node.
     * Local DNS can be wrong for proxied names (e.g. poisoned answers), and the
     * proxy would then be asked to reach the wrong IP. Direct traffic keeps
     * using the local (or user-chosen private) resolver. DNS rules mirror the
     * route rules in order, so the first matching rule decides both.
     */
    static JSONObject splitDns(Policy policy, Routing routing, Set<String> nodeIds,
                               Map<String, RuleListParser.Parsed> ruleSets) throws JSONException {
        JSONObject dns = dns(policy.privateDns);
        String directServer = dns.getString("final");
        if (routing.mode == Routing.Mode.DIRECT) return dns;
        JSONArray servers = dns.getJSONArray("servers");
        LinkedHashSet<String> remotes = new LinkedHashSet<>();
        JSONArray rules = new JSONArray();
        if (!policy.directSsids.isEmpty()) {
            rules.put(new JSONObject().put("wifi_ssid", new JSONArray(policy.directSsids))
                    .put("action", "route").put("server", directServer));
        }
        if (routing.mode == Routing.Mode.RULE) {
            for (RouteRule rule : ProxyRuleAdapter.enabledRules(routing)) {
                if (RouteRule.TARGET_BLOCK.equals(rule.target)) continue;
                JSONObject matcher = rule(rule, nodeIds, ruleSets);
                if (matcher == null) continue;
                String outbound = matcher.optString("outbound", "");
                for (String key : new String[]{"action", "outbound", "ip_cidr", "port", "port_range"}) matcher.remove(key);
                if (matcher.length() == 0) continue;
                String server = DIRECT.equals(outbound) ? directServer : remoteFor(outbound, remotes);
                rules.put(matcher.put("action", "route").put("server", server));
            }
        }
        String finalOutbound = routing.mode == Routing.Mode.GLOBAL ? PROXY : outboundFor(routing.finalTarget, nodeIds);
        String finalServer = finalOutbound == null || DIRECT.equals(finalOutbound)
                ? directServer : remoteFor(finalOutbound, remotes);
        for (String outbound : remotes) {
            servers.put(new JSONObject()
                    .put("type", "https")
                    .put("tag", "remote-" + outbound)
                    .put("server", "1.1.1.1")
                    .put("path", "/dns-query")
                    .put("tls", new JSONObject().put("enabled", true).put("server_name", "cloudflare-dns.com"))
                    .put("detour", outbound));
        }
        if (rules.length() > 0) dns.put("rules", rules);
        return dns.put("final", finalServer);
    }

    private static String remoteFor(String outbound, LinkedHashSet<String> remotes) {
        remotes.add(outbound);
        return "remote-" + outbound;
    }

    private static JSONObject dns(String privateDns) throws JSONException {
        JSONObject local = new JSONObject().put("type", "local").put("tag", "local");
        String value = privateDns == null ? "" : privateDns.trim();
        if (value.isEmpty()) {
            return new JSONObject().put("servers", new JSONArray().put(local)).put("final", "local");
        }
        URI uri = URI.create(value);
        String type = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(type.equals("https") || type.equals("tls") || type.equals("quic")
                || type.equals("h3") || type.equals("udp")) || uri.getHost() == null) {
            throw new IllegalArgumentException("私人 DNS 只接受 DoH/DoT/DoQ/UDP 位址");
        }
        int defaultPort = type.equals("udp") ? 53 : (type.equals("https") || type.equals("h3")) ? 443 : 853;
        JSONObject server = new JSONObject()
                .put("type", type)
                .put("tag", "private")
                .put("server", uri.getHost())
                .put("server_port", uri.getPort() > 0 ? uri.getPort() : defaultPort)
                .put("domain_resolver", "local")
                .put("detour", DIRECT);
        if (type.equals("https") || type.equals("h3")) {
            String path = uri.getRawPath();
            server.put("path", path == null || path.isEmpty() ? "/dns-query" : path);
        }
        if (!type.equals("udp")) {
            server.put("tls", new JSONObject().put("enabled", true).put("server_name", uri.getHost()));
        }
        return new JSONObject().put("servers", new JSONArray().put(local).put(server)).put("final", "private");
    }

    private static JSONObject outbound(ProxyProfile profile, String tag) {
        try {
            switch (profile.type) {
                case VLESS: return vless(profile.rawConfig, tag);
                case SHADOWSOCKS: return shadowsocks(profile.rawConfig, tag);
                case HY2: return hy2(profile.rawConfig, tag);
                case WIREGUARD: return wireguard(profile.rawConfig, tag);
                default: throw new IllegalArgumentException("未支援的代理類型");
            }
        } catch (Exception e) {
            // The message names the node label only, never its secret content.
            throw new IllegalArgumentException("節點「" + profile.label + "」內容無法轉成 sing-box 設定", e);
        }
    }

    private static JSONObject vless(String raw, String tag) throws Exception {
        URI uri = URI.create(raw);
        JSONObject out = new JSONObject()
                .put("type", "vless")
                .put("tag", tag)
                .put("server", requireHost(uri))
                .put("server_port", requirePort(uri))
                .put("uuid", uri.getUserInfo());
        Map<String, String> query = query(uri.getRawQuery());
        if (query.containsKey("flow")) out.put("flow", query.get("flow"));
        String security = query.getOrDefault("security", "tls");
        if (!"none".equalsIgnoreCase(security)) {
            JSONObject tls = new JSONObject().put("enabled", true);
            if (query.containsKey("sni")) tls.put("server_name", query.get("sni"));
            if (query.containsKey("fp")) tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", query.get("fp")));
            if ("reality".equalsIgnoreCase(security)) {
                JSONObject reality = new JSONObject().put("enabled", true);
                if (query.containsKey("pbk")) reality.put("public_key", query.get("pbk"));
                if (query.containsKey("sid")) reality.put("short_id", query.get("sid"));
                tls.put("reality", reality);
            }
            // Share links spell "skip certificate verification" three ways.
            if (truthy(query.get("insecure")) || truthy(query.get("allowInsecure")) || truthy(query.get("skip-cert-verify"))) {
                tls.put("insecure", true);
            }
            if (query.containsKey("alpn") && !"reality".equalsIgnoreCase(security)) tls.put("alpn", csv(query.get("alpn")));
            out.put("tls", tls);
        }
        String network = query.getOrDefault("type", "tcp");
        if ("ws".equalsIgnoreCase(network)) {
            JSONObject transport = new JSONObject().put("type", "ws");
            if (query.containsKey("path")) transport.put("path", query.get("path"));
            if (query.containsKey("host")) transport.put("headers", new JSONObject().put("Host", query.get("host")));
            out.put("transport", transport);
        } else if ("grpc".equalsIgnoreCase(network)) {
            out.put("transport", new JSONObject().put("type", "grpc")
                    .put("service_name", query.getOrDefault("serviceName", query.getOrDefault("service_name", ""))));
        }
        return out;
    }

    private static JSONObject shadowsocks(String raw, String tag) throws Exception {
        URI uri = URI.create(raw);
        String userInfo = uri.getUserInfo();
        String host = uri.getHost();
        int port = uri.getPort();
        if (userInfo == null) {
            // Legacy form: ss://base64(method:password@host:port)#label
            String encoded = raw.substring(raw.indexOf("://") + 3).split("[?#]", 2)[0];
            String decoded = decodeBase64(encoded);
            int at = decoded.lastIndexOf('@');
            if (at < 0) throw new IllegalArgumentException("ss 節點缺少主機");
            userInfo = decoded.substring(0, at);
            String hostPort = decoded.substring(at + 1);
            int colon = hostPort.lastIndexOf(':');
            host = hostPort.substring(0, colon);
            port = Integer.parseInt(hostPort.substring(colon + 1));
        } else if (!userInfo.contains(":")) {
            // SIP002: userinfo is base64(method:password)
            userInfo = decodeBase64(userInfo);
        }
        int colon = userInfo.indexOf(':');
        if (colon <= 0 || host == null || port <= 0) throw new IllegalArgumentException("ss 節點缺少 method/password/主機");
        return new JSONObject()
                .put("type", "shadowsocks")
                .put("tag", tag)
                .put("server", host)
                .put("server_port", port)
                .put("method", userInfo.substring(0, colon))
                .put("password", userInfo.substring(colon + 1));
    }

    /**
     * hysteria2:// / hy2:// in the common share format:
     * hy2://auth@host:port[,hop-ports]/?sni=&insecure=&obfs=salamander&obfs-password=&alpn=&pinSHA256=&mport=&upmbps=&downmbps=
     * Missing obfs on an obfuscated server looks exactly like a dead server
     * (handshakes silently time out), so every common parameter is honored.
     */
    static JSONObject hy2(String raw, String tag) throws Exception {
        String rest = raw.substring(raw.indexOf("://") + 3);
        int hashAt = rest.indexOf('#');
        if (hashAt >= 0) rest = rest.substring(0, hashAt);
        String queryPart = "";
        int q = rest.indexOf('?');
        if (q >= 0) { queryPart = rest.substring(q + 1); rest = rest.substring(0, q); }
        if (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        int at = rest.lastIndexOf('@');
        String password = at >= 0 ? URLDecoder.decode(rest.substring(0, at), StandardCharsets.UTF_8.name()) : "";
        String hostPort = at >= 0 ? rest.substring(at + 1) : rest;
        String host;
        String ports;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            host = hostPort.substring(1, close);
            ports = hostPort.length() > close + 1 ? hostPort.substring(close + 2) : "443";
        } else {
            int colon = hostPort.indexOf(':');
            host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
            ports = colon >= 0 ? hostPort.substring(colon + 1) : "443";
        }
        if (host.isEmpty()) throw new IllegalArgumentException("節點缺少主機");
        Map<String, String> query = query(queryPart);

        JSONObject out = new JSONObject().put("type", "hysteria2").put("tag", tag).put("server", host).put("password", password);
        // "443" or "443,20000-30000" (port hopping); mport= is the v2rayN spelling.
        String hop = query.getOrDefault("mport", "");
        String[] list = ports.split(",");
        int main = -1;
        JSONArray hopPorts = new JSONArray();
        for (String item : list) {
            String p = item.trim();
            if (p.isEmpty()) continue;
            if (p.contains("-")) hopPorts.put(p.replace('-', ':'));
            else if (main < 0) main = Integer.parseInt(p);
            else hopPorts.put(p + ":" + p);
        }
        for (String item : hop.split(",")) {
            String p = item.trim();
            if (!p.isEmpty()) hopPorts.put(p.contains("-") ? p.replace('-', ':') : p + ":" + p);
        }
        if (main > 0) out.put("server_port", main);
        if (hopPorts.length() > 0) {
            out.put("server_ports", hopPorts);
            if (query.containsKey("hop-interval")) out.put("hop_interval", query.get("hop-interval") + (query.get("hop-interval").matches("\\d+") ? "s" : ""));
        }
        if (main <= 0 && hopPorts.length() == 0) throw new IllegalArgumentException("節點缺少埠號");

        String obfs = query.get("obfs");
        if (obfs != null && !obfs.isEmpty() && !"none".equalsIgnoreCase(obfs)) {
            out.put("obfs", new JSONObject().put("type", obfs.toLowerCase(Locale.ROOT))
                    .put("password", query.getOrDefault("obfs-password", query.getOrDefault("obfs_password", ""))));
        }
        if (query.containsKey("upmbps")) out.put("up_mbps", Integer.parseInt(query.get("upmbps").replaceAll("[^0-9]", "")));
        if (query.containsKey("downmbps")) out.put("down_mbps", Integer.parseInt(query.get("downmbps").replaceAll("[^0-9]", "")));

        JSONObject tls = new JSONObject().put("enabled", true)
                .put("server_name", query.getOrDefault("sni", query.getOrDefault("peer", host)));
        if (truthy(query.get("insecure")) || truthy(query.get("allowInsecure")) || truthy(query.get("skip-cert-verify"))) {
            tls.put("insecure", true);
        }
        if (query.containsKey("alpn")) tls.put("alpn", csv(query.get("alpn")));
        String pin = query.getOrDefault("pinSHA256", query.get("pinsha256"));
        if (pin != null && !pin.isEmpty()) {
            tls.put("certificate_public_key_sha256", new JSONArray().put(pin.replace(":", "").toLowerCase(Locale.ROOT)));
        }
        out.put("tls", tls);
        return out;
    }

    private static boolean truthy(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    /** Parameter names (never values) a node uses, for diagnosing unsupported options. */
    static String parameterNames(ProxyProfile profile) {
        if (profile.type == ProxyProfile.Type.WIREGUARD) return "";
        String raw = profile.rawConfig;
        int q = raw.indexOf('?');
        if (q < 0) return "";
        String query = raw.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);
        StringBuilder names = new StringBuilder();
        for (String pair : query.split("&")) {
            String name = pair.split("=", 2)[0];
            if (name.isEmpty()) continue;
            if (names.length() > 0) names.append(',');
            names.append(name);
        }
        return names.toString();
    }

    private static JSONObject wireguard(String raw, String tag) throws JSONException {
        Map<String, String> values = new LinkedHashMap<>();
        String section = "";
        for (String line : raw.split("\\R")) {
            String item = line.trim();
            if (item.startsWith("[") && item.endsWith("]")) {
                section = item.substring(1, item.length() - 1).toLowerCase(Locale.ROOT);
            } else if (item.contains("=")) {
                String[] parts = item.split("=", 2);
                values.put(section + "." + parts[0].trim().toLowerCase(Locale.ROOT), parts[1].trim());
            }
        }
        String endpoint = values.get("peer.endpoint");
        String publicKey = values.get("peer.publickey");
        String privateKey = values.get("interface.privatekey");
        String address = values.get("interface.address");
        if (endpoint == null || publicKey == null || privateKey == null || address == null) {
            throw new IllegalArgumentException("WireGuard 缺少 Endpoint/PublicKey/PrivateKey/Address");
        }
        String host;
        String port;
        if (endpoint.startsWith("[")) {
            host = endpoint.substring(1, endpoint.indexOf(']'));
            port = endpoint.substring(endpoint.indexOf(']') + 2);
        } else {
            host = endpoint.substring(0, endpoint.lastIndexOf(':'));
            port = endpoint.substring(endpoint.lastIndexOf(':') + 1);
        }
        JSONObject peer = new JSONObject()
                .put("address", host)
                .put("port", Integer.parseInt(port))
                .put("public_key", publicKey)
                .put("allowed_ips", csv(values.getOrDefault("peer.allowedips", "0.0.0.0/0, ::/0")));
        String preSharedKey = values.get("peer.presharedkey");
        if (preSharedKey != null && !preSharedKey.isEmpty()) peer.put("pre_shared_key", preSharedKey);
        String keepalive = values.get("peer.persistentkeepalive");
        if (keepalive != null && !keepalive.isEmpty()) peer.put("persistent_keepalive_interval", Integer.parseInt(keepalive));
        JSONObject out = new JSONObject()
                .put("type", "wireguard")
                .put("tag", tag)
                .put("address", csv(address))
                .put("private_key", privateKey)
                .put("peers", new JSONArray().put(peer));
        String mtu = values.get("interface.mtu");
        if (mtu != null && !mtu.isEmpty()) out.put("mtu", Integer.parseInt(mtu));
        return out;
    }

    private static JSONArray csv(String value) {
        JSONArray result = new JSONArray();
        for (String item : value.split(",")) {
            if (!item.trim().isEmpty()) result.put(item.trim());
        }
        return result;
    }

    private static String requireHost(URI uri) {
        if (uri.getHost() == null || uri.getHost().isEmpty()) throw new IllegalArgumentException("節點缺少主機");
        return uri.getHost();
    }

    private static int requirePort(URI uri) {
        if (uri.getPort() <= 0) throw new IllegalArgumentException("節點缺少埠號");
        return uri.getPort();
    }

    private static Map<String, String> query(String raw) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null) return result;
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()),
                        URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()));
            }
        }
        return result;
    }

    private static String decodeBase64(String value) {
        String clean = value.trim().replace('+', '-').replace('/', '_').replace("=", "");
        String padded = clean + "=".repeat((4 - clean.length() % 4) % 4);
        return new String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8);
    }
}
