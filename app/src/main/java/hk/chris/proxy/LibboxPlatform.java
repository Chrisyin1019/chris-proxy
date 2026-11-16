package hk.chris.proxy;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.nekohasekai.libbox.BridgeOptions;
import io.nekohasekai.libbox.BridgeSession;
import io.nekohasekai.libbox.ConnectionOwner;
import io.nekohasekai.libbox.InterfaceUpdateListener;
import io.nekohasekai.libbox.LocalDNSTransport;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.Notification;
import io.nekohasekai.libbox.PlatformInterface;
import io.nekohasekai.libbox.PlatformUser;
import io.nekohasekai.libbox.RoutePrefix;
import io.nekohasekai.libbox.RoutePrefixIterator;
import io.nekohasekai.libbox.ShellSession;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.TunOptions;
import io.nekohasekai.libbox.WIFIState;

final class LibboxPlatform implements PlatformInterface {
    /** App context for everything except the VPN itself. */
    private final Context service;
    /** Null for the test-only engine, which never opens a TUN. */
    private final VpnService vpn;
    private final ConnectivityManager connectivity;
    private ParcelFileDescriptor tun;
    private HandlerThread networkThread;
    private Handler networkHandler;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile Network defaultNetwork;
    private volatile InterfaceUpdateListener interfaceListener;
    /** Called when the underlying network changes, so the core re-reads the Wi-Fi SSID. */
    volatile Runnable onNetworkChanged;
    private volatile String lastWifiVerdict = "";

    LibboxPlatform(VpnService service) {
        this(service, service);
    }

    LibboxPlatform(Context context, VpnService vpn) {
        this.service = context;
        this.vpn = vpn;
        this.connectivity = context.getSystemService(ConnectivityManager.class);
    }

    @Override
    public int openTun(TunOptions options) throws Exception {
        if (vpn == null) throw new IllegalStateException("test engine has no TUN");
        if (VpnService.prepare(service) != null) throw new IllegalStateException("VPN permission is not prepared");
        VpnService.Builder builder = vpn.new Builder()
                .setSession("Chris Proxy")
                .setMtu(options.getMTU() > 0 ? options.getMTU() : 1500);
        addPrefixes(builder, options.getInet4Address());
        addPrefixes(builder, options.getInet6Address());
        addDns(builder, options.getDNSServerAddress());
        if (options.getAutoRoute()) {
            if (addRoutes(builder, options.getInet4RouteAddress(), false) == 0) {
                builder.addRoute("0.0.0.0", 0);
            }
            if (addRoutes(builder, options.getInet6RouteAddress(), true) == 0) {
                builder.addRoute("::", 0);
            }
            addRoutes(builder, options.getInet4RouteExcludeAddress(), false, true);
            addRoutes(builder, options.getInet6RouteExcludeAddress(), true, true);
        }
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
        Network underlying = defaultNetwork;
        if (underlying != null) builder.setUnderlyingNetworks(new Network[]{underlying});
        StringIterator include = options.getIncludePackage();
        if (include != null && include.hasNext()) {
            addAllowed(builder, include, true);
        } else {
            // The app's own latency probes and rule-list downloads go direct,
            // so they measure the real network instead of looping through the tunnel.
            builder.addDisallowedApplication(service.getPackageName());
            addAllowed(builder, options.getExcludePackage(), false);
        }
        ParcelFileDescriptor established = builder.establish();
        if (established == null) throw new IllegalStateException("VPN interface could not be established");
        tun = established;
        AppLogger.log(service, "INFO", "sing-box TUN interface established");
        return established.getFd();
    }

    private void addPrefixes(VpnService.Builder builder, RoutePrefixIterator iterator) {
        while (iterator != null && iterator.hasNext()) {
            RoutePrefix prefix = iterator.next();
            builder.addAddress(prefix.address(), prefix.prefix());
        }
    }

    private int addRoutes(VpnService.Builder builder, RoutePrefixIterator iterator, boolean ipv6) throws Exception {
        return addRoutes(builder, iterator, ipv6, false);
    }

    private int addRoutes(VpnService.Builder builder, RoutePrefixIterator iterator, boolean ipv6, boolean exclude) throws Exception {
        int count = 0;
        while (iterator != null && iterator.hasNext()) {
            RoutePrefix prefix = iterator.next();
            if (exclude && Build.VERSION.SDK_INT >= 33) {
                builder.excludeRoute(new IpPrefix(InetAddress.getByName(prefix.address()), prefix.prefix()));
            }
            else if (!exclude) {
                builder.addRoute(prefix.address(), prefix.prefix());
            }
            count++;
        }
        return count;
    }

    private void addDns(VpnService.Builder builder, StringIterator iterator) {
        while (iterator != null && iterator.hasNext()) builder.addDnsServer(iterator.next());
    }

    private void addAllowed(VpnService.Builder builder, StringIterator iterator, boolean allow) throws Exception {
        while (iterator != null && iterator.hasNext()) {
            if (allow) builder.addAllowedApplication(iterator.next());
            else builder.addDisallowedApplication(iterator.next());
        }
    }

    void closeTun() {
        if (tun != null) {
            try { tun.close(); } catch (Exception ignored) {}
            tun = null;
        }
        stopNetworkMonitor();
    }

    @Override public void autoDetectInterfaceControl(int fd) {
        // Without a VPN (test engine) sockets already use the physical network.
        if (vpn != null) vpn.protect(fd);
    }
    @Override public boolean usePlatformAutoDetectInterfaceControl() { return true; }
    @Override public boolean useProcFS() { return Build.VERSION.SDK_INT < 29; }
    @Override public boolean includeAllNetworks() { return false; }
    @Override public boolean underNetworkExtension() { return false; }
    @Override public boolean usePlatformBridge() { return false; }
    @Override public boolean usePlatformShell() { return false; }
    @Override public String tailscaleHostname() { return ""; }
    @Override public void registerMyInterface(String name) {}
    @Override public void clearDNSCache() {}
    @Override public synchronized void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        interfaceListener = listener;
        if (connectivity == null || networkCallback != null) return;
        networkThread = new HandlerThread("chris-proxy-network-monitor");
        networkThread.start();
        networkHandler = new Handler(networkThread.getLooper());
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { updateDefaultNetwork(network); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (network.equals(defaultNetwork)) updateDefaultNetwork(network);
            }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                if (network.equals(defaultNetwork)) notifyInterface(network, properties);
            }
            @Override public void onLost(Network network) {
                if (network.equals(defaultNetwork)) {
                    defaultNetwork = null;
                    if (vpn != null) vpn.setUnderlyingNetworks(null);
                    InterfaceUpdateListener current = interfaceListener;
                    if (current != null) current.updateDefaultInterface("", -1, false, false);
                    networkChanged();
                    AppLogger.log(service, "WARN", "底層網路已中斷，等待新的 Wi-Fi 或行動網路");
                }
            }
        };
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                connectivity.registerBestMatchingNetworkCallback(request, networkCallback, networkHandler);
            } else if (Build.VERSION.SDK_INT >= 28) {
                connectivity.requestNetwork(request, networkCallback, networkHandler);
            } else {
                connectivity.registerDefaultNetworkCallback(networkCallback, networkHandler);
            }
            Network active = connectivity.getActiveNetwork();
            if (active != null) updateDefaultNetwork(active);
        } catch (RuntimeException error) {
            AppLogger.log(service, "WARN", "底層網路監聽啟動失敗=" + error.getClass().getSimpleName());
        }
    }

    @Override public synchronized void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        if (interfaceListener == listener) interfaceListener = null;
        stopNetworkMonitor();
    }

    private void networkChanged() {
        Runnable hook = onNetworkChanged;
        if (hook != null) {
            try { hook.run(); } catch (Exception ignored) {}
        }
    }

    private void updateDefaultNetwork(Network network) {
        defaultNetwork = network;
        networkChanged();
        if (vpn != null) vpn.setUnderlyingNetworks(new Network[]{network});
        LinkProperties properties = connectivity.getLinkProperties(network);
        if (properties != null) notifyInterface(network, properties);
    }

    private void notifyInterface(Network network, LinkProperties properties) {
        String name = properties.getInterfaceName();
        if (name == null || name.isEmpty()) return;
        try {
            NetworkInterface systemInterface = NetworkInterface.getByName(name);
            if (systemInterface == null) return;
            InterfaceUpdateListener current = interfaceListener;
            if (current != null) {
                current.updateDefaultInterface(name, systemInterface.getIndex(), false, false);
                AppLogger.log(service, "INFO", "底層網路已更新=" + name);
            }
        } catch (Exception error) {
            AppLogger.log(service, "WARN", "底層介面更新失敗=" + error.getClass().getSimpleName());
        }
    }

    private synchronized void stopNetworkMonitor() {
        if (connectivity != null && networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        }
        networkCallback = null;
        defaultNetwork = null;
        if (networkThread != null) networkThread.quitSafely();
        networkThread = null;
        networkHandler = null;
    }
    @Override public void startNeighborMonitor(io.nekohasekai.libbox.NeighborUpdateListener listener) {}
    @Override public void closeNeighborMonitor(io.nekohasekai.libbox.NeighborUpdateListener listener) {}
    @Override public void sendNotification(Notification notification) {}
    @Override public void cancelNotification(String identifier, int typeId) {}
    @Override public void checkPlatformShell() {}
    @Override public String lookupSFTPServer() { return ""; }
    @Override public String readSystemSSHHostKey() { return ""; }
    @Override public PlatformUser lookupUser(String name) { return null; }
    @Override public ShellSession openShellSession(PlatformUser user, String command, StringIterator environment, String workdir, int uid, int gid) { return null; }
    @Override public BridgeSession createBridge(BridgeOptions options) { return null; }
    /**
     * DNS for sing-box's "local" server. Without it the core falls back to
     * resolv.conf ([::1]:53), which does not exist on Android, and every
     * lookup fails. Queries go to the current physical network, not the tunnel.
     */
    @Override public LocalDNSTransport localDNSTransport() {
        return new LocalDNSTransport() {
            @Override public boolean raw() { return Build.VERSION.SDK_INT >= 29; }

            // Only called when raw() is true, i.e. API 29+.
            @androidx.annotation.RequiresApi(29)
            @Override public void exchange(io.nekohasekai.libbox.ExchangeContext ctx, byte[] message) throws Exception {
                Network network = currentNetwork();
                if (network == null) { ctx.errnoCode(android.system.OsConstants.ENETDOWN); return; }
                java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                android.os.CancellationSignal cancel = new android.os.CancellationSignal();
                ctx.onCancel(cancel::cancel);
                android.net.DnsResolver.getInstance().rawQuery(network, message, android.net.DnsResolver.FLAG_NO_RETRY,
                        Runnable::run, cancel, new android.net.DnsResolver.Callback<byte[]>() {
                            @Override public void onAnswer(byte[] answer, int rcode) {
                                ctx.rawSuccess(answer);
                                done.countDown();
                            }
                            @Override public void onError(android.net.DnsResolver.DnsException error) {
                                if (error.getCause() instanceof android.system.ErrnoException) {
                                    ctx.errnoCode(((android.system.ErrnoException) error.getCause()).errno);
                                } else {
                                    ctx.errorCode(error.code);
                                }
                                done.countDown();
                            }
                        });
                done.await();
            }

            @Override public void lookup(io.nekohasekai.libbox.ExchangeContext ctx, String network, String domain) throws Exception {
                Network underlying = currentNetwork();
                if (underlying == null) { ctx.errnoCode(android.system.OsConstants.ENETDOWN); return; }
                try {
                    StringBuilder out = new StringBuilder();
                    for (InetAddress address : underlying.getAllByName(domain)) {
                        boolean v6 = address instanceof java.net.Inet6Address;
                        if ("ip4".equals(network) && v6 || "ip6".equals(network) && !v6) continue;
                        if (out.length() > 0) out.append('\n');
                        String host = address.getHostAddress();
                        int zone = host.indexOf('%');
                        out.append(zone >= 0 ? host.substring(0, zone) : host);
                    }
                    ctx.success(out.toString());
                } catch (java.net.UnknownHostException e) {
                    ctx.errorCode(3); // NXDOMAIN
                }
            }
        };
    }

    private Network currentNetwork() {
        Network network = defaultNetwork;
        return network != null || connectivity == null ? network : connectivity.getActiveNetwork();
    }
    /**
     * Lets sing-box match package_name rules. Android only answers this for
     * the active VPN app, and only on API 29+; older systems fall back to procfs.
     */
    @Override public ConnectionOwner findConnectionOwner(int protocol, String sourceAddress, int sourcePort, String destinationAddress, int destinationPort) {
        ConnectionOwner owner = new ConnectionOwner();
        if (Build.VERSION.SDK_INT < 29 || connectivity == null) return owner;
        try {
            int uid = connectivity.getConnectionOwnerUid(protocol,
                    new java.net.InetSocketAddress(InetAddress.getByName(sourceAddress), sourcePort),
                    new java.net.InetSocketAddress(InetAddress.getByName(destinationAddress), destinationPort));
            if (uid == android.os.Process.INVALID_UID) return owner;
            owner.setUserId(uid);
            String[] packages = service.getPackageManager().getPackagesForUid(uid);
            if (packages != null) {
                owner.setAndroidPackageNames(LibboxIterators.strings(java.util.Arrays.asList(packages)));
            }
        } catch (Exception ignored) {
            // Unknown owner simply means package rules do not match this connection.
        }
        return owner;
    }

    @Override public WIFIState readWIFIState() {
        String ssid = SsidResolver.currentSsid(service);
        String verdict = ssid.startsWith("<") ? "讀不到（" + ssid + "）"
                : PolicyStore.load(service).directSsids.contains(ssid.trim()) ? "命中 Wi-Fi 例外" : "非例外 Wi-Fi";
        if (!verdict.equals(lastWifiVerdict)) {
            lastWifiVerdict = verdict;
            AppLogger.log(service, "INFO", "核心讀取 Wi-Fi：" + verdict);
        }
        return new WIFIState(ssid.startsWith("<") ? "" : ssid, "");
    }

    /**
     * Physical networks as sing-box sees them. Flags must be Linux IFF_* values;
     * with flags 0 every interface looks down and the core reports
     * "no available network interface" for all direct dials, including DNS.
     */
    @Override public NetworkInterfaceIterator getInterfaces() {
        ArrayList<io.nekohasekai.libbox.NetworkInterface> result = new ArrayList<>();
        if (connectivity == null) return LibboxIterators.networkInterfaces(result);
        for (Network network : connectivity.getAllNetworks()) {
            try {
                LinkProperties link = connectivity.getLinkProperties(network);
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
                if (link == null || caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                String name = link.getInterfaceName();
                if (name == null) continue;
                NetworkInterface system = NetworkInterface.getByName(name);
                if (system == null) continue;
                io.nekohasekai.libbox.NetworkInterface out = new io.nekohasekai.libbox.NetworkInterface();
                out.setName(name);
                out.setIndex(system.getIndex());
                out.setMTU(system.getMTU());
                List<String> addresses = new ArrayList<>();
                for (android.net.LinkAddress address : link.getLinkAddresses()) {
                    // Java appends "%iface" to link-local IPv6; Go's netip.ParsePrefix
                    // panics on zones and takes the whole process down.
                    String host = address.getAddress().getHostAddress();
                    int zone = host.indexOf('%');
                    if (zone >= 0) host = host.substring(0, zone);
                    addresses.add(host + "/" + address.getPrefixLength());
                }
                out.setAddresses(LibboxIterators.strings(addresses));
                List<String> dns = new ArrayList<>();
                for (InetAddress server : link.getDnsServers()) {
                    String host = server.getHostAddress();
                    int zone = host.indexOf('%');
                    dns.add(zone >= 0 ? host.substring(0, zone) : host);
                }
                out.setDNSServer(LibboxIterators.strings(dns));
                out.setGateway(LibboxIterators.strings(Collections.emptyList()));
                int flags = 0;
                if (system.isUp()) flags |= android.system.OsConstants.IFF_UP | android.system.OsConstants.IFF_RUNNING;
                if (system.isLoopback()) flags |= android.system.OsConstants.IFF_LOOPBACK;
                if (system.isPointToPoint()) flags |= android.system.OsConstants.IFF_POINTOPOINT;
                if (system.supportsMulticast()) flags |= android.system.OsConstants.IFF_MULTICAST;
                out.setFlags(flags);
                out.setType(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? io.nekohasekai.libbox.Libbox.InterfaceTypeWIFI
                        : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? io.nekohasekai.libbox.Libbox.InterfaceTypeCellular
                        : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? io.nekohasekai.libbox.Libbox.InterfaceTypeEthernet
                        : io.nekohasekai.libbox.Libbox.InterfaceTypeOther);
                out.setMetered(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
                result.add(out);
            } catch (Exception ignored) {
                // Skip interfaces that vanish while being enumerated.
            }
        }
        return LibboxIterators.networkInterfaces(result);
    }

}
