package hk.chris.proxy;

import android.content.Context;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.nekohasekai.libbox.CommandClient;
import io.nekohasekai.libbox.CommandClientHandler;
import io.nekohasekai.libbox.CommandClientOptions;
import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.ConnectionEvents;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.LogIterator;
import io.nekohasekai.libbox.OutboundGroup;
import io.nekohasekai.libbox.OutboundGroupItem;
import io.nekohasekai.libbox.OutboundGroupItemIterator;
import io.nekohasekai.libbox.OutboundGroupIterator;
import io.nekohasekai.libbox.OverrideOptions;
import io.nekohasekai.libbox.StatusMessage;
import io.nekohasekai.libbox.StringIterator;

/**
 * Real latency: sing-box's URL test makes each node complete its own handshake
 * (QUIC for HY2, WireGuard, TLS/Reality for VLESS, …) and fetch a small URL
 * through it. While the VPN runs the live core is asked; otherwise a short-lived
 * test core is started in-process with only the node outbounds and no TUN.
 */
final class RealLatencyTester {
    /** Delay per node id; -1 means failed or timed out. */
    interface Callback {
        /** reasons: short Chinese failure reason per node id, when the core gave one. */
        void done(Map<String, Integer> delays, Map<String, String> reasons, String error);
    }

    /** Turns a core error into something a person can act on. */
    static String reason(String error) {
        String e = error.toLowerCase(java.util.Locale.ROOT);
        if (e.contains("unknown version")) return "伺服器拒絕（帳號可能失效）";
        if (e.contains("deadline exceeded") || e.contains("timeout") || e.contains("i/o timeout")) return "逾時";
        if (e.contains("certificate") || e.contains("x509")) return "憑證錯誤";
        if (e.contains("refused")) return "拒絕連線";
        if (e.contains("no such host") || e.contains("lookup")) return "找不到伺服器";
        if (e.contains("reality")) return "Reality 驗證失敗";
        if (e.contains("authentication") || e.contains("auth")) return "驗證失敗";
        return "失敗";
    }

    private static final long TIMEOUT_MS = 20_000;
    private static volatile boolean running;

    private RealLatencyTester() {}

    static boolean isRunning() { return running; }

    static void testAll(Context context, List<ProxyProfile> profiles, Callback callback) {
        if (running) return;
        running = true;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            Map<String, Integer> delays = new HashMap<>();
            Map<String, String> reasons = new HashMap<>();
            String error = null;
            CommandServer temporary = null;
            CommandClient client = null;
            try {
                LibboxRuntime.ensureSetup(app);
                if (ProxyVpnService.state() != ProxyVpnService.State.RUNNING) {
                    temporary = new CommandServer(new ProxyCommandHandler(app, () -> {}, () -> {}),
                            new LibboxPlatform(app, null));
                    temporary.start();
                    String testConfig = SingBoxConfig.buildTestConfig(profiles);
                    temporary.startOrReloadService(testConfig, new OverrideOptions());
                }
                Groups groups = new Groups();
                CommandClientOptions options = new CommandClientOptions();
                options.addCommand(Libbox.CommandGroup);
                options.addCommand(Libbox.CommandLog);
                options.setStatusInterval(500_000_000L);
                client = new CommandClient(groups, options);
                client.connect();
                if (!groups.first.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("核心沒有回應");
                Map<String, Long> before = groups.times();
                client.urlTest(SingBoxConfig.PROXY);
                long deadline = System.currentTimeMillis() + TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(300);
                    if (groups.allChangedSince(before, profiles)) break;
                }
                for (ProxyProfile p : profiles) {
                    String tag = SingBoxConfig.nodeTag(p.id());
                    Long time = groups.time.get(tag);
                    Integer delay = groups.delay.get(tag);
                    boolean fresh = time != null && !time.equals(before.get(tag));
                    delays.put(p.id(), fresh && delay != null && delay > 0 ? delay : -1);
                    String why = groups.reasons.get(tag);
                    if (why != null) reasons.put(p.id(), why);
                }
                AppLogger.log(app, "INFO", "真實延遲測試完成，節點=" + profiles.size()
                        + (temporary != null ? "（暫時測試核心）" : "（連線中的核心）"));
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                AppLogger.log(app, "WARN", "真實延遲測試失敗=" + error);
            } finally {
                try { if (client != null) client.disconnect(); } catch (Exception ignored) {}
                try { if (temporary != null) temporary.closeService(); } catch (Exception ignored) {}
                try { if (temporary != null) temporary.close(); } catch (Exception ignored) {}
                running = false;
            }
            callback.done(delays, reasons, error);
        }, "real-latency").start();
    }

    /** Collects the latest URL-test delay and time of every member of the "proxy" group. */
    private static final class Groups implements CommandClientHandler {
        final CountDownLatch first = new CountDownLatch(1);
        final Map<String, Integer> delay = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, Long> time = new java.util.concurrent.ConcurrentHashMap<>();

        Map<String, Long> times() { return new HashMap<>(time); }

        boolean allChangedSince(Map<String, Long> before, List<ProxyProfile> profiles) {
            for (ProxyProfile p : profiles) {
                String tag = SingBoxConfig.nodeTag(p.id());
                Long now = time.get(tag);
                if (now == null || now.equals(before.get(tag))) return false;
            }
            return true;
        }

        final Map<String, String> reasons = new java.util.concurrent.ConcurrentHashMap<>();

        @Override public void writeGroups(OutboundGroupIterator groups) {
            while (groups != null && groups.hasNext()) {
                OutboundGroup group = groups.next();
                if (!SingBoxConfig.PROXY.equals(group.getTag())) continue;
                OutboundGroupItemIterator items = group.getItems();
                while (items != null && items.hasNext()) {
                    OutboundGroupItem item = items.next();
                    delay.put(item.getTag(), item.getURLTestDelay());
                    time.put(item.getTag(), item.getURLTestTime());
                }
            }
            first.countDown();
        }

        @Override public void clearLogs() {}
        @Override public void connected() {}
        @Override public void disconnected(String message) {}
        @Override public void initializeClashMode(StringIterator modes, String current) {}
        @Override public void setDefaultLogLevel(int level) {}
        @Override public void updateClashMode(String mode) {}
        @Override public void writeConnectionEvents(ConnectionEvents events) {}
        /** Keeps only "outbound node-x unavailable: reason" lines, turned into a short reason. */
        @Override public void writeLogs(LogIterator logs) {
            while (logs != null && logs.hasNext()) {
                String m = logs.next().getMessage();
                if (m == null) continue;
                int at = m.indexOf("outbound node-");
                int un = m.indexOf(" unavailable: ");
                if (at < 0 || un < at) continue;
                reasons.put(m.substring(at + "outbound ".length(), un), reason(m.substring(un + " unavailable: ".length())));
            }
        }
        @Override public void writeOutbounds(OutboundGroupItemIterator outbounds) {}
        @Override public void writeStatus(StatusMessage status) {}
    }
}
