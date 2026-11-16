package hk.chris.proxy;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Exporter side of the two-stage transfer, following Chris Secure's pairing:
 * show QR and wait → the new phone asks (name + six-digit check code) →
 * the owner approves and an OTP is shown → the new phone types the OTP →
 * only then is the bundle sent, encrypted. The QR carries no settings, only
 * an address, a one-time token and this session's public key. The first
 * phone with a valid token is bound; nobody else can claim, even with the OTP.
 */
public final class TransferServer {
    public static final String SCHEME_PREFIX = "chrisproxy://transfer?";
    static final long SESSION_MILLIS = 10 * 60 * 1000;
    static final long OTP_MILLIS = 5 * 60 * 1000;
    static final int MAX_OTP_ATTEMPTS = 5;

    public interface Listener {
        void onRequest(String deviceName, String checkCode);
        void onApproved(String otp);
        void onOtpWrong(int attemptsLeft);
        void onDone();
        void onFailed(String reason);
    }

    private final Supplier<String> bundle;
    private final Listener listener;
    private volatile String deviceName = "";

    /** This phone's own name, shown on the new phone so the user knows where the settings come from. */
    public void setDeviceName(String name) { deviceName = name == null ? "" : name; }
    private final BlockingQueue<Boolean> decision = new LinkedBlockingQueue<>();
    private volatile ServerSocket server;
    private volatile Socket client;
    private volatile boolean finished;

    public TransferServer(Supplier<String> bundle, Listener listener) {
        this.bundle = bundle;
        this.listener = listener;
    }

    /** Binds to the given LAN address and returns the QR text. */
    public String start(InetAddress address) throws Exception {
        KeyPair keys = TransferCrypto.newKeyPair();
        byte[] publicKey = keys.getPublic().getEncoded();
        byte[] token = TransferCrypto.random(16);
        server = new ServerSocket();
        server.bind(new InetSocketAddress(address, 0));
        server.setSoTimeout((int) SESSION_MILLIS);
        Thread thread = new Thread(() -> run(keys, publicKey, token), "transfer-export");
        thread.setDaemon(true);
        thread.start();
        String host = address.getHostAddress();
        if (host.contains(":")) host = "[" + host + "]";
        return SCHEME_PREFIX + "v=1&h=" + java.net.URLEncoder.encode(host, "UTF-8") + "&p=" + server.getLocalPort()
                + "&k=" + b64(publicKey) + "&s=" + b64(token);
    }

    private final java.util.concurrent.atomic.AtomicBoolean decided = new java.util.concurrent.atomic.AtomicBoolean();

    /** Only the first approve/reject counts; a double tap must not queue a second decision. */
    public void approve() { if (decided.compareAndSet(false, true)) decision.offer(true); }

    public void reject() { if (decided.compareAndSet(false, true)) decision.offer(false); }

    public void close() {
        finished = true;
        decision.offer(false);
        closeQuietly(client);
        try { if (server != null) server.close(); } catch (Exception ignored) {}
    }

    private void run(KeyPair keys, byte[] publicKey, byte[] token) {
        long deadline = System.currentTimeMillis() + SESSION_MILLIS;
        try {
            while (!finished) {
                Socket socket;
                try {
                    socket = server.accept();
                } catch (SocketTimeoutException e) {
                    fail("轉移已逾時，請重新開始");
                    return;
                }
                socket.setSoTimeout((int) Math.max(1000, deadline - System.currentTimeMillis()));
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                JSONObject hello;
                try {
                    hello = new JSONObject(new String(TransferCrypto.readFrame(in), StandardCharsets.UTF_8));
                } catch (Exception e) {
                    closeQuietly(socket);
                    continue;
                }
                byte[] offered = unb64(hello.optString("s"));
                if (!"hello".equals(hello.optString("t")) || !MessageDigest.isEqual(offered, token)) {
                    // Wrong or missing token: drop it and keep waiting for the real phone.
                    closeQuietly(socket);
                    continue;
                }
                // Bound: stop accepting anyone else for this session.
                client = socket;
                server.close();
                serve(keys, publicKey, token, hello, in, out);
                return;
            }
        } catch (Exception e) {
            if (!finished) fail("連線中斷");
        } finally {
            closeQuietly(client);
        }
    }

    private void serve(KeyPair keys, byte[] publicKey, byte[] token, JSONObject hello,
                       DataInputStream in, DataOutputStream out) throws Exception {
        byte[] importerKey = unb64(hello.getString("k"));
        TransferCrypto.Session session = TransferCrypto.derive(keys, publicKey, importerKey, token, true);
        String name = hello.optString("n", "另一支手機");
        if (name.length() > 60) name = name.substring(0, 60);
        session.send(out, new JSONObject().put("t", "wait").put("n", deviceName).toString());
        listener.onRequest(name, session.checkCode);

        Boolean approved = decision.poll(SESSION_MILLIS, TimeUnit.MILLISECONDS);
        if (approved == null || !approved || finished) {
            session.send(out, new JSONObject().put("t", "rejected").toString());
            fail(approved == null ? "等待核准逾時" : "已拒絕這次轉移");
            return;
        }
        String otp = TransferCrypto.otp();
        long otpDeadline = System.currentTimeMillis() + OTP_MILLIS;
        session.send(out, new JSONObject().put("t", "approved").toString());
        listener.onApproved(otp);

        for (int attempts = 0; attempts < MAX_OTP_ATTEMPTS; ) {
            client.setSoTimeout((int) Math.max(1000, otpDeadline - System.currentTimeMillis()));
            JSONObject message;
            try {
                message = new JSONObject(session.receive(in));
            } catch (SocketTimeoutException e) {
                session.send(out, new JSONObject().put("t", "expired").toString());
                fail("OTP 已過期，請重新開始");
                return;
            }
            if (!"otp".equals(message.optString("t"))) continue;
            if (System.currentTimeMillis() > otpDeadline) {
                session.send(out, new JSONObject().put("t", "expired").toString());
                fail("OTP 已過期，請重新開始");
                return;
            }
            if (TransferCrypto.constantTimeEquals(otp, message.optString("otp"))) {
                session.send(out, new JSONObject().put("t", "bundle").put("data", bundle.get()).toString());
                finished = true;
                listener.onDone();
                return;
            }
            attempts++;
            int left = MAX_OTP_ATTEMPTS - attempts;
            if (left > 0) {
                session.send(out, new JSONObject().put("t", "otp_wrong").put("left", left).toString());
                listener.onOtpWrong(left);
            }
        }
        session.send(out, new JSONObject().put("t", "cancelled").toString());
        fail("OTP 錯誤次數過多，已取消");
    }

    private void fail(String reason) {
        if (finished) return;
        finished = true;
        listener.onFailed(reason);
    }

    static String b64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    static byte[] unb64(String value) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    private static void closeQuietly(Socket socket) {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }
}
