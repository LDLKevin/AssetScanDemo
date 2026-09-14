package com.example.myapplication.data;

import android.content.ContentResolver;

import com.example.myapplication.model.Asset;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// 單例：當前載入的財產清單 = 記憶體資料源，同時是寫檔的擁有者。
// 掃描／寫入當下只改記憶體並 markDirty()，實際寫檔延到頁面 onPause()/onStop() 時 flush()，
// 避免「每掃一次就整份重寫」的磁碟負擔與寫壞既有檔案的風險。
public class AssetRepository {
    // 全盤／抽盤各自一份資料集（各自的清單、CSV、進度），彼此獨立。
    private static AssetRepository fullInstance;
    private static AssetRepository samplingInstance;

    // 資料夾（含持久權限）兩模式共用同一個，故設為全域。
    private static android.net.Uri treeUri;

    private List<Asset> assets;
    private android.net.Uri csvUri;   // CSV 文件本身（資料夾內）
    private String csvName;           // CSV 顯示檔名（header 顯示用）

    private boolean dirty   = false;  // 記憶體有未落檔的變更
    private boolean writing = false;  // 寫檔進行中，避免重入

    /** 全盤資料集（單例）。 */
    public static AssetRepository full() {
        if (fullInstance == null) fullInstance = new AssetRepository();
        return fullInstance;
    }

    /** 抽盤資料集（單例）。 */
    public static AssetRepository sampling() {
        if (samplingInstance == null) samplingInstance = new AssetRepository();
        return samplingInstance;
    }

    /** 供測試建立獨立實例；App 走 {@link #full()} / {@link #sampling()}。 */
    public AssetRepository() {}

    public List<Asset> getAssets() { return assets; }
    public void setAssets(List<Asset> assets) { this.assets = assets; }

    public android.net.Uri getCsvUri() { return csvUri; }
    public void setCsvUri(android.net.Uri uri) { this.csvUri = uri; }

    // 資料夾為全域共用（兩模式從同一資料夾讀 ALL*/RAN* 的 CSV）
    public static android.net.Uri getTreeUri() { return treeUri; }
    public static void setTreeUri(android.net.Uri uri) { treeUri = uri; }

    public String getCsvName() { return csvName; }
    public void setCsvName(String name) { this.csvName = name; }

    public boolean isDirty() { return dirty; }

    // ── 寫入動詞：mutation 與 dirty 標記合成單一操作 ──────────────
    // 呼叫端不再「改欄位 + 另外 markDirty()」，避免漏標導致 flush 跳過而漏存檔。

    /** 命中：標記盤點狀態、蓋上盤點時間、標記待落檔。 */
    public void recordCheck(Asset asset, Asset.Status status) {
        asset.status    = status;
        asset.checkedAt = now();
        dirty = true;
    }

    /** 盤盈寫入：新增一筆盤點過的財產（指定狀態）、蓋時間、加入清單、標記待落檔。 */
    public void addChecked(Asset asset, Asset.Status status) {
        asset.status    = status;
        asset.checkedAt = now();
        if (assets != null) assets.add(asset);
        dirty = true;
    }

    /** 編輯既有財產的部門／地點，標記待落檔（不動 checkedAt）。 */
    public void recordEdit(Asset asset, String department, String location) {
        asset.department = department;
        asset.location   = location;
        dirty = true;
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date());
    }

    /**
     * 若有未落檔變更則寫一次檔（含 Big5 編碼、序列化先行）。
     * 會阻塞做 I/O，呼叫端請放在背景執行緒。回傳是否真的寫了檔。
     */
    public synchronized boolean flush(ContentResolver resolver) throws Exception {
        if (!dirty || writing || assets == null || csvUri == null) return false;
        writing = true;
        try {
            CsvManager.write(resolver, csvUri, assets);
            dirty = false;
            return true;
        } finally {
            writing = false;
        }
    }
}
