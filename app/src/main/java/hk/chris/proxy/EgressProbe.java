package hk.chris.proxy;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the real public exit of normal traffic. While the VPN runs, the probe
 * goes through the core's loopback-only SOCKS inbound, so it follows exactly
 * the same rules as other apps (Wi-Fi exceptions, FINAL, …); the core log then
 * tells which outbound actually carried it. Without the VPN it goes direct.
 */
final class EgressProbe {
    static final String HOST = "ip-api.com";
    static final String PATH = "/line/?fields=status,query,country,city,isp&lang=zh-CN";
    private static final Pattern ID = Pattern.compile("\\[(\\d+) ");

    private EgressProbe() {}

    static final class Result {
        String ip = "";
        String place = "";
        /** "直連", "經由 <節點>", or "" when unknown. */
        String route = "";
        String error = "";
        final long time = System.currentTimeMillis();
    }

    static Result run(Context context) {
        Result result = new Result();
        int port = ProxyVpnService.probePort();
        try {
            String body = port > 0
                    ? viaSocks(port, ProxyVpnService.probeUser(), ProxyVpnService.probePassword())
                    : direct();
            String[] lines = body.trim().split("\\R");
            if (lines.length < 2 || !"success".equals(lines[0].trim())) throw new IllegalStateException("查詢服務沒有回應");
            parseBody(lines, result);
            result.route = port > 0 ? routeFromCoreLog(context) : "未連線（直接出口）";
        } catch (Exception e) {
            result.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        return result;
    }

    /** ip-api returns fields in its own order; pick the address by shape, the rest is place/ISP. */
    static void parseBody(String[] lines, Result result) {
        StringBuilder place = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            String part = lines[i].trim();
            if (part.isEmpty()) continue;
            if (result.ip.isEmpty() && (part.matches("\\d{1,3}(\\.\\d{1,3}){3}") || part.matches("[0-9a-fA-F:]+:[0-9a-fA-F:]*"))) {
                result.ip = part;
                continue;
            }
            if (place.length() > 0) place.append(" · ");
            place.append(part);
        }
        result.place = place.toString();
    }

    private static String direct() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, 80), 8000);
            socket.setSoTimeout(8000);
            return httpGet(socket);
        }
    }

    /** Minimal SOCKS5 client with username/password auth (RFC 1928/1929). */
    static String viaSocks(int port, String user, String password) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            out.write(new byte[]{5, 1, 2});
            if (in.readByte() != 5 || in.readByte() != 2) throw new IllegalStateException("檢測入口拒絕");
            byte[] u = user.getBytes(StandardCharsets.UTF_8);
            byte[] p = password.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream auth = new ByteArrayOutputStream();
            auth.write(1);
            auth.write(u.length);
            auth.write(u);
            auth.write(p.length);
            auth.write(p);
            out.write(auth.toByteArray());
            in.readByte();
            if (in.readByte() != 0) throw new IllegalStateException("檢測入口驗證失敗");
            byte[] host = HOST.getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream connect = new ByteArrayOutputStream();
            connect.write(new byte[]{5, 1, 0, 3, (byte) host.length});
            connect.write(host);
            connect.write(new byte[]{0, 80});
            out.write(connect.toByteArray());
            in.readByte();
            if (in.readByte() != 0) throw new IllegalStateException("出口連線失敗");
            in.readByte();
            int type = in.readByte();
            int skip = type == 1 ? 4 : type == 4 ? 16 : in.readUnsignedByte();
            in.readFully(new byte[skip + 2]);
            return httpGet(socket);
        }
    }

    private static String httpGet(Socket socket) throws Exception {
        OutputStream out = socket.getOutputStream();
        out.write(("GET " + PATH + " HTTP/1.1\r\nHost: " + HOST + "\r\nUser-Agent: ChrisProxy\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1 && body.size() < 65536) body.write(buffer, 0, n);
        String response = body.toString("UTF-8");
        int split = response.indexOf("\r\n\r\n");
        if (!response.startsWith("HTTP/1.") || split < 0) throw new IllegalStateException("查詢服務回應格式錯誤");
        if (!response.startsWith("HTTP/1.1 200") && !response.startsWith("HTTP/1.0 200")) {
            throw new IllegalStateException(response.substring(0, Math.min(response.indexOf('\r'), 40)));
        }
        return response.substring(split + 4);
    }

    /** Which outbound carried the last probe connection, read from the core log. */
    static String routeFromCoreLog(Context context) throws Exception {
        Thread.sleep(300);
        File file = new File(context.getFilesDir(), "logs/core.log");
        if (!file.exists()) return "";
        String tail;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, raf.length() - 256 * 1024);
            byte[] data = new byte[(int) (raf.length() - start)];
            raf.seek(start);
            raf.readFully(data);
            tail = new String(data, StandardCharsets.UTF_8);
        }
        return routeFromLog(tail, ProxyProfileStore.load(context));
    }

    /** Pure parser, unit-tested: finds the probe connection id and its outbound. */
    static String routeFromLog(String log, List<ProxyProfile> profiles) {
        String[] lines = log.split("\\R");
        String id = null;
        for (int i = lines.length - 1; i >= 0 && id == null; i--) {
            if (lines[i].contains("inbound/" + ProxyVpnService.PROBE_TYPE + "[" + ProxyVpnService.PROBE_TAG + "]")) {
                Matcher m = ID.matcher(lines[i]);
                if (m.find()) id = m.group(1);
            }
        }
        if (id == null) return "";
        for (String line : lines) {
            if (!line.contains("[" + id + " ") || !line.contains("outbound/")) continue;
            int at = line.indexOf("outbound/");
            int open = line.indexOf('[', at);
            int close = line.indexOf(']', open);
            if (open < 0 || close < 0) continue;
            String tag = line.substring(open + 1, close);
            if (SingBoxConfig.DIRECT.equals(tag)) return "直連";
            if (tag.startsWith("node-")) {
                String nodeId = tag.substring("node-".length());
                for (ProxyProfile p : profiles) if (p.id().equals(nodeId)) return "經由 " + p.label;
                return "經由節點";
            }
            return "經由 " + tag;
        }
        return "";
    }
}
