# 正式版發布

## 目前使用的簽章

1.0.0 起以舊版中性代理的金鑰 `app/keys/neutralproxy-debug.keystore`（alias `neutralproxy-debug`）簽 release，憑證 SHA-256：
`0fc9f8999de850d04d5b4bab0258f905f7be61aae7778a77a0a7c6dfb79beae9`

- 此 keystore 不進 Git（`.gitignore` 已排除），請另行備份；遺失就無法再覆蓋升級。
- 它的密碼寫在 `app/build.gradle.kts` 的 debug 設定中，拿到 keystore 檔的人就能簽出可覆蓋安裝的 APK，檔案請妥善保管。
- **這個檔案不得放進專案資料夾、壓縮包或任何上傳位置。** App 內更新只比對 APK 簽章憑證，金鑰外流等於可推送會被接受的惡意更新。
- 套件 ID 不同（`hk.chris.proxy` vs `app.neutralproxy.local`），同一把金鑰也不會讓 Chris Proxy 覆蓋舊版，兩者仍是不同 App。

## 一次性：建立 release 金鑰（若改用新金鑰）

金鑰與密碼放在專案外，與 Chris Secure、ERP App 相同做法；**遺失金鑰就無法再覆蓋升級**，請另行備份。

```bash
keytool -genkeypair -keystore ~/Android/chris-proxy-release.keystore \
  -alias chris-proxy -keyalg RSA -keysize 4096 -validity 10000
```

建立 `~/Android/chris-proxy-release-keystore.properties`（權限 600，不進 Git）：

```properties
storeFile=$HOME/Android/chris-proxy-release.keystore
storePassword=…
keyAlias=chris-proxy
keyPassword=…
```

可用環境變數 `CHRIS_PROXY_RELEASE_PROPERTIES` 指向其他位置。找不到此檔時 `assembleRelease` 只產生未簽章 APK。

## 每次發布

1. 調高 `app/build.gradle.kts` 的 `versionCode` 與 `versionName`。
2. 測試、lint、建置與驗證簽章：

```bash
./gradlew clean :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk
```

3. 依 `TESTING.md` 完成實機驗收後才發布。
4. 更新更新伺服器的 JSON：`versionCode` 必須大於目前安裝版、`url` 與 APK 同主機、`sha256` 用上面算出的值；否則 App 會顯示「已是最新版本」或拒絕下載。

## 從舊版「中性代理」改用 Chris Proxy

舊版 ID 為 `app.neutralproxy.local`，Chris Proxy 是 `hk.chris.proxy`，兩者是不同 App、可同時安裝，資料不會自動搬移：

1. 在舊版「導出節點」，於 Chris Proxy「節點 → 新增節點」貼上。
2. 分流規則需重新設定或匯入清單。
3. 確認無誤後解除安裝舊版（兩個 VPN App 同時只能有一個啟用）。

debug 版為 `hk.chris.proxy.debug`，可與正式版並存。
