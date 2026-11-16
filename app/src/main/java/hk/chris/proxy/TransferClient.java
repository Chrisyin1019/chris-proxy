package hk.chris.proxy;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Importer side: scan the QR, show the check code, wait for approval, send the OTP. */
public final class TransferClient {
    public interface Listener {
        void onCheckCode(String checkCode, String exporterName);
        void onApproved();
        void onOtpWrong(int attemptsLeft);
        void onBundle(String json);
        void onFailed(String reason);
    }

    /** Parsed QR: chrisproxy://transfer?v=1&h=host&p=port&k=key&s=token */
    public static final class Target {
        final String host;
        final int port;
        final byte[] exporterKey;
        final byte[] token;

        Target(String host, int port, byte[] exporterKey, byte[] token) {
            this.host = host;
            this.port = port;
            this.exporterKey = exporterKey;
            this.token = token;
        }
    }

    public static boolean isTransferQr(String text) {
        return text != null && text.trim().startsWith(TransferServer.SCHEME_PREFIX);
    }

    public static Target parse(String text) {
        String query = text.trim().substring(TransferServer.SCHEME_PREFIX.length());
        String host = null;
        int port = -1;
        byte[] key = null;
        byte[] token = null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) continue;
            String value;
            try {
                value = java.net.URLDecoder.decode(kv[1], "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
            switch (kv[0]) {
                case "h": host = value.startsWith("[") ? value.substring(1, value.length() - 1) : value; break;
                case "p": port = Integer.parseInt(value); break;
                case "k": key = TransferServer.unb64(value); break;
                case "s": token = TransferServer.unb64(value); break;
                default: break;
            }
        }
        if (host == null || port <= 0 || key == null || key.length == 0 || token == null || token.length != 16) {
            throw new IllegalArgumentException("轉移 QR 內容不完整");
        }
        return new Target(host, port, key, token);
    }

    private final Listener listener;
    private final BlockingQueue<String> otps = new LinkedBlockingQueue<>();
    private volatile Socket socket;
    private volatile boolean closed;

    public TransferClient(Listener listener) {
        this.listener = listener;
    }

    public void connect(Target target, String deviceName) {
        Thread thread = new Thread(() -> run(target, deviceName), "transfer-import");
        thread.setDaemon(true);
        thread.start();
    }

    public void submitOtp(String otp) {
        otps.offer(otp == null ? "" : otp.replaceAll("[^0-9]", ""));
    }

    public void close() {
        closed = true;
        otps.offer("");
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    private void run(Target target, String deviceName) {
        try {
            KeyPair keys = TransferCrypto.newKeyPair();
            byte[] publicKey = keys.getPublic().getEncoded();
            socket = new Socket();
            socket.connect(new InetSocketAddress(target.host, target.port), 8000);
            socket.setSoTimeout((int) TransferServer.SESSION_MILLIS);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            TransferCrypto.writeFrame(out, new JSONObject()
                    .put("t", "hello")
                    .put("s", TransferServer.b64(target.token))
                    .put("k", TransferServer.b64(publicKey))
                    .put("n", deviceName)
                    .toString().getBytes(StandardCharsets.UTF_8));
            TransferCrypto.Session session = TransferCrypto.derive(keys, target.exporterKey, publicKey, target.token, false);
            JSONObject wait = new JSONObject(session.receive(in));
            if (!"wait".equals(wait.optString("t"))) throw new IllegalStateException("unexpected message");
            String exporter = wait.optString("n");
            if (exporter.length() > 60) exporter = exporter.substring(0, 60);
            listener.onCheckCode(session.checkCode, exporter);

            JSONObject decision = new JSONObject(session.receive(in));
            if (!"approved".equals(decision.optString("t"))) {
                fail("舊手機拒絕了這次轉移");
                return;
            }
            listener.onApproved();
            while (!closed) {
                String otp = otps.poll(TransferServer.OTP_MILLIS + 5000, TimeUnit.MILLISECONDS);
                if (otp == null || closed) { fail("OTP 已過期，請重新開始"); return; }
                session.send(out, new JSONObject().put("t", "otp").put("otp", otp).toString());
                JSONObject reply = new JSONObject(session.receive(in));
                switch (reply.optString("t")) {
                    case "bundle":
                        listener.onBundle(reply.getString("data"));
                        closed = true;
                        return;
                    case "otp_wrong":
                        listener.onOtpWrong(reply.optInt("left"));
                        break;
                    case "expired":
                        fail("OTP 已過期，請重新開始");
                        return;
                    default:
                        fail("OTP 錯誤次數過多，已取消");
                        return;
                }
            }
        } catch (java.net.ConnectException e) {
            fail("舊手機 " + target.host + " 拒絕連線；請確認舊手機仍停在顯示 QR 的畫面");
        } catch (java.net.SocketTimeoutException | java.net.NoRouteToHostException e) {
            fail("連不到舊手機 " + target.host + "；請確認兩支手機在同一個 Wi-Fi，且路由器沒有開啟「AP 隔離／訪客網路隔離」");
        } catch (Exception e) {
            if (!closed) fail("連線中斷");
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }
    }

    private void fail(String reason) {
        if (closed) return;
        closed = true;
        listener.onFailed(reason);
    }
}
