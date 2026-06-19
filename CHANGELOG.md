# 修改紀錄（Changelog）

> 更新日期：2026-06-19
> 本次工作為一系列功能調整、bug 修正、UI 美化與新功能。以下依主題分類。

---

## 一、家長端調整

- 移除遠端控制的 +10 / +30 分鐘快捷鈕（保留手動輸入與增/扣/設定）。（`parent/RemoteControlScreen.kt`）
- 看板移除「上次套用每日規則日期」：裝置管理與遠端控制皆移除。（`parent/DeviceManagementScreen.kt`、`parent/RemoteControlScreen.kt`）
- 任務類型改下拉選單（永久／限時）。（`parent/TaskManagementScreen.kt`）
- 限時任務可選時間單位（分鐘／小時／天）。（`parent/TaskManagementScreen.kt`）
- 兌換商店「發送對象」改下拉選單。（`parent/StoreManagementScreen.kt`）
- 家長可刪除已上架商品，後端連同圖片一併刪除。（`parent/StoreManagementScreen.kt`、`sync/SupabaseStoreClient.kt`）
- 每日錢包規則改下拉選單（本機時間設定＋遠端控制）。（`parent/LocalTimeSettingsScreen.kt`、`parent/RemoteControlScreen.kt`）
- 遠端控制自動更新（背景輪詢，每 4 秒），移除重新整理鈕。（`parent/RemoteControlScreen.kt`）
- 移除手動「同步家長 PIN」按鈕（改 PIN 已自動同步）。（`parent/FamilyManagementScreen.kt`）
- 移除家長模式的「設定兒童保護」按鈕。（`parent/ParentHomeScreen.kt`）
- 家長端自動請求通知權限（Android 13+），讓 FCM 推播能正常顯示。（`parent/ParentHomeScreen.kt`）

## 二、兒童端調整

- 修正不會計時 bug：每分鐘扣秒移入同一同步協程，順序 `pull → 處理指令 → 扣秒 → push`，避免被遠端覆蓋。（`lock/ChildTimeForegroundService.kt`）
- 兌換商店：購買前二次確認、餘額不足禁用、畫面顯示剩餘時間；重新整理/進入/購買後會先拉伺服器狀態再更新餘額。（`child/ChildStoreScreen.kt`）
- 返回不再重複要求家長 PIN（權限提示旗標改 `rememberSaveable`）。（`child/ChildHomeScreen.kt`）
- 無障礙/權限失效常駐警告條，一鍵直達設定（不經 PIN）。（`child/ChildHomeScreen.kt`、`MainActivity.kt`）
- 移除「已使用」時間顯示（持續累加無意義），保留剩餘時間與鎖定狀態。（`child/ChildHomeScreen.kt`）

## 三、任務流程

- 修好手動核准：先發放獎勵再送通知（通知改 best-effort），缺裝置時直接擋下，不留半套狀態。（`sync/SupabaseTaskClient.kt`）
- 孩子端看不到任務的修正：匿名裝置被 RLS 擋住，改走 SECURITY DEFINER RPC。（`sync/SupabaseTaskClient.kt`、需執行 SQL）
- 孩子提交任務 → 家長端可見：任務頁自動刷新並顯示「⚠ 有 N 件待審核」。（`parent/TaskManagementScreen.kt`）
- 「限時到」時間格式化為 `yyyy/MM/dd HH:mm:ss`（修正解析器支援 `+00:00` 位移）。（`ui/TimeFormats.kt`）

## 四、家長 PIN 同步

- 綁定家庭後一律以家庭同步 PIN 為準、本地自動更新；離線沿用上次 PIN。（`pin/ParentPinGateScreen.kt`）
- 改 PIN 後自動上傳家庭，全家裝置下次開關卡即同步。（`pin/ParentPinSettingsScreen.kt`）
- 孩子端改走 RPC 取得家庭 PIN（匿名無法直讀 `families`）。（`sync/SupabaseFamilyClient.kt`、需執行 SQL）

## 五、紀錄過濾

- 兒童時效歷程：只顯示任務獎勵、商店購買、家長遠端加/減。（`child/ChildTimeHistoryScreen.kt`）
- 家長操作紀錄：只顯示任務、商店購買、遠端加/減、變更 PIN。（`parent/AuditLogScreen.kt`）

## 六、QR 配對（新功能）

- 家長端綁定後顯示家庭碼 QR；兒童端可掃描 QR 連接（保留手動輸入備援）。
- 使用 ZXing 產生 QR、Google `play-services-code-scanner` 掃描（免 CAMERA 權限）。後端零改動。
- （`build.gradle.kts`、`ui/QrCodeGenerator.kt`、`parent/FamilyManagementScreen.kt`、`child/ChildHomeScreen.kt`）

## 七、兌換紀錄與發放（新功能）

- 孩子購買成功後寫入兌換紀錄；家長新增「兌換紀錄」頁：清單、待發放數提示、自動刷新、標記已發放/改回待發放、刪除紀錄。
- （`sync/SupabaseStoreClient.kt`、`sync/SupabaseRedemptionClient.kt`、`parent/RedemptionsScreen.kt`、`child/ChildStoreScreen.kt`、`MainActivity.kt`、`parent/ParentHomeScreen.kt`、需執行 SQL）

## 八、語音朗讀（新功能）

- 兒童任務頁與兌換商店每張卡片加「🔊 朗讀」鈕，唸出標題/名稱與說明；用 Android 內建 `TextToSpeech`，依內容自動切換中/英。
- 引擎未就緒時按鈕仍可按，會提示並直接帶往「文字轉語音」設定頁。（`child/ChildTasksScreen.kt`、`child/ChildStoreScreen.kt`）

## 九、UI 美化（度假沙灘・童趣風）

- 配色換成海灘調（海洋藍＋陽光黃＋珊瑚橘＋沙色）。（`ui/Theme.kt`）
- 全域淡淡海灘背景（太陽、天空泡泡、沙灘色帶）＋微笑太陽吉祥物 `SunMascot`＋餘額徽章 `BalancePill`。（`ui/Decorations.kt`、`ui/Components.kt`）
- 平板寬度自適應：內容置中、最大寬度 560dp，按鈕不再被拉成超寬。（`ui/Components.kt`）

## 十、即時推播 FCM（新功能）

- 孩子提交任務 → Supabase Database Webhook 觸發 Edge Function `notify-submission` → 查家長 FCM token → 送 FCM v1 推播 → 家長手機即使關 App/鎖屏也跳通知。
- 家長登入家長模式時自動上傳 FCM token；自動請求通知權限（Android 13+）。
- 屏蔽舊的 `TASK_SUBMITTED` 本地通知（改由 FCM 通知家長），避免重複。（`sync/DeviceCommandProcessor.kt`）
- App 端：`push/FamilyMessagingService.kt`、`push/PushTokenUploader.kt`、`build.gradle.kts`（firebase-messaging）、`AndroidManifest.xml`、`parent/ParentHomeScreen.kt`。
- 後端：`docs/push_tokens.sql`、`supabase/functions/notify-submission/index.ts`、設定指南 `docs/FCM_SETUP.md`。

---

# 2026-06-19 更新

> 本次聚焦家長 PIN 同步的假成功修正、PIN 權限收斂為「僅擁有者」，以及輸入體驗。

## 十一、家長 PIN 同步修正（假成功 bug）

- 修正「改 PIN 顯示成功，但下次仍要舊 PIN」：根因是 `syncParentPinHash` 用 `Prefer: return=minimal`，PostgREST 在 RLS 過濾掉 0 列時仍回 204，被當成成功；PIN 閘門隨後又以遠端（仍是舊 hash）覆蓋本機新 hash。
- `syncParentPinHash` 改用 `Prefer: return=representation`，並比對回傳的 `parent_pin_hash` 確認確實寫入，否則回 `Error`（觸發本機 rollback、不顯示假成功）。（`sync/SupabaseFamilyClient.kt`）
- PIN 寫入前要求已登入家長 token，不再 fallback 成 anon key 被 RLS 靜默擋下；新增 `SupabaseRestAuthHeaders.hasAccessToken()`。（`sync/SupabaseRestAuthHeaders.kt`、`sync/SupabaseFamilyClient.kt`）

## 十二、PIN 變更權限收斂為「僅家庭擁有者」

- 只有建立家庭的擁有者（`family_members.role = 'owner'`）可變更家長 PIN；以家庭碼綁入的家長（`parent`）不可變更，但仍可使用任務／寶箱／商店等功能。
- 後端：新增 `is_family_owner(uuid)`；`families` 更新 policy 由 `parents update families`（用 `is_family_parent`）改為 `owner updates families`（用 `is_family_owner`）；`is_family_parent` 維持含 `owner`，供其他資料表沿用。（`docs/SUPABASE_ALL_IN_ONE.sql`、需執行 SQL）
- App 端：新增 `SupabaseFamilyClient.fetchCurrentMemberRole()` 讀取自身角色；非擁有者於變更 PIN 畫面顯示「只有家庭擁有者可以變更家長 PIN」並隱藏表單，家長首頁亦隱藏「變更家長 PIN」入口。（`sync/SupabaseFamilyClient.kt`、`pin/ParentPinSettingsScreen.kt`、`parent/ParentHomeScreen.kt`、新增字串 `parent_pin_owner_only`）

## 十三、PIN 輸入體驗

- PIN 輸入框改用數字鍵盤（`KeyboardType.NumberPassword`），維持圓點遮蔽；涵蓋輸入、建立、變更各欄位。（`pin/ParentPinGateScreen.kt`、`pin/ParentPinSettingsScreen.kt`）

## 十四、已結束任務保留期限

- 過期關閉或被退回的任務最多保留 3 天；期滿後由 App 維護流程先刪除 Supabase Storage 內的提交照片，再刪除任務與提交資料。
- 清理期限由 7 天縮短為 3 天，並同步套用於候選清單與最終刪除 RPC。（`docs/SUPABASE_ALL_IN_ONE.sql`、需重新執行 SQL）

---

## 需在 Supabase 執行的內容

1. SQL：執行 `docs/SUPABASE_ALL_IN_ONE.sql`（孩子看任務、孩子跟隨家庭 PIN、兌換紀錄等 RPC/表）。
   - 2026-06-19 更新需重跑此檔（或單獨套用）：新增 `is_family_owner`、`families` 更新 policy 收斂為僅擁有者、`is_family_parent` 維持含 `owner`。
2. FCM：執行 `docs/push_tokens.sql`，部署 `supabase/functions/notify-submission`（設 Firebase 密鑰），並建立 `task_submissions` Insert 的 Database Webhook。詳見 `docs/FCM_SETUP.md`。

> 註：SQL 型別預設 `uuid`，若 `devices.id` / `families.id` 為 `text` 需對應調整。

## 建置與驗證注意事項

- 變更涉及版面與字串，請用完整 **Run**（非 Apply Changes）。
- 無障礙權限在每次更新 App 後會被系統關閉（Android 安全機制），更新後需重新開啟（主畫面警告條可一鍵前往）。
- 實機驗證重點：
  - 任務指派/孩子可見、核准獎勵入帳、兩端狀態同步。
  - 改 PIN 後孩子端用新 PIN、舊 PIN 失效（離線沿用上次）。
  - 計時每分鐘遞減、歸零鎖定。
  - 購買後家長「兌換紀錄」出現待發放、可標記已發放。
  - 語音朗讀（裝置需安裝對應語言 TTS 語音包）。
  - FCM：家長登入一次（`push_tokens` 有 parent token）→ 關 App → 孩子提交 → 家長手機跳通知（需先開通知權限）。
