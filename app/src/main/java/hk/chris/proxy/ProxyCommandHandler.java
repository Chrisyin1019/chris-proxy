package hk.chris.proxy;

import android.content.Context;

import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.SystemProxyStatus;

final class ProxyCommandHandler implements CommandServerHandler {
    private final Context context;
    private final Runnable stop;
    private final Runnable reload;

    ProxyCommandHandler(Context context, Runnable stop, Runnable reload) {
        this.context = context;
        this.stop = stop;
        this.reload = reload;
    }

    @Override public int connectSSHAgent() { return -1; }
    @Override public SystemProxyStatus getSystemProxyStatus() {
        SystemProxyStatus status = new SystemProxyStatus();
        status.setAvailable(false);
        status.setEnabled(false);
        return status;
    }
    @Override public void serviceReload() { reload.run(); }
    @Override public void serviceStop() { stop.run(); }
    @Override public void setSystemProxyEnabled(boolean enabled) {}
    @Override public void triggerNativeCrash() {}
    @Override public void writeDebugMessage(String message) {
        AppLogger.log(context, "DEBUG", "libbox: " + (message == null ? "" : message));
    }
}
