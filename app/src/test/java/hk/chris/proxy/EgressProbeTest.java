package hk.chris.proxy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class EgressProbeTest {
    private static final ProxyProfile NODE = ProxyProfileParser.parse(
            "hy2://secret@jp.example.net:443/?sni=jp.example.net#Tokyo-Node");

    @Test public void directWhenTheProbeLeftThroughDirect() {
        String log = "+0800 2026-10-03 18:50:01 INFO [111 0ms] inbound/socks[probe-in]: inbound connection from 127.0.0.1:5000\n"
                + "+0800 2026-10-03 18:50:01 INFO [111 1ms] inbound/socks[probe-in]: inbound connection to ip-api.com:80\n"
                + "+0800 2026-10-03 18:50:01 INFO [222 0ms] outbound/hysteria2[node-" + NODE.id() + "]: outbound connection to x:443\n"
                + "+0800 2026-10-03 18:50:01 INFO [111 3ms] outbound/direct[direct]: outbound connection to 208.95.112.1:80\n";
        assertEquals("直連", EgressProbe.routeFromLog(log, Collections.singletonList(NODE)));
    }

    @Test public void nodeLabelWhenTheProbeWentThroughANode() {
        String log = "x INFO [7 0ms] inbound/socks[probe-in]: inbound connection from 127.0.0.1:1\n"
                + "x INFO [7 2ms] outbound/hysteria2[node-" + NODE.id() + "]: outbound connection to ip-api.com:80\n";
        assertEquals("經由 Tokyo-Node", EgressProbe.routeFromLog(log, Arrays.asList(NODE)));
    }

    @Test public void latestProbeWins() {
        String log = "x INFO [1 0ms] inbound/socks[probe-in]: inbound connection from a\n"
                + "x INFO [1 1ms] outbound/direct[direct]: outbound connection to b\n"
                + "x INFO [2 0ms] inbound/socks[probe-in]: inbound connection from a\n"
                + "x INFO [2 1ms] outbound/hysteria2[node-" + NODE.id() + "]: outbound connection to b\n";
        assertEquals("經由 Tokyo-Node", EgressProbe.routeFromLog(log, Arrays.asList(NODE)));
        assertEquals("", EgressProbe.routeFromLog("nothing here", Arrays.asList(NODE)));
    }

    @Test public void addressIsFoundWhereverIpApiPutsIt() {
        EgressProbe.Result r = new EgressProbe.Result();
        EgressProbe.parseBody("success\n日本\n东京\nExample ISP\n203.0.113.10\n".split("\n"), r);
        assertEquals("203.0.113.10", r.ip);
        assertEquals("日本 · 东京 · Example ISP", r.place);
    }
}
