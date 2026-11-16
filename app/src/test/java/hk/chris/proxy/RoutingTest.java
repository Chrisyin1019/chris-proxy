package hk.chris.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RoutingTest {
    private static final ProxyProfile NODE_A = ProxyProfileParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@a.example.net:443?security=tls&sni=a.example.net#A");
    private static final ProxyProfile NODE_B = ProxyProfileParser.parse(
            "ss://YWVzLTI1Ni1nY206c2VjcmV0@b.example.net:8388#B");
    private static final ProxyProfile NODE_WG = ProxyProfileParser.parse(
            "[Interface]\nPrivateKey = cHJpdmF0ZQ==\nAddress = 10.0.0.2/32\n"
                    + "[Peer]\nPublicKey = cHVibGlj\nEndpoint = wg.example.net:51820\n");

    private static Policy policy(String... blocked) {
        return new Policy(Policy.Mode.PROXY_BLACKLIST, new LinkedHashSet<>(), new LinkedHashSet<>(),
                new LinkedHashSet<>(), new LinkedHashSet<>(Arrays.asList(blocked)), "");
    }

    @Test public void ruleValuesAreNormalizedAndValidated() {
        assertEquals("example.com", new RouteRule(RouteRule.Match.DOMAIN_SUFFIX, " https://Example.com/x ", "DIRECT").value);
        assertEquals("10.0.0.1/32", new RouteRule(RouteRule.Match.IP_CIDR, "10.0.0.1", "DIRECT").value);
        assertEquals("80,8000-8080", new RouteRule(RouteRule.Match.PORT, "80, 8000-8080", "DIRECT").value);
        assertThrows(IllegalArgumentException.class, () -> new RouteRule(RouteRule.Match.IP_CIDR, "300.1.1.1", "DIRECT"));
        assertThrows(IllegalArgumentException.class, () -> new RouteRule(RouteRule.Match.PORT, "70000", "DIRECT"));
        assertThrows(IllegalArgumentException.class, () -> new RouteRule(RouteRule.Match.PACKAGE, "not a package", "DIRECT"));
        assertThrows(IllegalArgumentException.class, () -> new RouteRule(RouteRule.Match.DOMAIN, "a.com", "node:XYZ"));
    }

    @Test public void ruleSerializationRoundTrips() {
        RouteRule rule = new RouteRule(RouteRule.Match.PACKAGE, "com.example.app", RouteRule.nodeTarget(NODE_B.id()), false);
        RouteRule back = RouteRule.deserialize(rule.serialize());
        assertEquals(rule.match, back.match);
        assertEquals(rule.value, back.value);
        assertEquals(NODE_B.id(), back.nodeId());
        assertFalse(back.enabled);
        assertTrue(RouteRule.deserialize("DOMAIN_SUFFIX\tlegacy.example\tDIRECT").enabled);
    }

    @Test public void disabledRulesStayStoredButNeverReachTheProxyCore() throws Exception {
        List<RouteRule> rules = new ArrayList<>();
        rules.add(new RouteRule(RouteRule.Match.DOMAIN_SUFFIX, "disabled.example", RouteRule.TARGET_BLOCK, false));
        rules.add(new RouteRule(RouteRule.Match.DOMAIN_SUFFIX, "active.example", RouteRule.TARGET_DIRECT));
        Routing routing = new Routing(Routing.Mode.RULE, rules, RouteRule.TARGET_PROXY);

        assertEquals(1, ProxyRuleAdapter.enabledRules(routing).size());
        JSONObject config = new JSONObject(SingBoxConfig.build(Arrays.asList(NODE_A), NODE_A, policy(), routing));
        JSONArray generated = config.getJSONObject("route").getJSONArray("rules");
        assertEquals(4, generated.length()); // sniff + DNS + private IP + one active user rule
        assertEquals("active.example", generated.getJSONObject(3).getJSONArray("domain_suffix").getString(0));
    }

    @Test public void dryRunUsesFirstEnabledMatchAndFallsBackToFinal() {
        List<RouteRule> rules = new ArrayList<>();
        rules.add(new RouteRule(RouteRule.Match.DOMAIN_SUFFIX, "corp.example", RouteRule.TARGET_BLOCK, false));
        rules.add(new RouteRule(RouteRule.Match.DOMAIN_SUFFIX, "corp.example", RouteRule.TARGET_PROXY));
        rules.add(new RouteRule(RouteRule.Match.PORT, "8000-8080", RouteRule.TARGET_DIRECT));
        Routing routing = new Routing(Routing.Mode.RULE, rules, RouteRule.TARGET_BLOCK);

        ProxyRuleAdapter.Result domain = ProxyRuleAdapter.dryRun(routing,
                new ProxyRuleAdapter.Sample("ime.corp.example", "", "", 443), new LinkedHashMap<>());
        assertEquals(1, domain.ruleIndex);
        assertEquals(RouteRule.TARGET_PROXY, domain.target);

        ProxyRuleAdapter.Result port = ProxyRuleAdapter.dryRun(routing,
                new ProxyRuleAdapter.Sample("other.example", "", "", 8042), new LinkedHashMap<>());
        assertEquals(2, port.ruleIndex);
        assertEquals(RouteRule.TARGET_DIRECT, port.target);

        ProxyRuleAdapter.Result fallback = ProxyRuleAdapter.dryRun(routing,
                new ProxyRuleAdapter.Sample("other.example", "", "", 443), new LinkedHashMap<>());
        assertEquals(-1, fallback.ruleIndex);
        assertEquals(RouteRule.TARGET_BLOCK, fallback.target);
    }

    @Test public void dryRunMatchesIpv4Ipv6AndRuleSets() {
        RouteRule ipv4 = new RouteRule(RouteRule.Match.IP_CIDR, "192.0.2.0/24", RouteRule.TARGET_DIRECT);
        RouteRule ipv6 = new RouteRule(RouteRule.Match.IP_CIDR, "2001:db8::/32", RouteRule.TARGET_DIRECT);
        assertTrue(ProxyRuleAdapter.matches(ipv4, new ProxyRuleAdapter.Sample("", "192.0.2.19", "", 0), null));
        assertFalse(ProxyRuleAdapter.matches(ipv4, new ProxyRuleAdapter.Sample("", "192.0.3.19", "", 0), null));
        assertTrue(ProxyRuleAdapter.matches(ipv6, new ProxyRuleAdapter.Sample("", "2001:db8::3", "", 0), null));

        RuleListParser.Parsed parsed = RuleListParser.parse("DOMAIN-SUFFIX,sync.example\nIP-CIDR,198.51.100.0/24");
        Map<String, RuleListParser.Parsed> sets = new LinkedHashMap<>();
        sets.put("aaaaaaaaaaaa", parsed);
        RouteRule set = new RouteRule(RouteRule.Match.RULE_SET, "aaaaaaaaaaaa", RouteRule.TARGET_PROXY);
        assertTrue(ProxyRuleAdapter.matches(set,
                new ProxyRuleAdapter.Sample("host.sync.example", "", "", 0), sets));
        assertTrue(ProxyRuleAdapter.matches(set,
                new ProxyRuleAdapter.Sample("", "198.51.100.10", "", 0), sets));
    }

    @Test public void routingVersionCodecPreservesOrderStateAndDisabledRules() {
        List<RouteRule> rules = Arrays.asList(
                new RouteRule(RouteRule.Match.DOMAIN, "first.example", RouteRule.TARGET_DIRECT, false),
                new RouteRule(RouteRule.Match.PACKAGE, "hk.chris.ime", RouteRule.TARGET_PROXY));
        Routing original = new Routing(Routing.Mode.RULE, rules, RouteRule.TARGET_BLOCK);
        Routing restored = RoutingCodec.decode(RoutingCodec.encode(original));
        assertEquals(Routing.Mode.RULE, restored.mode);
        assertEquals(RouteRule.TARGET_BLOCK, restored.finalTarget);
        assertEquals("first.example", restored.rules.get(0).value);
        assertFalse(restored.rules.get(0).enabled);
        assertEquals("hk.chris.ime", restored.rules.get(1).value);
    }

    @Test public void eachNodeIsAnOutboundAndRulesCanTargetSpecificNodes() throws Exception {
        List<RouteRule> rules = new ArrayList<>();
        rules.add(new RouteRule(RouteRule.Match.DOMAIN_SUFFIX, "work.example", RouteRule.nodeTarget(NODE_B.id())));
        rules.add(new RouteRule(RouteRule.Match.PACKAGE, "com.example.app", RouteRule.nodeTarget(NODE_WG.id())));
        rules.add(new RouteRule(RouteRule.Match.PORT, "25,6000-6010", RouteRule.TARGET_BLOCK));
        Routing routing = new Routing(Routing.Mode.RULE, rules, RouteRule.TARGET_PROXY);

        JSONObject config = new JSONObject(SingBoxConfig.build(
                Arrays.asList(NODE_A, NODE_B, NODE_WG), NODE_A, policy("ads.example"), routing));

        JSONArray outbounds = config.getJSONArray("outbounds");
        JSONObject selector = find(outbounds, "proxy");
        assertEquals("selector", selector.getString("type"));
        assertEquals("node-" + NODE_A.id(), selector.getString("default"));
        assertEquals(3, selector.getJSONArray("outbounds").length());
        assertEquals("shadowsocks", find(outbounds, "node-" + NODE_B.id()).getString("type"));
        assertEquals("wireguard", find(config.getJSONArray("endpoints"), "node-" + NODE_WG.id()).getString("type"));

        JSONObject route = config.getJSONObject("route");
        JSONArray routeRules = route.getJSONArray("rules");
        assertEquals("sniff", routeRules.getJSONObject(0).getString("action"));
        assertEquals("hijack-dns", routeRules.getJSONObject(1).getString("action"));
        assertTrue(routeRules.getJSONObject(2).getBoolean("ip_is_private"));
        assertEquals("reject", routeRules.getJSONObject(3).getString("action"));
        assertEquals("ads.example", routeRules.getJSONObject(3).getJSONArray("domain_suffix").getString(0));
        assertEquals("node-" + NODE_B.id(), routeRules.getJSONObject(4).getString("outbound"));
        assertEquals("com.example.app", routeRules.getJSONObject(5).getJSONArray("package_name").getString(0));
        assertEquals("node-" + NODE_WG.id(), routeRules.getJSONObject(5).getString("outbound"));
        JSONObject ports = routeRules.getJSONObject(6);
        assertEquals(25, ports.getJSONArray("port").getInt(0));
        assertEquals("6000:6010", ports.getJSONArray("port_range").getString(0));
        assertEquals("reject", ports.getString("action"));
        assertEquals("proxy", route.getString("final"));
    }

    @Test public void deletedNodeFallsBackToNodeInUse() throws Exception {
        List<RouteRule> rules = new ArrayList<>();
        rules.add(new RouteRule(RouteRule.Match.DOMAIN, "a.example", RouteRule.nodeTarget("abcdef123456")));
        JSONObject config = new JSONObject(SingBoxConfig.build(Arrays.asList(NODE_A), NODE_A, policy(),
                new Routing(Routing.Mode.RULE, rules, RouteRule.TARGET_DIRECT)));
        JSONArray routeRules = config.getJSONObject("route").getJSONArray("rules");
        assertEquals("proxy", routeRules.getJSONObject(3).getString("outbound"));
        assertEquals("direct", config.getJSONObject("route").getString("final"));
    }

    @Test public void globalAndDirectModesIgnoreRules() throws Exception {
        List<RouteRule> rules = new ArrayList<>();
        rules.add(new RouteRule(RouteRule.Match.DOMAIN, "a.example", RouteRule.TARGET_BLOCK));
        Routing routing = new Routing(Routing.Mode.GLOBAL, rules, RouteRule.TARGET_DIRECT);
        JSONObject global = new JSONObject(SingBoxConfig.build(Arrays.asList(NODE_A), NODE_A, policy(), routing));
        assertEquals("proxy", global.getJSONObject("route").getString("final"));
        assertEquals(3, global.getJSONObject("route").getJSONArray("rules").length());

        JSONObject direct = new JSONObject(SingBoxConfig.build(Arrays.asList(NODE_A), NODE_A, policy("ads.example"),
                routing.withMode(Routing.Mode.DIRECT)));
        assertEquals("direct", direct.getJSONObject("route").getString("final"));
        assertEquals(3, direct.getJSONObject("route").getJSONArray("rules").length());
    }

    @Test public void finalBlockRejectsUnmatchedTraffic() throws Exception {
        JSONObject config = new JSONObject(SingBoxConfig.build(Arrays.asList(NODE_A), NODE_A, policy(),
                new Routing(Routing.Mode.RULE, new ArrayList<>(), RouteRule.TARGET_BLOCK)));
        JSONArray routeRules = config.getJSONObject("route").getJSONArray("rules");
        JSONObject last = routeRules.getJSONObject(routeRules.length() - 1);
        assertEquals("reject", last.getString("action"));
        assertFalse(last.has("domain"));
    }

    @Test public void shadowsocksSip002Base64UserInfoIsDecoded() throws Exception {
        JSONObject config = new JSONObject(SingBoxConfig.build(Arrays.asList(NODE_B), NODE_B, policy(), Routing.defaults()));
        JSONObject ss = find(config.getJSONArray("outbounds"), "node-" + NODE_B.id());
        assertEquals("aes-256-gcm", ss.getString("method"));
        assertEquals("secret", ss.getString("password"));
        assertEquals(8388, ss.getInt("server_port"));
    }

    @Test public void nodeErrorsNameTheLabelNotTheSecret() {
        ProxyProfile broken = ProxyProfileParser.parse("vless://uuid-secret@:443#Broken");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                SingBoxConfig.build(Arrays.asList(broken), broken, policy(), Routing.defaults()));
        assertTrue(error.getMessage().contains("Broken"));
        assertFalse(error.getMessage().contains("uuid-secret"));
    }

    @Test public void loonImportKeepsOrderAndMapsPolicies() throws Exception {
        String config = "[General]\nskip=1\n[Rule]\n"
                + "DOMAIN-SUFFIX,corp.example,DIRECT\n"
                + "DOMAIN-KEYWORD,tracker,REJECT\n"
                + "IP-CIDR,192.0.2.0/24,MyNode,no-resolve\n"
                + "USER-AGENT,foo*,DIRECT\n"
                + "FINAL,MyNode\n";
        LoonConfigParser.Result result = LoonConfigParser.parse(new StringReader(config));
        assertEquals(3, result.rules.size());
        assertEquals(RouteRule.TARGET_DIRECT, result.rules.get(0).target);
        assertEquals(RouteRule.TARGET_BLOCK, result.rules.get(1).target);
        assertEquals("192.0.2.0/24", result.rules.get(2).value);
        assertEquals(RouteRule.TARGET_PROXY, result.rules.get(2).target);
        assertEquals(1, result.skipped);
        assertEquals(RouteRule.TARGET_PROXY, result.finalTarget);
    }

    @Test public void legacyWhitelistBecomesRulesWithProxyFinal() {
        Policy legacy = new Policy(Policy.Mode.DIRECT_WHITELIST, new LinkedHashSet<>(),
                new LinkedHashSet<>(Arrays.asList("lan.example")), new LinkedHashSet<>(Arrays.asList("p.example")),
                new LinkedHashSet<>(), "");
        Routing routing = Routing.fromLegacy(legacy);
        assertEquals(2, routing.rules.size());
        assertEquals(RouteRule.TARGET_DIRECT, routing.rules.get(0).target);
        assertEquals(RouteRule.TARGET_PROXY, routing.rules.get(1).target);
        assertEquals(RouteRule.TARGET_PROXY, routing.finalTarget);
        assertNull(routing.rules.get(0).nodeId());
    }

    @Test public void hysteria2HonorsObfsHoppingAndTlsOptions() throws Exception {
        JSONObject hy2 = SingBoxConfig.hy2("hy2://p%40ss@h.example.net:443,20000-30000/?sni=s.example.net"
                + "&insecure=1&obfs=salamander&obfs-password=ob&alpn=h3&upmbps=50#Label", "t");
        assertEquals("p@ss", hy2.getString("password"));
        assertEquals(443, hy2.getInt("server_port"));
        assertEquals("20000:30000", hy2.getJSONArray("server_ports").getString(0));
        assertEquals("salamander", hy2.getJSONObject("obfs").getString("type"));
        assertEquals("ob", hy2.getJSONObject("obfs").getString("password"));
        assertEquals(50, hy2.getInt("up_mbps"));
        JSONObject tls = hy2.getJSONObject("tls");
        assertEquals("s.example.net", tls.getString("server_name"));
        assertTrue(tls.getBoolean("insecure"));
        assertEquals("h3", tls.getJSONArray("alpn").getString(0));
    }

    @Test public void hysteria2WithoutOptionsKeepsDefaults() throws Exception {
        JSONObject hy2 = SingBoxConfig.hy2("hysteria2://secret@[2001:db8::1]:8443", "t");
        assertEquals("2001:db8::1", hy2.getString("server"));
        assertEquals(8443, hy2.getInt("server_port"));
        assertFalse(hy2.has("obfs"));
        assertFalse(hy2.getJSONObject("tls").has("insecure"));
    }

    @Test public void parameterNamesNeverIncludeValues() {
        ProxyProfile p = ProxyProfileParser.parse("hy2://secret@h.example:443/?sni=x&obfs=salamander&obfs-password=topsecret#N");
        assertEquals("sni,obfs,obfs-password", SingBoxConfig.parameterNames(p));
    }

    @Test public void proxiedNamesResolveThroughTheirNode() throws Exception {
        List<RouteRule> rules = new ArrayList<>();
        rules.add(new RouteRule(RouteRule.Match.DOMAIN_SUFFIX, "lan.example", RouteRule.TARGET_DIRECT));
        rules.add(new RouteRule(RouteRule.Match.DOMAIN_SUFFIX, "work.example", RouteRule.nodeTarget(NODE_B.id())));
        rules.add(new RouteRule(RouteRule.Match.IP_CIDR, "10.0.0.0/8", RouteRule.TARGET_PROXY));
        JSONObject dns = new JSONObject(SingBoxConfig.build(Arrays.asList(NODE_A, NODE_B), NODE_A, policy(),
                new Routing(Routing.Mode.RULE, rules, RouteRule.nodeTarget(NODE_A.id())))).getJSONObject("dns");
        JSONArray dnsRules = dns.getJSONArray("rules");
        assertEquals(2, dnsRules.length());
        assertEquals("local", dnsRules.getJSONObject(0).getString("server"));
        assertEquals("remote-node-" + NODE_B.id(), dnsRules.getJSONObject(1).getString("server"));
        assertEquals("remote-node-" + NODE_A.id(), dns.getString("final"));
        JSONObject remoteB = find(dns.getJSONArray("servers"), "remote-node-" + NODE_B.id());
        assertEquals("node-" + NODE_B.id(), remoteB.getString("detour"));
    }

    @Test public void directModeKeepsLocalDns() throws Exception {
        JSONObject dns = new JSONObject(SingBoxConfig.build(Arrays.asList(NODE_A), NODE_A, policy(),
                Routing.defaults().withMode(Routing.Mode.DIRECT))).getJSONObject("dns");
        assertEquals("local", dns.getString("final"));
        assertEquals(1, dns.getJSONArray("servers").length());
    }

    @Test public void wifiExceptionsGoDirectInsideTheRunningVpn() throws Exception {
        Policy policy = new Policy(Policy.Mode.PROXY_BLACKLIST, new LinkedHashSet<>(Arrays.asList("Office WiFi")),
                new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), "");
        JSONObject config = new JSONObject(SingBoxConfig.build(Arrays.asList(NODE_A), NODE_A, policy,
                Routing.defaults().withMode(Routing.Mode.GLOBAL)));
        JSONObject wifi = config.getJSONObject("route").getJSONArray("rules").getJSONObject(3);
        assertEquals("Office WiFi", wifi.getJSONArray("wifi_ssid").getString(0));
        assertEquals("direct", wifi.getString("outbound"));
        JSONObject dnsRule = config.getJSONObject("dns").getJSONArray("rules").getJSONObject(0);
        assertEquals("Office WiFi", dnsRule.getJSONArray("wifi_ssid").getString(0));
        assertEquals("local", dnsRule.getString("server"));
        // Off that Wi-Fi the global proxy still applies.
        assertEquals("proxy", config.getJSONObject("route").getString("final"));
    }

    @Test public void vlessAllowInsecureSkipsCertificateCheck() throws Exception {
        ProxyProfile node = ProxyProfileParser.parse("vless://11111111-2222-3333-4444-555555555555@v.example:443"
                + "?encryption=none&flow=xtls-rprx-vision&security=tls&sni=update.microsoft.com&type=tcp&allowInsecure=1#J");
        JSONObject vless = find(new JSONObject(SingBoxConfig.buildTestConfig(Arrays.asList(node))).getJSONArray("outbounds"),
                "node-" + node.id());
        assertTrue(vless.getJSONObject("tls").getBoolean("insecure"));
        assertEquals("update.microsoft.com", vless.getJSONObject("tls").getString("server_name"));
        assertEquals("xtls-rprx-vision", vless.getString("flow"));
    }

    private static JSONObject find(JSONArray items, String tag) throws Exception {
        for (int i = 0; i < items.length(); i++) {
            if (tag.equals(items.getJSONObject(i).optString("tag"))) return items.getJSONObject(i);
        }
        throw new AssertionError("missing tag " + tag);
    }
}
