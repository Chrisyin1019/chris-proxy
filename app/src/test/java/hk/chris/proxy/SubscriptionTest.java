package hk.chris.proxy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class SubscriptionTest {
    private static final String LIST = "vless://11111111-2222-3333-4444-555555555555@a.example:443?security=tls#%E6%97%A5%E6%9C%AC%20A\n"
            + "hy2://secret@b.example:443/?obfs=salamander&obfs-password=x#B\n"
            + "vmess://eyJhZGQiOiJjIn0=\n"
            + "trojan://p@c.example:443#C\n"
            + "hy2://secret@b.example:443/?obfs=salamander&obfs-password=x#B\n";

    @Test public void base64SubscriptionIsDecoded() {
        String body = Base64.getEncoder().encodeToString(LIST.getBytes(StandardCharsets.UTF_8));
        SubscriptionStore.Parsed parsed = SubscriptionStore.parse(body);
        assertEquals(2, parsed.nodes.size());
        assertEquals(2, parsed.unsupported);
        assertEquals("日本 A", parsed.nodes.get(0).label);
    }

    @Test public void urlSafeBase64WithLineBreaksIsDecoded() {
        String body = Base64.getUrlEncoder().encodeToString(LIST.getBytes(StandardCharsets.UTF_8));
        String wrapped = body.substring(0, 40) + "\n" + body.substring(40);
        assertEquals(2, SubscriptionStore.parse(wrapped).nodes.size());
    }

    @Test public void plainTextSubscriptionWorks() {
        SubscriptionStore.Parsed parsed = SubscriptionStore.parse(LIST);
        assertEquals(2, parsed.nodes.size());
        assertEquals("B", parsed.nodes.get(1).label);
    }
}
