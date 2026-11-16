# Architecture

## Screens（Chris ERP Android 式）

- 左側抽屜：首頁、節點、分流規則（▸ 規則／規則集／匯入清單）、運行記錄；底部設定＋連線燈。
- 首頁儀表板：「還活著嗎」狀態塊 → 開始／中斷連線 → 「需要處理」計數列 → 出站模式、分流摘要。
- 節點、規則、規則集、Wi-Fi 例外：三行列，點一下開 BottomSheet 詳情與動作。
- 匯入預覽：條數與類型、名稱、走向，存成規則集或拆成規則。
- 設定：深淺色、Wi-Fi 例外、私人 DNS、廣告過濾、導出節點、版本。

## External import

`MainActivity` 為 `singleTask`，`ACTION_SEND text/*`、`ACTION_VIEW content:// text/*` 與 `chrisproxy://import?url=` 都進入 `handleExternalIntent()`，只建立預覽，不會未經確認寫入規則。`RuleListParser` 正規化多種清單格式；`RuleSetStore` 把規則集內容存在 `files/rulesets/<id>.list`（classical 格式），中繼資料存 SharedPreferences。

## Runtime path

```text
MainActivity
  -> VpnService.prepare()
  -> ProxyVpnService (foreground, state: STOPPED/STARTING/RUNNING/FAILED)
  -> CommandServer + LibboxPlatform
  -> sing-box libbox
  -> Android VpnService.Builder TUN
  -> outbounds: node-<id> per saved node, "proxy" selector (default = node in use), "direct"
```

`LibboxPlatform` 是 Android 系統邊界：建立 TUN、加入路由/DNS、保護核心 socket、回傳 Wi-Fi 狀態，並以 `ConnectivityManager.getConnectionOwnerUid` 提供連線所屬 App（App 規則需要）。

## Routing semantics

sing-box route rules 依序為：

1. `sniff`（讓網域規則也能比對直接以 IP 連線的流量）
2. DNS hijack
3. 私有 IP → direct
4. 廣告網域清單 → reject（單一規則、網域陣列）
5. 使用者規則，依列表順序；規則集展開為一條含 domain/domain_suffix/domain_keyword/ip_cidr 陣列的規則（同一條內為 OR）
6. `final` = FINAL 去向（封鎖時以最後一條 reject 實作）

全局代理模式保留 1–4、略過使用者規則，final 為 proxy；全部直連模式只保留 1–3，final 為 direct。節點 ID 為原始設定的 SHA-256 前 12 hex，不洩漏內容且不受排序影響。

設定變更時 `MainActivity` 送出 `ACTION_RELOAD`，service 重新產生設定、`checkConfig` 後 `startOrReloadService`；失敗則保留舊設定。
