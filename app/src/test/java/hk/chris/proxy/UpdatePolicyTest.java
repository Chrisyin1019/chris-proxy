package hk.chris.proxy;

import org.junit.Test;
import java.net.URL;
import static org.junit.Assert.*;

public class UpdatePolicyTest {
    @Test
    public void acceptsValidHttpsUrl() throws Exception {
        URL url = UpdatePolicy.checkedUrl("https://example.com/assets/chris-proxy.json");
        assertEquals("https", url.getProtocol());
        assertEquals("example.com", url.getHost());
        assertEquals("/assets/chris-proxy.json", url.getPath());
    }

    @Test
    public void rejectsHttpUrl() {
        try {
            UpdatePolicy.checkedUrl("http://example.com/assets/chris-proxy.json");
            fail("Expected exception for plain http");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("HTTPS"));
        }
    }

    @Test
    public void rejectsUserInfo() {
        try {
            UpdatePolicy.checkedUrl("https://admin:secret@example.com/assets/chris-proxy.json");
            fail("Expected exception for userInfo in URL");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("帳號密碼"));
        }
    }

    @Test
    public void rejectsFragment() {
        try {
            UpdatePolicy.checkedUrl("https://example.com/assets/chris-proxy.json#section");
            fail("Expected exception for fragment in URL");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("標籤"));
        }
    }

    @Test
    public void rejectsPathTraversal() {
        try {
            UpdatePolicy.checkedUrl("https://example.com/assets/../secret.json");
            fail("Expected exception for path traversal");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("路徑"));
        }
    }

    @Test
    public void downloadUrlRequiresSameOrigin() throws Exception {
        String feed = "https://updates.example.com/assets/chris-proxy.json";
        String sameOriginDownload = "https://updates.example.com/assets/chris-proxy.apk";
        URL result = UpdatePolicy.downloadUrl(sameOriginDownload, feed);
        assertEquals("updates.example.com", result.getHost());

        String differentHostDownload = "https://evil.example.com/assets/chris-proxy.apk";
        try {
            UpdatePolicy.downloadUrl(differentHostDownload, feed);
            fail("Expected exception for different host download");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("來源與資訊清單伺服器不同"));
        }
    }
}
