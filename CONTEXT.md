# CONTEXT — AssetScanDemo 領域詞彙

財產盤點 App：ERP 匯出 CSV（Big5）→ USB 傳到手機 → 現場離線掃 QR Code 逐筆比對、寫回註記 → 傳回 ERP。

## 流程（Flow）

- **全盤（Full scan）** — `FullActivity` + `FullScanActivity`。相機連續開著，掃到什麼判定什麼，掃過的財產進 history 可回看。
- **抽盤（Sampling）** — `SamplingActivity` + `SamplingScanActivity`。逐筆鎖定一個目標資產，掃描頁只接受該目標。

## 掃描判定（Scan outcome）

一次掃描的 raw 字串（格式 `id;name;department;location`）判成四種之一，由純邏輯 module `ScanClassifier` 產生：

- **誤觸（IGNORED_INVALID）** — id 不成格式。靜默忽略：不刷新畫面、不提示、不記錄。
- **命中相符（MATCHED）** — 在清單、部門與地點都相符。標記 `V` + 時間。
- **命中不符（UNMATCHED）** — 在清單、但部門或地點不符。標記 `X` + 時間。
- **盤盈（SURPLUS）** — 格式正確但不在清單（未列入清單）。提示並可寫入。

## 模組（Modules）

- **`QrScanner`**（`camera/`）— 相機 + QR 解碼管線的 deep module。藏 CameraX 綁定、ZXing 解碼、YUV 轉換、手電筒、執行緒收尾。純傳輸層：每次成功解碼在主執行緒回呼 `onDecoded(raw)`，不做冷卻／去重（那是各畫面的政策）。前置條件：`bind()` 前相機權限須已授權。全盤與抽盤兩掃描頁共用。
- **`ScanClassifier`**（`logic/`）— 純判定：`raw + 清單 + id 格式 predicate → Outcome`。不碰相機／UI，可單元測試。
- **`AssetIdFormat`**（`logic/`）— 可抽換的 id 格式 predicate，用來擋誤觸。正式規則待使用單位確認，屆時只換此實作。
- **`AssetRepository`**（`data/`）— 記憶體資料源 + 寫檔擁有者。掃描當下只改記憶體並 `markDirty()`，落檔延到 `onPause()/onStop()` 的 `flush()`。是日後換 SQLite/Room 的自然接點。
- **`CsvManager`**（`data/`）— Big5 讀寫，序列化先行後單次落檔，不加 BOM。

## 狀態欄位對應（CSV）

`MATCHED → "V"` ／ `UNMATCHED → "X"` ／ `UNCHECKED → 空`。欄位順序 `id, name, department, location, status, checkedAt` 維持不變，供既有 ERP 匯入。

## 政策放哪（Policy locality）

冷卻（cooldown）與去重（de-dup）是**各掃描頁的政策**，留在 Activity（`FullScanActivity` 有 900ms 冷卻＋同一 raw 去重；`SamplingScanActivity` 只對非目標 Toast 節流），不進 `QrScanner`。
