package hk.chris.proxy;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/**
 * Handles checking, downloading, verifying, and installing Chris Proxy updates.
 * Strictly verifies SHA-256 and APK signing certificate before handing off to the OS.
 * All UI updates pass through {@link UpdateUiGate}, so a finished Activity never
 * receives a dialog or progress update from a worker that outlived it.
 */
final class ProxyUpdater {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final long MAX_APK = 256L * 1024 * 1024;
    private final AppCompatActivity activity;
    private final UpdateUiGate ui;
    private AlertDialog progressDialog;
    private LifecycleEventObserver destroyObserver;

    ProxyUpdater(AppCompatActivity activity) {
        this.activity = activity;
        this.ui = new UpdateUiGate(() -> !activity.isFinishing() && !activity.isDestroyed(),
                activity::runOnUiThread, BUSY);
        destroyObserver = (source, event) -> {
            if (event == Lifecycle.Event.ON_DESTROY) {
                ui.destroy(() -> dismissProgress(progressDialog));
                source.getLifecycle().removeObserver(destroyObserver);
            }
        };
        activity.getLifecycle().addObserver(destroyObserver);
    }

    void check() {
        check(null);
    }

    void check(String customFeedUrl) {
        if (!ui.begin()) {
            message("檢查中", "更新檢查已在進行中，請稍候。");
            return;
        }

        String feedUrl = customFeedUrl != null && !customFeedUrl.trim().isEmpty()
                ? customFeedUrl.trim()
                : UpdatePolicy.getUpdateUrl(activity);

        if (feedUrl == null || feedUrl.isEmpty()) {
            ui.complete(() -> {}, () -> message("尚未設定更新伺服器", "請至「設定」中設定更新伺服器網址。"));
            return;
        }

        AlertDialog waiting = new AlertDialog.Builder(activity)
                .setTitle("檢查 Chris Proxy 更新")
                .setMessage("正在連線更新伺服器…")
                .setCancelable(false)
                .create();
        progressDialog = waiting;
        if (!showDialog(waiting)) {
            ui.complete(() -> dismissProgress(waiting), () -> {});
            return;
        }

        new Thread(() -> {
            try {
                HttpsURLConnection connection = open(feedUrl);
                JSONObject manifest;
                try (InputStream input = connection.getInputStream()) {
                    requireOk(connection);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (bytes.size() + n > 32768) {
                            throw new Exception("更新資訊過大");
                        }
                        bytes.write(buffer, 0, n);
                    }
                    manifest = new JSONObject(bytes.toString("UTF-8"));
                } finally {
                    connection.disconnect();
                }

                String pkg = manifest.optString("package", "");
                if (!pkg.isEmpty() && !activity.getPackageName().equals(pkg)) {
                    throw new Exception("更新資訊套件名稱不符（" + pkg + "）");
                }

                long code = manifest.getLong("versionCode");
                String sha = manifest.getString("sha256");
                if (!sha.matches("[a-fA-F0-9]{64}")) {
                    throw new Exception("缺少有效 SHA-256 校驗值");
                }

                String downloadAddress = manifest.getString("url");
                UpdatePolicy.downloadUrl(downloadAddress, feedUrl);

                PackageInfo installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                long currentVersion = version(installed);

                ui.complete(() -> dismissProgress(waiting), () -> {
                    if (code <= currentVersion) {
                        message("已是最新版本", "目前安裝的 Chris Proxy " + installed.versionName + " 已是最新版本。");
                    } else {
                        String newVer = manifest.optString("version", String.valueOf(code));
                        String notes = manifest.optString("notes", "有可用的更新版本。");
                        showDialog(new AlertDialog.Builder(activity)
                                .setTitle("發現新版本 Chris Proxy " + newVer)
                                .setMessage(notes)
                                .setNegativeButton("稍後", null)
                                .setPositiveButton("立即更新",
                                        (d, w) -> ui.runIfAlive(() -> download(manifest, feedUrl)))
                                .create());
                    }
                });
            } catch (Exception error) {
                ui.complete(() -> dismissProgress(waiting), () -> {
                    String msg = error.getMessage() != null ? error.getMessage() : "連線逾時或憑證無效";
                    message("檢查更新失敗", "請確認網路連線、憑證及更新網址設定。\n\n詳細原因：" + msg);
                });
            }
        }, "chris-proxy-update-check").start();
    }

    private void download(JSONObject manifest, String feedUrl) {
        if (!ui.begin()) return;

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        TextView label = new TextView(activity);
        label.setText("準備下載…");
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);

        layout.addView(label);
        layout.addView(bar);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("下載 Chris Proxy 更新")
                .setView(layout)
                .setCancelable(false)
                .create();
        progressDialog = dialog;
        if (!showDialog(dialog)) {
            ui.complete(() -> dismissProgress(dialog), () -> {});
            return;
        }

        new Thread(() -> {
            File partial = null;
            try {
                File dir = new File(activity.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new Exception("無法建立暫存目錄");
                }
                partial = new File(dir, "chris-proxy.apk.part");
                String downloadUrl = manifest.getString("url");
                UpdatePolicy.downloadUrl(downloadUrl, feedUrl);

                HttpsURLConnection connection = open(downloadUrl);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");

                try (InputStream input = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(partial)) {
                    requireOk(connection);
                    long expected = connection.getContentLengthLong();
                    if (expected > MAX_APK) {
                        throw new Exception("更新套件容量超出上限");
                    }
                    long size = 0;
                    long lastProgress = 0;
                    byte[] buffer = new byte[32768];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        size += n;
                        if (size > MAX_APK) throw new Exception("更新套件過大");
                        out.write(buffer, 0, n);
                        digest.update(buffer, 0, n);
                        if (size - lastProgress >= 131072) {
                            lastProgress = size;
                            final long current = size;
                            ui.progress(() -> {
                                bar.setIndeterminate(expected <= 0);
                                if (expected > 0) {
                                    bar.setProgress((int) (current * 100 / expected));
                                }
                                label.setText(String.format(java.util.Locale.ROOT, "%.1f MB", current / 1048576.0));
                            });
                        }
                    }
                    if (expected >= 0 && size != expected) {
                        throw new Exception("下載不完整");
                    }
                    out.getFD().sync();
                } finally {
                    connection.disconnect();
                }

                ui.progress(() -> label.setText("正在驗證套件完整性及簽章…"));
                if (!hex(digest.digest()).equalsIgnoreCase(manifest.getString("sha256"))) {
                    throw new Exception("SHA-256 校驗不符");
                }

                verifyPackage(partial, manifest.getLong("versionCode"));

                File apk = new File(dir, "chris-proxy.apk");
                if (!partial.renameTo(apk)) {
                    throw new Exception("無法儲存已完成的安裝檔");
                }

                ui.complete(() -> dismissProgress(dialog), () -> showDialog(new AlertDialog.Builder(activity)
                        .setTitle("驗證完成")
                        .setMessage("已下載並通過簽章安全驗證，準備安裝更新。")
                        .setNegativeButton("稍後", null)
                        .setPositiveButton("立即安裝", (d, w) -> ui.runIfAlive(() -> install(apk)))
                        .create()));
            } catch (Exception error) {
                if (partial != null) partial.delete();
                ui.complete(() -> dismissProgress(dialog), () -> {
                    String msg = error.getMessage() != null ? error.getMessage() : "下載或校驗失敗";
                    message("下載失敗", "未安裝更新，現有版本不受影響。\n\n詳細原因：" + msg);
                });
            }
        }, "chris-proxy-update-download").start();
    }

    private void verifyPackage(File apk, long expected) throws Exception {
        PackageManager pm = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo current = pm.getPackageInfo(activity.getPackageName(), flags);
        PackageInfo candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (candidate == null || !activity.getPackageName().equals(candidate.packageName)
                || version(candidate) != expected || version(candidate) <= version(current)) {
            throw new Exception("更新套件名稱或版本號無效");
        }
        if (!Arrays.equals(signers(current), signers(candidate))) {
            throw new Exception("更新套件簽名證書與現有 App 不符，已終止安裝");
        }
    }

    private static String[] signers(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? info.signingInfo.getApkContentsSigners() : info.signatures;
        if (signatures == null || signatures.length == 0) throw new Exception("缺少簽名資訊");
        String[] result = new String[signatures.length];
        for (int i = 0; i < signatures.length; i++) {
            result[i] = hex(MessageDigest.getInstance("SHA-256").digest(signatures[i].toByteArray()));
        }
        Arrays.sort(result);
        return result;
    }

    private void install(File apk) {
        ui.runIfAlive(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                    showDialog(new AlertDialog.Builder(activity)
                            .setTitle("需要安裝權限")
                            .setMessage("請允許 Chris Proxy 安裝未知來源應用程式，完成後即可繼續更新。")
                            .setPositiveButton("前往設定", (d, w) -> ui.runIfAlive(() -> {
                                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + activity.getPackageName())));
                                showDialog(new AlertDialog.Builder(activity)
                                        .setTitle("安裝更新")
                                        .setMessage("設定完成後請按繼續安裝。")
                                        .setPositiveButton("安裝", (x, y) -> install(apk))
                                        .setNegativeButton("取消", null)
                                        .create());
                            }))
                            .setNegativeButton("取消", null)
                            .create());
                    return;
                }
                Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", apk);
                Intent intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
            } catch (Exception error) {
                message("無法啟動系統安裝程式", "請稍後重新嘗試。");
            }
        });
    }

    private static HttpsURLConnection open(String address) throws Exception {
        URL url = UpdatePolicy.checkedUrl(address);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setUseCaches(false);
        return connection;
    }

    private static void requireOk(HttpsURLConnection connection) throws Exception {
        if (connection.getResponseCode() != 200) {
            throw new Exception("HTTP 伺服器回應碼：" + connection.getResponseCode());
        }
    }

    private static long version(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        }
        return result.toString();
    }

    private boolean showDialog(AlertDialog dialog) {
        if (!ui.isAlive()) return false;
        try {
            dialog.show();
            return true;
        } catch (android.view.WindowManager.BadTokenException | IllegalStateException unavailableWindow) {
            UpdateUiGate.dismissSafely(dialog::dismiss);
            return false;
        }
    }

    private void dismissProgress(AlertDialog dialog) {
        if (dialog == null) return;
        if (progressDialog == dialog) progressDialog = null;
        UpdateUiGate.dismissSafely(dialog::dismiss);
    }

    private void message(String title, String text) {
        if (!ui.isAlive()) return;
        showDialog(new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton("確定", null)
                .create());
    }
}
