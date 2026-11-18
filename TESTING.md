# Testing

## 自動檢查

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export JAVA_HOME="$HOME/.jdks/jbr-17.0.14"
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

單元測試（`app/src/test`）涵蓋：延遲失敗原因對照、VLESS `allowInsecure`、訂閱解析（base64／URL-safe／純文字、URL 編碼名稱、不支援類型計數）、實際出口判定（從核心記錄找出檢測連線的出站）、Wi-Fi 例外規則、配置轉移協定端到端（本機迴路上模擬兩支手機：核對碼一致、核准後 OTP、錯 OTP 計次與 5 次取消、拒絕、錯誤 token 被丟棄後真手機仍可配對、QR 不含設定）、加密設定檔（轉移碼錯誤與竄改皆拒絕）、清單解析（純網域、Surge/Clash/Loon、Clash payload、Adblock、hosts、去重）、規則集展開、規則值正規化與驗證、序列化、規則啟停、dry-run、規則版本回退、每個節點各自成為出站、規則指定特定節點（含 WireGuard endpoint 與 App 規則）、已刪除節點回退、全局／直連模式、FINAL 封鎖、SS SIP002 解碼、錯誤訊息不含節點密鑰、Loon 匯入、舊白名單遷移、更新網址與下載來源驗證、更新 UI 在 Activity 結束／旋轉後的存活檢查與 BUSY 解鎖時機，以及個人詞庫驗證、匯入、去重、持久化、版本回退、匯出與 UI 狀態。2026-11-16 本機重跑為 63/63 通過。

測試只驗證產生的 JSON 結構；**sing-box 核心是否接受設定**在手機上由 `Libbox.checkConfig` 驗證，結果見運行記錄。

## UI 自動測試

`app/src/androidTest` 使用 Espresso 覆蓋：

1. 從抽屜進入個人詞庫，確認空狀態與新增入口可見。
2. 新增詞條後重建 Activity，再次進入詞庫，確認內容、啟用數及版本仍正確。

```bash
./gradlew :app:connectedDebugAndroidTest
```

需要已連線 Android 裝置或 Emulator。只完成 `assembleDebugAndroidTest` 代表 UI 測試原始碼、資源及 test APK 可編譯，不代表已在裝置上執行。

## 更新流程（1.4.1）

`UpdatePolicyTest` 覆蓋：只接受 HTTPS、拒絕帳號密碼與 fragment、拒絕路徑穿越、下載來源必須與資訊清單同主機同埠。

尚未完成的實機驗收（不把建置成功當成已驗收）：

1. 以 `CHRIS_PROXY_UPDATE_URL` 建置，確認設定頁顯示預設網址。
2. `versionCode` 相同或較低時顯示「已是最新版本」，較高才進入下載。
3. 故意提供錯誤 SHA-256、錯誤簽章或跨主機下載網址，確認全部拒絕。
4. 完成一次實際下載、交給系統安裝器、覆蓋安裝後版本與 App 資料保留。
5. 在下載途中旋轉螢幕、按返回離開設定頁、或把 App 切到背景後結束，確認不崩潰；回到 App 重新檢查時可以再啟動一次。

## 虛擬機測試（2026-11-18）

Android 14／API 34、Google APIs x86_64 映像（支援 ARM64 轉譯）：

- 脫敏後的原版 ARM64 release APK（1.4.1／versionCode 16；SHA-256 `eb401f5bd0ef43361538062bac96eed102621ebeffd3dfe886095258aa2691e0`）可安裝、冷啟動、進入設定；未設定預設更新伺服器時顯示提示，HTTP 網址不會保存，HTTPS 測試網址的檢查失敗會顯示錯誤而不崩潰。
- 可新增僅供測試的本機 SS 節點，但首次同意 VPN 授權後，ARM64 函式庫在 x86_64 轉譯環境發生原生 `SIGILL`，App 退出。這不代表 ARM64 實機會重現，也不能算原版 APK 的 VPN 驗收通過。
- 另在暫存目錄以**相同程式碼、版本和簽章**建置 x86_64 專用測試 APK；覆蓋安裝後測試節點保留，VPN 啟動並建立 `tun0`（Android 回報 `IS_VALIDATED`），中斷後 `tun0` 移除，無崩潰。這份 x86_64 測試 APK **不是**公開發布檔。
- `connectedDebugAndroidTest` 在此虛擬機執行 2/2 通過；JVM 單元測試 63/63、`lintRelease` 及 release 組裝通過。
- 未使用真實節點，未驗證代理出口、更新伺服器的有效 manifest、APK 下載／安裝或 Android 17 的 ARM64 實機行為。公開正式 APK 前仍須在 ARM64 實機走完更新與 VPN 驗收。

## 實機驗收紀錄

1.4.1 正式候選檔（SHA-256 見上方虛擬機紀錄）：Pixel 9 Pro／Android 17 從 1.4.0-mvp 以 ADB 覆蓋安裝，裝置端 `base.apk` 雜湊與候選檔一致。使用既有設定連線後，Android 回報 VPN `IS_VALIDATED`；中斷時 `tun0` 消失，再次連線後恢復。未逐一重跑各代理協定。

另於隔離的 API 34 模擬器，以同一更新器程式碼、相同簽章、較低 versionCode 的測試客戶端讀取本機 HTTPS manifest。只有該測試客戶端信任測試憑證。點選「立即更新」及「立即安裝」後，Android 系統安裝器完成更新；複核 versionCode 為 16 且 `base.apk` SHA-256 與公開候選檔相同。此流程驗證客戶端下載與安裝，不代表公開版內建了更新站。

2026-10-25，Pixel 9 Pro（caiman）、Android 17（build CP3A.261005.005／2026102100），1.4.0-mvp：

- 專案正式 Gradle Wrapper clean build 通過；JVM 測試 49/49、Espresso 詞庫 UI 測試 2/2、`lintDebug`、debug／release duplicate-class 檢查及 APK 組裝全部通過。
- release APK 以原簽章從 1.2.0 覆蓋安裝成功；versionCode 15／versionName 1.4.0-mvp，保留原始安裝時間、既有節點及 App 資料。
- 正式 App 冷啟動 549 ms，無 fatal exception；由抽屜進入個人詞庫可見空狀態、新增／匯入／匯出／版本回退與搜尋。
- 正式 VPN 建立 `tun0` 後 Android 回報 `VALIDATED`；行動網路下首頁顯示經 `<HY2 節點>`，出口 `<節點出口 IP>`（東京）。切回 `<公司 Wi-Fi>` 時同一 VPN 由 CELLULAR 切為 WIFI，並正確改走既有 Wi-Fi 直連例外；最後中斷 VPN、恢復原本 Wi-Fi 與未連線狀態。

2026-10-02，Pixel 9 Pro（caiman）、GrapheneOS Android 17（build 2026092500），debug APK，無真實代理節點，以指向 127.0.0.1 的假 SS 節點測試：

- 通過：16 KB 頁面對齊（AGP 8.13）、VPN 授權、sing-box 啟動與 TUN 建立、Android 判定 VPN 為 VALIDATED。
- 通過：全部直連模式下瀏覽器開百度、Google 正常；App 辨識（記錄中 `found package name`）正常。
- 通過：規則模式新增「baidu.com → 目前節點」後即時套用（未中斷連線），百度流量送往指定節點、Google 仍走 FINAL 直連。
- 通過：深色模式、工具列不被狀態列遮住、Material 對話框按鈕可見。
- 實機修正：補 `Libbox.setup()`；IPv6 link-local 位址去掉 `%介面` 以免 Go panic；介面旗標改用 Linux IFF_*；實作 Android DnsResolver 的 `LocalDNSTransport`；服務改為不自動重啟以免崩潰迴圈。
- 2026-10-03 真實節點：HY2（salamander 混淆）經規則模式 FINAL 送出，Google 搜尋正常；代理網域經節點以 DoH 解析，避免本機 DNS 回傳錯誤位址；「中斷連線」立即移除 VPN。
- 實機修正（第二輪）：HY2 支援 obfs／obfs-password、alpn、pinSHA256、多埠與 mport、up/downmbps；DNS 隨路由分流；服務以 ACTION_STOP 先關核心與 TUN 再停止。
- 2026-10-03 兩支手機（Pixel 9 Pro／OnePlus PJZ110 Android 16）掃碼轉移成功（核對碼＋OTP）。
- 2026-10-03 Wi-Fi 例外：Pixel 在例外 Wi-Fi 上連線，首頁實際出口顯示「直連（Wi-Fi 例外）」，依核心記錄判定；ERP 式上滑面板、摺疊首頁確認。
- 2026-10-03 Wi-Fi 例外背景失效（直連被重置）：App 在背景時 Android 不給 SSID，核心讀到「未知」，例外失效改走 FINAL。前景服務加上位置類型後，背景關／開 Wi-Fi 時核心正確讀到「命中 Wi-Fi 例外」（1.1.1）。
- 2026-10-10 真實延遲（1.2.0）：改用 sing-box URL 測試（每個節點實際握手並請求 generate_204）。未連線時以暫時測試核心（無 TUN）進行。Pixel 實測 HY2 372–579 ms；訂閱 VLESS 節點在家用 Wi-Fi 回報「伺服器拒絕」（`unknown version: 72`），改走 5G 後同一批節點 748–4133 ms 正常。
  原因：該 Wi-Fi 的路由器透明代理會依偽裝 SNI（baidu.com、update.microsoft.com）把連線改送到真的網站，回應變成一般網頁。不是 App 的設定問題；對照實驗（uTLS、ALPN、拿掉 Vision）確認設定正確，且這批節點必須使用 Vision。
  判讀提醒：Wi-Fi 上「伺服器拒絕」不一定是帳號失效，請先在行動網路重測。
- 未驗：SS、WireGuard 真實節點；Reality 節點在其他網路；外部分享匯入；規則集與訂閱自動更新。

核心記錄在 App 私有目錄 `files/logs/core.log`（超過 1 MB 於下次啟動時清除）：

```bash
adb shell run-as hk.chris.proxy.debug cat files/logs/core.log
```

## 實機驗收（發布前必做）

APK 只包含 `arm64-v8a/libbox.so`。

1. 首次 VPN 同意後通知圖示與 VPN key 出現；首頁狀態顯示「● 已連線」。
2. 運行記錄出現 `sing-box 資料面已啟動`。
3. VLESS/SS/HY2/WireGuard 各一個真實節點的 HTTPS 流量。
4. 分流：
   - 網域後綴規則指定節點 B，其餘走節點 A：分別造訪，確認出口 IP 不同。
   - App 規則（例如瀏覽器 → 直連）：該 App 出口為本機 IP，其他 App 走代理。
   - IP/CIDR 與目的埠規則、封鎖規則、FINAL 三種去向。
   - 連線中修改規則：記錄出現「分流設定已套用（未中斷連線）」且連線不中斷。
   - 刪除被規則引用的節點：首頁出現警示，流量改走目前節點。
5. 全局代理／全部直連模式切換。
6. Wi-Fi 例外：在例外 Wi-Fi 按開始連線會詢問；權限撤銷後顯示警示卡。
7. 舊版資料遷移：從 0.2.x 白名單／黑名單升級（同簽章的 debug 版）後規則正確出現。
8. 配置轉移：兩支手機實測「傳到另一支手機」全流程；加密設定檔分享到新手機並以轉移碼匯入；取代與合併結果；導出前生物辨識取消不導出。
9. 外部匯入：從瀏覽器分享網址、從檔案管理員分享／開啟 .txt、點 `chrisproxy://import?url=…`；確認都先出預覽、取消不寫入；存成規則集後隔天自動更新。
10. 個人詞庫：新增／編輯／搜尋／停用／刪除、TSV／Rime 匯入預覽、Rime／Fcitx 匯出、版本回退及 Activity 重建後保存。
11. 抽屜、連線燈顏色、首頁「需要處理」計數、BottomSheet 詳情、返回鍵順序。
12. 設定切換深淺色；GrapheneOS 服務停止、權限撤銷。

延遲按鈕使用 sing-box URL 測試，包含協定握手及測試網址請求；不等同於完整 VPN 流量驗收。
