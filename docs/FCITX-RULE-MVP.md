# Fcitx5 復用架構：現有代理規則 MVP

## 基準與範圍

- Repo：`Chris-Proxy/chris-proxy`
- 起始 commit：`8e859c3a8fad7222abc5099f35e7c70630916e10`
- 分支：`fork/fcitx-rule-mvp`
- App：`hk.chris.proxy`，版本 `1.4.0-mvp`

1.3.0 MVP 解決「沿用 Chris Proxy 的既有 sing-box 代理，安全地增加及管理規則」；1.4.0 再加入離線個人詞庫與 Fcitx/Rime 匯出邊界。沒有新增 WebSocket、同步 Server、DNS、Firewall、VPS Bridge 或 Chris Secure 權限，也沒有把輸入法、詞條或剪貼簿內容放進代理記錄。

## 復用點

| 能力 | 既有路徑 | MVP 做法 |
| --- | --- | --- |
| 代理資料面 | `ProxyVpnService` → `SingBoxConfig` → sing-box `libbox` | 完全沿用，不另建通道 |
| 規則持久化 | `RoutingStore` / Android SharedPreferences | 相容舊資料，加入版本歷史 |
| 有序規則與 UI | `RouteRule` / `Routing` / `MainActivity` | 保留新增、編輯、刪除、上下移；加入啟停、dry-run、回退 |
| 規則集 | `RuleSetStore` / `RuleListParser` | dry-run 與資料面共用同一份規則集 |
| 熱重載 | `ProxyVpnService.reloadIfRunning()` | 儲存或回退後沿用既有熱重載 |

## 資料模型與相容性

`RouteRule` 新增 `enabled`。新序列格式為：

```text
MATCH<TAB>value<TAB>target<TAB>1|0
```

舊版三欄資料仍可讀取，預設為啟用。停用規則保留原位置及內容，但 `ProxyRuleAdapter.enabledRules()` 不會將它交給 `SingBoxConfig`。

本機規則歷史只包含：模式、FINAL、規則類型、規則值、目標節點 ID 與啟用狀態。它不包含節點 URI、密碼、剪貼簿內容或 dry-run 樣本。最多保留 20 版；回退前先保存目前版本，所以可以再次復原。

## Adapter 邊界

`ProxyRuleAdapter` 是規則層與既有代理的唯一新增邊界：

1. Production：把啟用的有序規則交給既有 `SingBoxConfig`。
2. Dry-run：以同樣的順序在本機測試網域、IP/CIDR、套件名、目的埠與 RULE-SET。
3. 第一條符合即停止；無符合時回傳 FINAL；全局代理／全部直連依現有模式處理。

Dry-run 不開網路、不改設定、不保存測試值、不寫 log。

## 個人詞庫邊界

`VocabularyRepository` 保存平台中立的詞條、讀音／編碼、權重與啟用狀態，不依賴 Fcitx 或 Rime 的運行中資料庫：

1. App 私有目錄原子寫入，最多 5,000 條。
2. 每次有效修改保留變更前版本，最多 10 版；回退前保存目前版本。
3. `VocabularyImporter` 支援純文字、TSV 及 Rime 字典正文，匯入前由 UI 顯示統計並要求確認。
4. `FcitxVocabularyAdapter` 只在使用者明確匯出時生成 Rime YAML 或中立 TSV；不直接寫入 IME。
5. log 只記錄條數與錯誤類型，不記錄詞條內容。

## 使用方式

1. 左側選單 →「分流規則」。
2. 「新增規則」建立網域／IP／App／埠或規則集規則。
3. 點規則可啟停、編輯、上移、下移或刪除。
4. 「測試規則」輸入一組假資料，查看第一條符合規則及去向。
5. 「版本與回退」選擇舊版；回退後 VPN 若正在運行會沿用既有熱重載。

## 驗證證據

- 修改過的 production Java（包含 `MainActivity`）以 Android SDK 36、JDK 17 及專案現有依賴編譯成功。
- 專案正式 Gradle Wrapper clean build 成功；JVM 測試 49/49 通過，包含 7 個 `TransferTest`。
- 規則測試涵蓋：舊格式相容、停用規則不進代理、第一條啟用規則、FINAL、目的埠、IPv4、IPv6、規則集及版本 codec。
- 詞庫測試涵蓋：欄位驗證、純文字／Rime 匯入、去重、停用狀態、JSON round-trip、原子持久化、版本上限、回退、匯出只含啟用詞條及 UI 狀態模型。
- Espresso UI 測試已在 Pixel 9 Pro（Android 17）執行，2/2 通過：空詞庫畫面，以及新增詞條後重建 Activity 仍保存。
- `lintDebug`、debug／release duplicate-class 檢查及 APK 組裝通過。release APK 為 `hk.chris.proxy`、versionCode 15、`1.4.0-mvp`，v2 簽章驗證通過。
- 同一簽章由 1.2.0 覆蓋安裝至 1.4.0-mvp 成功，保留既有節點與 App 資料；冷啟動成功，並在正式 App 實際打開個人詞庫空狀態頁。
- 正式 App 的 VPN 在 Android 17 顯示 `VALIDATED`；行動網路下經既有 HY2 節點取得東京出口，切回 Wi-Fi 後同一連線正確套用 `公司 Wi-Fi` 直連例外。測試結束已恢復 Wi-Fi 與未連線狀態。
- `app/src` 內沒有 Kotlin 原始碼。完整驗證命令：

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

## 尚未納入

- Fcitx5 Android／Ubuntu IME 本體、運行中資料庫寫入、選字學習與詞庫同步。
- Chris Secure capability API。
- LAN／5G／IPv6 路徑切換與剪貼簿事件協議。
- 尚未對 1.4.0-mvp 重跑 VLESS／SS／WireGuard 真實節點與規則編輯中的熱重載；Android 17 的 HY2 資料面、網路切換、安裝、冷啟動及詞庫 UI 已驗收。

這些應在現有服務介面完成鑑定後，以獨立 adapter 接入，不應擴大本 MVP 的代理權限。
