package hk.chris.proxy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RealLatencyTesterTest {
    @Test public void coreErrorsBecomeActionableReasons() {
        assertEquals("伺服器拒絕（帳號可能失效）", RealLatencyTester.reason(
                "Head \"https://www.gstatic.com/generate_204\": unknown version: 72"));
        assertEquals("逾時", RealLatencyTester.reason("context deadline exceeded"));
        assertEquals("憑證錯誤", RealLatencyTester.reason("tls: failed to verify certificate: x509: certificate is valid for a, not b"));
        assertEquals("拒絕連線", RealLatencyTester.reason("dial tcp 1.2.3.4:443: connect: connection refused"));
        assertEquals("失敗", RealLatencyTester.reason("something else"));
    }
}
