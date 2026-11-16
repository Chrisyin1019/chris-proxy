package hk.chris.proxy;

import android.content.Context;

import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.SetupOptions;

/**
 * One-time libbox setup for the whole process. Both the VPN service and the
 * in-app latency test engine need the base/working/temp directories before a
 * CommandServer or CommandClient can open the control socket.
 */
final class LibboxRuntime {
    private static boolean ready;

    private LibboxRuntime() {}

    static synchronized void ensureSetup(Context context) throws Exception {
        if (ready) return;
        Context app = context.getApplicationContext();
        java.io.File working = new java.io.File(app.getFilesDir(), "sing-box");
        if (!working.exists() && !working.mkdirs()) throw new IllegalStateException("無法建立 sing-box 工作目錄");
        SetupOptions options = new SetupOptions();
        options.setBasePath(app.getFilesDir().getPath());
        options.setWorkingPath(working.getPath());
        options.setTempPath(app.getCacheDir().getPath());
        options.setFixAndroidStack(true);
        options.setLogMaxLines(3000);
        options.setDebug(false);
        Libbox.setup(options);
        ready = true;
    }
}
