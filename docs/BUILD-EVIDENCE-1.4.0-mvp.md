# 1.4.0-mvp 建置證據

日期：2026-10-25
分支：`fork/fcitx-rule-mvp`

## 自動檢查

- 專案正式 Gradle Wrapper 以 Android SDK 36／JDK 17 完整 clean build 成功；126 個 task（125 executed、1 up-to-date）。
- JVM 測試：49/49 通過，包含 7 個 `TransferTest` LAN／加密轉移測試。
- Espresso 儀器測試：Pixel 9 Pro（Android 17）2/2 通過；涵蓋空詞庫頁，以及新增詞條後重建 Activity 仍保存。
- `lintDebug`：通過。
- `checkDebugDuplicateClasses`／`checkReleaseDuplicateClasses`：通過。
- `assembleDebug`／`assembleRelease`：通過。
- release 的 lint vital、resource optimization、dex、封裝及簽章：通過。
- `app/src` 內沒有 `.kt` 原始碼，Android App 維持純 Java。

正式驗證命令：

```bash
ANDROID_HOME=$ANDROID_HOME \
JAVA_HOME=$JAVA_HOME \
./gradlew --no-daemon clean \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleRelease \
  :app:connectedDebugAndroidTest
```

## Release APK

- 路徑：`app/build/outputs/apk/release/app-release.apk`
- 套件：`hk.chris.proxy`
- versionCode：`15`
- versionName：`1.4.0-mvp`
- ABI：`arm64-v8a`
- SHA-256：`b236047dc68a7c9a8be0250a35344eca1e3f2ccfaba7c6dd9edec2d69fe87687`
- 簽章：APK Signature Scheme v2 驗證通過
- 簽章憑證 SHA-256：`0fc9f8999de850d04d5b4bab0258f905f7be61aae7778a77a0a7c6dfb79beae9`

DEX 檢查確認 release APK 包含 `ProxyRuleAdapter`、`FcitxVocabularyAdapter` 與詞庫持久化類別。

## Android 17 實機驗收

- 裝置：Pixel 9 Pro（`caiman`），Android 17／SDK 37，build `CP3A.261005.005`（`2026102100`）。
- 以同一簽章從已安裝的 1.2.0（versionCode 13）覆蓋安裝至 1.4.0-mvp（versionCode 15）成功。
- App 原始 `firstInstallTime` 與既有節點 `<HY2 節點>` 均保留，沒有清除使用者資料。
- 冷啟動成功（549 ms），Activity 位於 resumed 狀態；啟動後 logcat 沒有 `FATAL EXCEPTION`。
- 實際由抽屜進入「個人詞庫（0）」並顯示新增、匯入、匯出、版本回退、搜尋與空狀態；未為了手動驗收寫入正式 App 詞庫。
- VPN 啟動後 Android 回報 `VALIDATED`，`tun0` 同時具備 IPv4／IPv6 位址與預設路由；行動網路下首頁顯示「經由 <HY2 節點> · 規則模式」，實際出口為 `<節點出口 IP>`（東京）。
- 連線中由行動網路切回 `公司 Wi-Fi` 後，底層網路由 CELLULAR 切為 WIFI，首頁依既有例外規則顯示「直連 · Wi-Fi 公司 Wi-Fi 例外」。測試後已中斷 VPN 並恢復 Wi-Fi 連線。

## 尚未成立的驗收

- 沒有 Fcitx5 Android fork 原始碼，故沒有把詞庫寫入運行中 IME，也沒有跨裝置同步。
- 沒有指定發布伺服器／下載頁，故沒有對外上傳；release APK 已完成本機交付準備。
