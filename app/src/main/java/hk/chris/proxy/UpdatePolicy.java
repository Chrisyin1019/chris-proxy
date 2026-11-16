package hk.chris.proxy;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.net.URL;

/**
 * Validates update manifest endpoints and package download targets.
 * Never embed sensitive server hosts or credentials in source code.
 */
final class UpdatePolicy {
    static final String PREF_KEY_UPDATE_URL = "app_update_url";

    static String defaultFeed() {
        return BuildConfig.DEFAULT_UPDATE_URL != null ? BuildConfig.DEFAULT_UPDATE_URL.trim() : "";
    }

    static String getUpdateUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("chris_proxy_settings", Context.MODE_PRIVATE);
        String saved = prefs.getString(PREF_KEY_UPDATE_URL, "").trim();
        if (!saved.isEmpty()) {
            return saved;
        }
        return defaultFeed();
    }

    static void setUpdateUrl(Context context, String url) {
        SharedPreferences prefs = context.getSharedPreferences("chris_proxy_settings", Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_KEY_UPDATE_URL, url != null ? url.trim() : "").apply();
    }

    static URL checkedUrl(String address) throws Exception {
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("更新網址不能為空");
        }
        String trimmed = address.trim();
        URL url = new URI(trimmed).toURL();
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("必須使用安全 HTTPS 網址");
        }
        if (url.getUserInfo() != null || url.getRef() != null) {
            throw new IllegalArgumentException("更新網址不允許包含帳號密碼或標籤");
        }
        if (url.getHost() == null || url.getHost().trim().isEmpty()) {
            throw new IllegalArgumentException("更新網址缺少主機名稱");
        }
        String path = url.getPath();
        if (path.contains("..") || path.contains("\\")) {
            throw new IllegalArgumentException("無效的更新路徑");
        }
        return url;
    }

    static URL downloadUrl(String downloadAddress, String feedAddress) throws Exception {
        URL target = checkedUrl(downloadAddress);
        URL feed = checkedUrl(feedAddress);
        int targetPort = target.getPort() == -1 ? target.getDefaultPort() : target.getPort();
        int feedPort = feed.getPort() == -1 ? feed.getDefaultPort() : feed.getPort();
        if (!target.getHost().equalsIgnoreCase(feed.getHost()) || targetPort != feedPort) {
            throw new IllegalArgumentException("更新套件下載來源與資訊清單伺服器不同，已拒絕下載");
        }
        return target;
    }
}
