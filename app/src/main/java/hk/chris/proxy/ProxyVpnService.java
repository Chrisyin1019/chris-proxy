package hk.chris.proxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.OverrideOptions;

public final class ProxyVpnService extends VpnService {
    static final String ACTION_RELOAD = "hk.chris.proxy.action.RELOAD";
    static final String ACTION_STOP = "hk.chris.proxy.action.STOP";
    private static final int NOTIFICATION_ID = 401;
    private static final String CHANNEL_ID = "chris_proxy";

    enum State { STOPPED, STARTING, RUNNING, FAILED }

    private static volatile State state = State.STOPPED;
    private static volatile String lastError = "";

    static State state() { return state; }
    static String lastError() { return lastError; }

    // Loopback-only SOCKS inbound used by EgressProbe; fresh port and credentials per start.
    static final String PROBE_TAG = "probe-in";
    static final String PROBE_TYPE = "socks";
    private static volatile int probePort;
    private static volatile String probeUser = "";
    private static volatile String probePassword = "";

    static int probePort() { return state == State.RUNNING ? probePort : 0; }
    static String probeUser() { return probeUser; }
    static String probePassword() { return probePassword; }

    /** Asks a running service to rebuild its config after rules/nodes change. */
    static void stop(Context context) {
        if (state == State.STOPPED) return;
        context.startService(new Intent(context, ProxyVpnService.class).setAction(ACTION_STOP));
    }

    static void reloadIfRunning(Context context) {
        if (state != State.RUNNING) return;
        context.startService(new Intent(context, ProxyVpnService.class).setAction(ACTION_RELOAD));
    }

    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();
    private LibboxPlatform platform;
    private CommandServer commandServer;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    private boolean engineStarted;

    private void startIfNeeded() {
        Notification notification = notification();
        if (Build.VERSION.SDK_INT >= 29 && wantsLocationType()) {
            try {
                int types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                        | (Build.VERSION.SDK_INT >= 34 ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
                startForeground(NOTIFICATION_ID, notification, types);
                if (!engineStarted) AppLogger.log(this, "INFO", "前景服務含位置類型：背景也能判斷 Wi-Fi 例外");
            } catch (SecurityException | IllegalStateException e) {
                // Started from the background or permission revoked: run without SSID access.
                AppLogger.log(this, "WARN", "無法以位置類型啟動前景服務，背景時 Wi-Fi 例外可能失效");
                startPlainForeground(notification);
            }
        } else {
            startPlainForeground(notification);
        }
        if (engineStarted) return;
        engineStarted = true;
        state = State.STARTING;
        lastError = "";
        engineExecutor.execute(this::startEngine);
    }

    private void startPlainForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    /** Only when there are Wi-Fi exceptions and location is granted; otherwise no location type. */
    private boolean wantsLocationType() {
        return !PolicyStore.load(this).directSsids.isEmpty()
                && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The system keeps an established VPN service bound, so stopService()
     * alone never tears it down. Close the core and the TUN first, then stop.
     */
    private void shutdown() {
        probePort = 0;
        if (state != State.FAILED) state = State.STOPPED;
        engineExecutor.execute(this::closeEngine);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    private void closeEngine() {
        CommandServer server = commandServer;
        commandServer = null;
        try { if (server != null) server.closeService(); } catch (Exception ignored) {}
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        if (platform != null) platform.closeTun();
        platform = null;
        if (server != null) AppLogger.log(this, "INFO", "代理資料面停止");
    }

    private void startEngine() {
        try {
            AppLogger.log(this, "INFO", "VPN 啟動階段 1/4：讀取節點與規則");
            String config = buildConfig();
            AppLogger.log(this, "INFO", "VPN 啟動階段 2/4：初始化 sing-box");
            LibboxRuntime.ensureSetup(this);
            Libbox.promoteOOMDraft();
            platform = new LibboxPlatform(this);
            platform.onNetworkChanged = () -> {
                CommandServer server = commandServer;
                if (server != null) server.updateWIFIState();
            };
            ProxyCommandHandler handler = new ProxyCommandHandler(this, this::shutdown, this::reloadEngine);
            commandServer = new CommandServer(handler, platform);
            commandServer.start();
            AppLogger.log(this, "INFO", "VPN 啟動階段 3/4：驗證設定");
            Libbox.checkConfig(config);
            commandServer.startOrReloadService(config, new OverrideOptions());
            state = State.RUNNING;
            AppLogger.log(this, "INFO", "VPN 啟動階段 4/4：sing-box 資料面已啟動");
        } catch (Exception e) {
            AppLogger.log(this, "ERROR", "資料面啟動失敗=" + describe(e));
            fail(describe(e));
        }
    }

    private String buildConfig() {
        List<ProxyProfile> profiles = ProxyProfileStore.load(this);
        if (profiles.isEmpty()) throw new IllegalStateException("沒有節點設定");
        ProxyProfile selected = ProxyProfileStore.selected(this);
        for (ProxyProfile profile : profiles) {
            String names = SingBoxConfig.parameterNames(profile);
            AppLogger.log(this, "INFO", "節點 " + profile.id() + " " + profile.type.name()
                    + (names.isEmpty() ? "" : " 參數名稱=" + names));
        }
        String config = SingBoxConfig.build(profiles, selected, PolicyStore.load(this), RoutingStore.load(this),
                RuleSetStore.loadEntries(this));
        // Core log goes to a file next to the app log, so data-plane failures are diagnosable.
        try {
            org.json.JSONObject json = new org.json.JSONObject(config);
            java.io.File core = new java.io.File(getFilesDir(), "logs/core.log");
            // sing-box appends forever; start fresh once it passes 1 MB.
            if (core.length() > 1024 * 1024 && !core.delete()) AppLogger.log(this, "WARN", "無法清理核心記錄");
            json.getJSONObject("log").put("output", core.getPath());
            if (probePort == 0) {
                try (java.net.ServerSocket free = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                    probePort = free.getLocalPort();
                } catch (java.io.IOException e) {
                    probePort = 0;
                }
                probeUser = TransferServer.b64(TransferCrypto.random(9));
                probePassword = TransferServer.b64(TransferCrypto.random(18));
            }
            if (probePort > 0) {
                json.getJSONArray("inbounds").put(new org.json.JSONObject()
                        .put("type", PROBE_TYPE)
                        .put("tag", PROBE_TAG)
                        .put("listen", "127.0.0.1")
                        .put("listen_port", probePort)
                        .put("users", new org.json.JSONArray().put(new org.json.JSONObject()
                                .put("username", probeUser).put("password", probePassword))));
            }
            return json.toString();
        } catch (org.json.JSONException e) {
            return config;
        }
    }

    private void fail(String reason) {
        lastError = reason;
        state = State.FAILED;
        shutdown();
    }

    /**
     * Error text for the log/UI. Our own exceptions carry fixed Chinese
     * messages; anything else is reduced to its type so node secrets that a
     * parser might echo never reach the log.
     */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        // Go errors arrive as go.Universe$proxyerror; their text is the useful part.
        // Anything that looks like it could echo a node URI is reduced to its type.
        if (message == null || message.isEmpty() || message.contains("://")) return e.getClass().getSimpleName();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private void reloadEngine() {
        engineExecutor.execute(() -> {
            try {
                if (commandServer == null || state != State.RUNNING) return;
                String config = buildConfig();
                Libbox.checkConfig(config);
                commandServer.startOrReloadService(config, new OverrideOptions());
                AppLogger.log(this, "INFO", "分流設定已套用（未中斷連線）");
            } catch (Exception e) {
                AppLogger.log(this, "ERROR", "重新載入失敗，沿用舊設定=" + describe(e));
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RELOAD.equals(intent.getAction())) {
            reloadEngine();
            return START_NOT_STICKY;
        }
        startIfNeeded();
        // Not sticky: a crash must not turn into a silent restart loop.
        // Android's always-on VPN setting restarts the service when the user wants that.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        engineExecutor.execute(this::closeEngine);
        if (state != State.FAILED) state = State.STOPPED;
        engineExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        AppLogger.log(this, "WARN", "系統撤銷 VPN 權限");
        shutdown();
        super.onRevoke();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.vpn_notification))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.vpn_channel_name), NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
