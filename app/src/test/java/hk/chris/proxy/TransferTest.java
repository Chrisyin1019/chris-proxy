package hk.chris.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class TransferTest {
    private static final String BUNDLE = "{\"format\":\"chris-proxy-transfer\",\"secret\":\"node-password\"}";

    /** Collects every callback of both sides as "side:event:value" strings. */
    private static final class Probe implements TransferServer.Listener, TransferClient.Listener {
        final BlockingQueue<String> events = new ArrayBlockingQueue<>(64);
        private final String side;
        Probe(String side) { this.side = side; }
        @Override public void onRequest(String name, String check) { events.add(side + ":request:" + name + ":" + check); }
        @Override public void onApproved(String otp) { events.add(side + ":approved:" + otp); }
        @Override public void onApproved() { events.add(side + ":approved"); }
        @Override public void onOtpWrong(int left) { events.add(side + ":wrong:" + left); }
        @Override public void onDone() { events.add(side + ":done"); }
        @Override public void onFailed(String reason) { events.add(side + ":failed:" + reason); }
        @Override public void onCheckCode(String check, String from) { events.add(side + ":check:" + check); fromName = from; }
        volatile String fromName;
        @Override public void onBundle(String json) { events.add(side + ":bundle:" + json); }
        String next() throws InterruptedException {
            String e = events.poll(10, TimeUnit.SECONDS);
            if (e == null) throw new AssertionError(side + " timed out");
            return e;
        }
    }

    @Test public void approvedTransferDeliversBundleOnlyAfterOtp() throws Exception {
        Probe exporter = new Probe("E");
        Probe importer = new Probe("I");
        TransferServer server = new TransferServer(() -> BUNDLE, exporter);
        server.setDeviceName("Chris 的 Pixel");
        String qr = server.start(InetAddress.getLoopbackAddress());
        assertFalse("QR must not carry settings", qr.contains("node-password"));
        assertFalse("QR must not carry the device name", qr.contains("Pixel"));
        TransferClient client = new TransferClient(importer);
        client.connect(TransferClient.parse(qr), "Pixel 新手機");

        String request = exporter.next();
        String check = importer.next();
        assertTrue(request.startsWith("E:request:Pixel 新手機:"));
        // Both screens show the same six-digit check code.
        assertEquals(request.substring(request.lastIndexOf(':') + 1), check.substring("I:check:".length()));
        assertEquals("Chris 的 Pixel", importer.fromName);

        server.approve();
        String otp = exporter.next().substring("E:approved:".length());
        assertEquals(6, otp.length());
        assertEquals("I:approved", importer.next());

        client.submitOtp("000000".equals(otp) ? "111111" : "000000");
        assertEquals("I:wrong:4", importer.next());
        assertEquals("E:wrong:4", exporter.next());

        client.submitOtp(otp);
        assertEquals("I:bundle:" + BUNDLE, importer.next());
        assertEquals("E:done", exporter.next());
    }

    @Test public void doubleApproveProducesOneOtp() throws Exception {
        Probe exporter = new Probe("E");
        Probe importer = new Probe("I");
        TransferServer server = new TransferServer(() -> BUNDLE, exporter);
        TransferClient client = new TransferClient(importer);
        client.connect(TransferClient.parse(server.start(InetAddress.getLoopbackAddress())), "x");
        exporter.next();
        importer.next();
        server.approve();
        server.approve();
        String otp = exporter.next().substring("E:approved:".length());
        assertNull("only one OTP may be issued", exporter.events.poll(500, TimeUnit.MILLISECONDS));
        importer.next();
        client.submitOtp(otp);
        assertEquals("I:bundle:" + BUNDLE, importer.next());
    }

    @Test public void fiveWrongOtpsCancelTheTransfer() throws Exception {
        Probe exporter = new Probe("E");
        Probe importer = new Probe("I");
        TransferServer server = new TransferServer(() -> BUNDLE, exporter);
        TransferClient client = new TransferClient(importer);
        client.connect(TransferClient.parse(server.start(InetAddress.getLoopbackAddress())), "x");
        exporter.next();
        importer.next();
        server.approve();
        String otp = exporter.next().substring("E:approved:".length());
        importer.next();
        String wrong = otp.equals("123456") ? "654321" : "123456";
        for (int left = 4; left >= 1; left--) {
            client.submitOtp(wrong);
            assertEquals("I:wrong:" + left, importer.next());
        }
        client.submitOtp(wrong);
        assertTrue(importer.next().startsWith("I:failed:"));
        String last;
        do { last = exporter.next(); } while (last.startsWith("E:wrong"));
        assertTrue(last.startsWith("E:failed:"));
        assertNull(importer.events.poll(500, TimeUnit.MILLISECONDS));
    }

    @Test public void rejectedTransferSendsNothing() throws Exception {
        Probe exporter = new Probe("E");
        Probe importer = new Probe("I");
        TransferServer server = new TransferServer(() -> BUNDLE, exporter);
        TransferClient client = new TransferClient(importer);
        client.connect(TransferClient.parse(server.start(InetAddress.getLoopbackAddress())), "x");
        exporter.next();
        importer.next();
        server.reject();
        assertTrue(importer.next().startsWith("I:failed:"));
        assertTrue(exporter.next().startsWith("E:failed:"));
    }

    @Test public void wrongTokenIsIgnoredAndTheRealPhoneStillPairs() throws Exception {
        Probe exporter = new Probe("E");
        Probe importer = new Probe("I");
        TransferServer server = new TransferServer(() -> BUNDLE, exporter);
        String qr = server.start(InetAddress.getLoopbackAddress());
        TransferClient.Target target = TransferClient.parse(qr);
        // An attacker who knows the address but not the token.
        try (Socket rogue = new Socket(target.host, target.port)) {
            TransferCrypto.writeFrame(new DataOutputStream(rogue.getOutputStream()),
                    ("{\"t\":\"hello\",\"s\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"k\":\"x\",\"n\":\"rogue\"}")
                            .getBytes(StandardCharsets.UTF_8));
            rogue.setSoTimeout(2000);
            assertEquals(-1, new DataInputStream(rogue.getInputStream()).read());
        }
        new TransferClient(importer).connect(target, "real");
        assertTrue(exporter.next().startsWith("E:request:real:"));
        server.close();
    }

    @Test public void sealedFileNeedsTheTransferCode() throws Exception {
        String code = TransferCodec.newCode();
        assertEquals(10, code.length());
        byte[] sealed = TransferCodec.seal(BUNDLE, code);
        assertTrue(TransferCodec.isSealed(sealed));
        assertFalse(new String(sealed, StandardCharsets.ISO_8859_1).contains("node-password"));
        assertEquals(BUNDLE, TransferCodec.open(sealed, TransferCodec.format(code).toLowerCase()));
        String other = code.charAt(0) == 'A' ? "B" + code.substring(1) : "A" + code.substring(1);
        assertThrows(TransferCodec.WrongCodeException.class, () -> TransferCodec.open(sealed, other));
        byte[] tampered = sealed.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThrows(TransferCodec.WrongCodeException.class, () -> TransferCodec.open(tampered, code));
    }

    @Test public void sessionsWithDifferentKeysDisagree() throws Exception {
        byte[] token = TransferCrypto.random(16);
        java.security.KeyPair a = TransferCrypto.newKeyPair();
        java.security.KeyPair b = TransferCrypto.newKeyPair();
        java.security.KeyPair c = TransferCrypto.newKeyPair();
        TransferCrypto.Session exporter = TransferCrypto.derive(a, a.getPublic().getEncoded(), b.getPublic().getEncoded(), token, true);
        TransferCrypto.Session importer = TransferCrypto.derive(b, a.getPublic().getEncoded(), b.getPublic().getEncoded(), token, false);
        TransferCrypto.Session impostor = TransferCrypto.derive(c, a.getPublic().getEncoded(), c.getPublic().getEncoded(), token, false);
        assertEquals(exporter.checkCode, importer.checkCode);
        assertNotEquals(exporter.checkCode, impostor.checkCode);
    }
}
