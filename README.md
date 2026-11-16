# Chris Proxy

Chris 系列的 Android 代理 App（`hk.chris.proxy`），針對 GrapheneOS / Android 17 保留正確的 SSID 權限邊界。

介面沿用 Chris ERP Android（四鐵共構）的操作邏輯：
- 左上三條線開**左側抽屜**：首頁、節點、分流規則（展開：規則／規則集／匯入清單）、個人詞庫、運行記錄；抽屜底部是設定與**連線燈**（目前節點延遲，<460 ms 綠、以上黃、失敗紅、未連線灰，每 15 秒量測）。
- **首頁**：問候（含自訂手機名稱與目前 Wi-Fi）＋一張主卡片（連線狀態、可點切換的出站模式、目前節點、延遲、規則數、**實際出口**，底部全寬的開始／中斷連線按鈕）。「需要處理」是 ERP 式的**底部上滑面板**（上次連線失敗、規則指向已刪除節點、規則集更新失敗／過期、訂閱更新失敗），0 為灰色、大於 0 琥珀色。分流設定不放首頁，在抽屜「分流規則」。
- **實際出口**：連線時透過核心的本機 SOCKS 入口（127.0.0.1、每次連線隨機帳密）發出檢測，走與一般流量完全相同的規則，再從核心記錄讀出實際出站，顯示「直連（Wi-Fi 例外）」或「經由 <節點>」，不是節點伺服器 IP。
- 清單都是**三行列**，點一下從底部拉出詳情與動作；刪除、導出明文前一律確認。
- 返回鍵：抽屜開著先關抽屜；子頁回上一層；首頁退到背景，不直接關閉 App。
- 設定可切換深淺色（跟隨系統／淺色／深色）；配色維持藍色系。

## 分流（Surge 式）

- **出站模式**：規則模式／全局代理／全部直連，首頁一鍵切換。
- **有序規則列表**：由上往下比對，第一條符合的生效；每條規則直接指定去向：直連、封鎖、目前使用的節點，或**某個指定節點**。
- **規則 MVP（1.3.0-mvp）**：每條規則可獨立啟用／停用而不刪除；可在手機本機輸入網域、IP、App 套件名及目的埠做 dry-run；每次變更保留規則版本，可回退且回退前會自動再保存。dry-run 輸入不保存、不寫入記錄。
- **比對類型**：完整網域、網域後綴、網域關鍵字、IP/CIDR、App 套件名（可從已安裝 App 選）、目的埠（`443`、`8000-8080`、`80,443`）。
- **FINAL**：其餘所有流量的去向。
- 區域網路（私有 IP）一律直連；廣告網域清單在規則之前封鎖。
- 修改規則、節點、DNS 時，若 VPN 正在執行會**熱重載**，不中斷連線；新設定驗證失敗則沿用舊設定並寫入記錄。
- 規則指定的節點被刪除時，該規則暫時改走目前節點，首頁與規則頁會提示。
- 舊版白名單／黑名單在第一次開啟時自動轉成規則（白名單 → FINAL 走代理，黑名單 → FINAL 直連）。
- **規則集（RULE-SET）**：一整份網域／IP 清單由一條規則決定走向，產生 sing-box 設定時合併成單一條陣列規則；來自網址的規則集每天開啟 App 時自動更新，也可手動更新。
- 可匯入 Loon / Surge 設定的 `[Rule]` 段落（`DOMAIN`、`DOMAIN-SUFFIX`、`DOMAIN-KEYWORD`、`IP-CIDR(6)`、`DST-PORT`、`FINAL`）；其他策略名稱一律對應「目前使用的節點」。不匯入腳本、重寫、MITM 或遠端規則集。

## 個人詞庫（1.4.0-mvp）

- 詞條、讀音／Rime 編碼、權重及啟用狀態全部離線保存在 App 私有目錄；最多 5,000 條。
- 可新增、編輯、搜尋、停用及刪除；每次有效變更保留本機版本，最多 10 版，回退前也會自動保存目前版本。
- 支援一行一詞、TSV 與 Rime 字典正文的貼上匯入。寫入前先顯示新增／更新／略過數量；重複詞條不會悄悄產生兩份。
- 只有使用者明確選擇匯出時，才把已啟用詞條複製成 Rime YAML 或中立 Fcitx TSV；詞條內容不寫入 App log。
- 這是供後續 Fcitx5 Android／Ubuntu Adapter 使用的中立資料層，**目前不直接改寫正在使用的 Fcitx/Rime 資料庫，也還沒有跨裝置同步**。

## 節點訂閱

節點頁 →「訂閱」：貼上訂閱網址（base64 或純文字的 vless／ss／hy2 清單；vmess、trojan 等目前不支援的類型會略過並告知數量）。更新時加入新節點、移除訂閱已刪除的節點，不動手動新增的節點，使用中的節點若仍存在會保持選取。每天開 App 自動更新，也可立即更新。網址以 Android Keystore 加密保存，並隨「App 設定檔」轉移。

## Wi-Fi 例外

設定 →「在這些 Wi-Fi 全部直連」。VPN 保持開啟，sing-box 以 `wifi_ssid` 規則（路由與 DNS 都套用）在這些網路全部直連；關掉 Wi-Fi 或換網路時自動恢復代理，不需手動。

## 外部快速導入網址清單

不用先開 App 複製貼上，以下方式都會先顯示**預覽**（條數、類型、略過幾行），選擇走向後才套用：

- 在瀏覽器、檔案管理員或聊天 App 選「分享」→「匯入到 Chris Proxy」（文字、單一網址或檔案皆可）。
- 用 Chris Proxy 開啟 `.txt`／`.list` 等文字檔。
- 連結 `chrisproxy://import?url=https://example.com/list.txt`（例如放在內部說明頁），點了會開預覽，不會自動套用。
- App 內：從網址、剪貼簿、檔案匯入。

支援格式：純網域（`example.com`、`.example.com`、`*.example.com`、整行網址）、Surge/Clash/Loon 規則行（`DOMAIN`、`DOMAIN-SUFFIX`、`DOMAIN-KEYWORD`、`IP-CIDR(6)`）、Clash YAML `payload`（含 `+.example.com`）、Adblock `||example.com^`、hosts 檔、IP/CIDR。正則、User-Agent、程序規則與 `@@` 例外不猜測，計為略過。單一清單上限 8 MB／20 萬條。

匯入時可選「存成規則集」（建議；網址來源會自動更新）或「拆成個別規則」（500 條以內，可逐條調整）。

## App 設定檔（轉移與備份）

設定頁 →「App 設定檔」，首頁不放導出／匯入入口。內容包含節點、目前節點、出站模式、規則與 FINAL、規則集（含內容）、Wi-Fi 例外、私人 DNS、廣告清單、深淺色。

**每次導出前都要驗證本人**：Android Keystore 的 P-256 簽章金鑰（優先 StrongBox，否則 TEE），每次使用都需生物辨識或螢幕鎖，簽章成功才導出；做法同 Chris Secure。

導出方式：
- **傳到另一支手機**（同一個 Wi-Fi，照 Chris Secure 兩段式配對）：舊手機顯示 QR（只有位址、一次性 token 與本次公鑰，不含設定）→ 新手機掃描並送出請求 → 兩邊顯示相同的六位核對碼 → 舊手機「核准並顯示 OTP」→ 新手機輸入 OTP（6 位、5 分鐘、單次、錯 5 次取消）→ 設定經 ECDH + AES-256-GCM 加密傳送。第一支持有效 token 的手機被綁定，其他手機即使知道 OTP 也無法領取。
- **加密設定檔 `.cpx`**：以一次性 10 碼轉移碼加密（PBKDF2-SHA256 31 萬次 + AES-256-GCM），可分享或存檔；轉移碼請另外告知。
- **JSON（明文）**：會先警告，仍需驗證本人。

匯入方式：掃描另一支手機的 QR、從文件（`.cpx` 或 `.json`）、貼上 JSON、從瀏覽器／網址下載（`chrisproxy://config?url=…` 亦可）。匯入前顯示摘要，可選「取代全部」或「合併」。

導出、匯入的畫面禁止截圖（FLAG_SECURE），離開即清除記憶體中的轉移碼、OTP 與檔案內容。

## 其他功能

- 指定 Wi-Fi（SSID）不使用代理。
- 可選的本機檔案或線上 URL 廣告網域清單。不解密 HTTPS、不安裝 CA、不做 MITM。
- App 內使用的 DoH、DoT、DoQ 或 UDP 私人 DNS；不修改系統 Private DNS。
- 節點手動貼上、外部 QR 掃描、使用者主動導出（導出前警告明文風險）；重複節點自動略過。
- 節點頁「測試全部延遲」：sing-box URL 測試，每個節點實際完成自身協定握手（HY2 QUIC、WireGuard、VLESS TLS/Reality、SS）並請求一個小網址後計時；未連線時在 App 內暫時起一個不建 TUN 的測試核心。失敗會顯示原因（逾時、憑證錯誤、伺服器拒絕等）。
- App 自身的流量（延遲量測、清單下載）不經過 VPN，直接走實體網路。
- 內建運行記錄，避免密碼、訂閱內容和完整流量進入記錄。

節點原文不以明文放在 `SharedPreferences` 或 log：使用 Android Keystore 的 AES-GCM 加密後才保存。導出是明確的使用者操作，透過 Storage Access Framework 由使用者選擇輸出位置；導出檔本身會是可攜的原始節點文字，App 不會偷偷建立另一份明文副本。

## 檢查更新（1.4.1）

- 設定 → 關於 →「檢查更新」向更新伺服器取得 JSON 資訊清單；「更新伺服器」可改成其他 HTTPS 網址。
- 資訊清單欄位：`package`、`version`、`versionCode`、`url`、`sha256`，可另外帶 `notes`。
- 只接受 HTTPS；下載網址必須與資訊清單同主機、同埠，且不跟隨重導。
- 下載後檢查容量上限、SHA-256、套件名稱、版本遞增，以及 APK 簽章憑證是否與目前安裝版本完全相同；任何一項不符都不會交給系統安裝器。
- 更新只覆蓋同簽章的 APK。更換簽章金鑰後，舊版無法透過此流程升級。
- 預設更新網址可在建置時用 `CHRIS_PROXY_UPDATE_URL` 或 `local.properties` 的 `updateUrl` 指定；未設定時 App 顯示「尚未設定」。
- 下載與檢查中的對話框只在目前 Activity 存活時顯示；離開畫面（旋轉、返回、結束）不會崩潰或誤跳視窗，下載完成後重新檢查即可。

```json
{
  "package": "hk.chris.proxy",
  "version": "1.4.1",
  "versionCode": 16,
  "url": "https://example.com/chris-proxy-1.4.1.apk",
  "sha256": "64 個十六進位字元",
  "notes": "更新說明"
}
```

## 重要狀態

`ProxyVpnService` 已接入 sing-box `libbox` 資料面：服務啟動後讀取目前節點，生成 sing-box JSON，透過 `VpnService.Builder` 建立 TUN，再由 libbox 處理 VLESS/SS/HY2/WireGuard 與路由。APK 目前只打包 ARM64，對應 GrapheneOS/Pixel 實機。

1.4.0-mvp 已在 Pixel 9 Pro／Android 17 覆蓋安裝、冷啟動並通過個人詞庫 UI 儀器測試；既有節點與 App 資料均保留。正式 VPN 亦已建立 `tun0` 並獲 Android `VALIDATED`：行動網路下經既有 HY2 節點取得東京出口，切回公司 Wi-Fi 後正確改走 Wi-Fi 直連例外；測試後已恢復 Wi-Fi 與未連線狀態。

1.4.1 只新增 App 內檢查更新，資料面未變更，因此 1.4.0-mvp 的資料面驗收仍適用；更新流程本身尚未做實機下載與安裝驗收。

資料面邊界：

1. Android bridge 只在核心 ready 後建立 TUN，並在底層 Wi-Fi/行動網路切換時更新出口介面。
2. 節點與路由設定由 App 生成，核心負責封包、DNS、TCP/UDP/IPv6 和協議握手。
3. proxy socket 由 libbox 的 Android platform callback 保護，避免回圈。
4. App log 不記錄節點原文或密鑰。

## 權限選擇

- `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`：SSID 是 location-sensitive Wi-Fi 欄位；不申請背景定位。
- Android 13+ `NEARBY_WIFI_DEVICES`：符合新版 Wi-Fi 權限模型。
- `ACCESS_WIFI_STATE`：讀取目前 Wi-Fi 狀態。
- `QUERY_ALL_PACKAGES`：App 分流需要把連線的 UID 對應到套件名，以及在規則編輯器列出 App；資料不離開裝置。GrapheneOS 側載不受 Play 政策限制，若上架 Play 需另行申報。
- `POST_NOTIFICATIONS`：Android 13+ 顯示 VPN 常駐通知。
- `REQUEST_INSTALL_PACKAGES`：只在使用者按下「立即安裝」後，把已驗證的同簽章 APK 交給系統安裝器；App 不自行安裝。
- `INTERNET`、`ACCESS_NETWORK_STATE`、`CHANGE_NETWORK_STATE`：代理連線、線上規則下載，以及追蹤 VPN 底層網路切換。
- VPN 的 `BIND_VPN_SERVICE`、前景服務權限：VPN 資料面生命週期。
- `targetSdk = 36`、`compileSdk = 36`：Android 17 上可運作；沒有把 Android 17 僅對 target 37+ 生效的 `ACCESS_LOCAL_NETWORK` 誤加進來。若未來直接用 LAN socket 並升 target 37，需另外設計該權限流程。

GrapheneOS 上仍可能因使用者關閉系統位置服務、撤銷附近 Wi-Fi 權限或工作設定檔政策而得到 `<未知 SSID>`；這不是把 SSID 猜成另一個網路的理由。

## 建置

```bash
export ANDROID_HOME="$HOME/Android/Sdk"          # Android SDK 36
export JAVA_HOME="$HOME/.jdks/jbr-17.0.14"       # JDK 17
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

正式版簽章與版本流程見 `RELEASE.md`。公開 repo 不含簽章金鑰，`assembleRelease` 在沒有金鑰時只會產生未簽章 APK。

## 目前沒有做的事

- 不內建或推薦任何訂閱來源；只有使用者自己貼上的訂閱網址。
- 節點可用 `vless://`、`ss://`、`hy2://`、`hysteria2://`，或完整 WireGuard `[Interface]` / `[Peer]` 文字；不限定 `http(s)://`。
- 不以「繞過中國大陸」或其他地區規避作為功能語義；不內建 geosite/geoip 地區規則集、機場訂閱或流量混淆。
- 不把 MITM 叫模組；MITM 若將來要加入，必須是獨立、明示、另行授權的高風險功能。
- 不宣稱個人詞庫已接入 Fcitx5 引擎、選字學習或 Android／Ubuntu 同步；目前只完成可回退的本機詞庫與明確匯出邊界。
- 不宣稱 1.4.0-mvp 已重跑所有協定與節點；本次實機資料面驗收涵蓋既有 HY2 節點及 Wi-Fi 例外，未重跑 VLESS／SS／WireGuard 真實節點。
- 不在 Google Play 上架；目前以自簽 APK 側載與覆蓋更新。

## 授權與來源

本專案使用 GPL-3.0-or-later，完整條文見 `LICENSE`；sing-box Android `libbox` 也依 GPL-3.0 發布。作者標記為 Chris。若你的副本另有上游著作權人，請依 GPL 條款保留授權、來源與對應修改。
