package hk.chris.proxy;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

public final class SsidResolver {
    private SsidResolver() {}

    public static String currentSsid(Context context) {
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            return "<需要附近 Wi-Fi 權限>";
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return "<需要精確位置權限>";
        }
        LocationManager location = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 28 && location != null && !location.isLocationEnabled()) {
            return "<系統位置服務未開啟>";
        }
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return "<Wi-Fi 不可用>";
            WifiInfo info = wifi.getConnectionInfo();
            if (info == null || WifiManager.UNKNOWN_SSID.equals(info.getSSID())) return "<未知 SSID>";
            String ssid = info.getSSID();
            if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            return ssid;
        } catch (SecurityException e) {
            return "<SSID 權限不足>";
        }
    }
}
