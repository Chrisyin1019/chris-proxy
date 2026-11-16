package hk.chris.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class RuleListParserTest {
    @Test public void plainDomainListsBecomeSuffixes() {
        RuleListParser.Parsed p = RuleListParser.parse(
                "# comment\nexample.com\n.dot.example\n*.star.example\nhttps://Url.Example/path\n\nlocalhost\n");
        assertEquals(new LinkedHashSet<>(Arrays.asList("example.com", "dot.example", "star.example", "url.example")), p.suffixes);
        assertEquals(1, p.skipped);
    }

    @Test public void surgeClashLoonClassicalLines() {
        RuleListParser.Parsed p = RuleListParser.parse(
                "DOMAIN,exact.example\nDOMAIN-SUFFIX,suffix.example,Proxy\nDOMAIN-KEYWORD,track\n"
                        + "IP-CIDR,10.0.0.0/8,no-resolve\nIP-CIDR6,2001:db8::/32\nUSER-AGENT,Foo*\nPROCESS-NAME,x\n");
        assertTrue(p.domains.contains("exact.example"));
        assertTrue(p.suffixes.contains("suffix.example"));
        assertTrue(p.keywords.contains("track"));
        assertTrue(p.cidrs.contains("10.0.0.0/8"));
        assertTrue(p.cidrs.contains("2001:db8::/32"));
        assertEquals(2, p.skipped);
    }

    @Test public void clashYamlPayload() {
        RuleListParser.Parsed p = RuleListParser.parse(
                "payload:\n  - '+.yaml.example'\n  - \"plain.example\"\n  - DOMAIN-SUFFIX,classic.example\n  - '192.0.2.0/24'\n");
        assertEquals(new LinkedHashSet<>(Arrays.asList("yaml.example", "plain.example", "classic.example")), p.suffixes);
        assertTrue(p.cidrs.contains("192.0.2.0/24"));
        assertEquals(0, p.skipped);
    }

    @Test public void adblockAndHostsFormats() {
        RuleListParser.Parsed p = RuleListParser.parse(
                "! title\n||ads.example^\n||tracker.example^$third-party\n@@||allowed.example^\n"
                        + "0.0.0.0 hosts.example\n127.0.0.1 localhost\n::1 ip6-localhost\n1.2.3.4\n");
        assertTrue(p.suffixes.contains("ads.example"));
        assertTrue(p.suffixes.contains("tracker.example"));
        assertTrue(p.suffixes.contains("hosts.example"));
        assertFalse(p.suffixes.contains("allowed.example"));
        assertTrue(p.cidrs.contains("1.2.3.4/32"));
        assertEquals(1, p.skipped);
    }

    @Test public void duplicatesCollapse() {
        RuleListParser.Parsed p = RuleListParser.parse("a.example\nA.example\nDOMAIN-SUFFIX,a.example\n");
        assertEquals(1, p.size());
    }

    @Test public void ruleSetBecomesOneArrayRule() throws Exception {
        ProxyProfile node = ProxyProfileParser.parse(
                "vless://11111111-2222-3333-4444-555555555555@a.example.net:443#A");
        RuleListParser.Parsed parsed = RuleListParser.parse("one.example\nDOMAIN,two.example\n10.1.0.0/16\n");
        Map<String, RuleListParser.Parsed> sets = new HashMap<>();
        sets.put("aaaaaaaaaaaa", parsed);
        List<RouteRule> rules = new ArrayList<>();
        rules.add(new RouteRule(RouteRule.Match.RULE_SET, "aaaaaaaaaaaa", RouteRule.TARGET_PROXY));
        rules.add(new RouteRule(RouteRule.Match.RULE_SET, "bbbbbbbbbbbb", RouteRule.TARGET_BLOCK));
        Policy policy = new Policy(Policy.Mode.PROXY_BLACKLIST, new LinkedHashSet<>(), new LinkedHashSet<>(),
                new LinkedHashSet<>(), new LinkedHashSet<>(), "");
        JSONObject config = new JSONObject(SingBoxConfig.build(Arrays.asList(node), node, policy,
                new Routing(Routing.Mode.RULE, rules, RouteRule.TARGET_DIRECT), sets));
        JSONArray routeRules = config.getJSONObject("route").getJSONArray("rules");
        // sniff, dns, private, then the one rule set; the missing set is dropped.
        assertEquals(4, routeRules.length());
        JSONObject set = routeRules.getJSONObject(3);
        assertEquals("one.example", set.getJSONArray("domain_suffix").getString(0));
        assertEquals("two.example", set.getJSONArray("domain").getString(0));
        assertEquals("10.1.0.0/16", set.getJSONArray("ip_cidr").getString(0));
        assertEquals("proxy", set.getString("outbound"));
        assertNull(set.optJSONArray("domain_keyword"));
    }
}
