package com.example.myapplication.data;

import android.content.ContentResolver;

import com.example.myapplication.model.Asset;

import java.util.List;

// 單例：當前載入的財產清單 = 記憶體資料源，同時是寫檔的擁有者。
// 掃描／寫入當下只改記憶體並 markDirty()，實際寫檔延到頁面 onPause()/onStop() 時 flush()，
// 避免「每掃一次就整份重寫」的磁碟負擔與寫壞既有檔案的風險。
public class AssetRepository {
    private static AssetRepository instance;

    private List<Asset> assets;
    private android.net.Uri csvUri;

    private boolean dirty   = false;  // 記憶體有未落檔的變更
    private boolean writing = false;  // 寫檔進行中，避免重入

    public static AssetRepository getInstance() {
        if (instance == null) instance = new AssetRepository();
        return instance;
    }

    public List<Asset> getAssets() { return assets; }
    public void setAssets(List<Asset> assets) { this.assets = assets; }

    public android.net.Uri getCsvUri() { return csvUri; }
    public void setCsvUri(android.net.Uri uri) { this.csvUri = uri; }

    /** 標記記憶體已變更、待落檔。 */
    public void markDirty() { dirty = true; }

    public boolean isDirty() { return dirty; }

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
