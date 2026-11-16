package hk.chris.proxy;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AppLogger {
    private static final long MAX_BYTES = 512 * 1024;
    private AppLogger() {}

    public static synchronized void log(Context context, String level, String message) {
        File dir = new File(context.getFilesDir(), "logs");
        if (!dir.exists() && !dir.mkdirs()) return;
        File file = new File(dir, "proxy.log");
        if (file.length() > MAX_BYTES) {
            File old = new File(dir, "proxy.log.1");
            if (old.exists()) old.delete();
            file.renameTo(old);
        }
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(time + " [" + level + "] " + message.replace('\n', ' ') + "\n");
        } catch (IOException ignored) {
            // Logging must never crash the VPN or UI.
        }
    }

    public static synchronized String tail(Context context, int maxChars) {
        File file = new File(new File(context.getFilesDir(), "logs"), "proxy.log");
        if (!file.exists()) return "尚無 log。";
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
        } catch (IOException e) {
            return "讀取 log 失敗：" + e.getMessage();
        }
    }
}
