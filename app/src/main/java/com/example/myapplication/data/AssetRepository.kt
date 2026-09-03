package com.example.myapplication.data

import android.content.ContentResolver
import android.net.Uri
import com.example.myapplication.model.Asset

/**
 * 單例：當前載入的財產清單 = 記憶體資料源，同時是寫檔的擁有者。
 * 掃描／寫入當下只改記憶體並 markDirty()，實際寫檔延到頁面 onPause()/onStop() 時 flush()，
 * 避免「每掃一次就整份重寫」的磁碟負擔與寫壞既有檔案的風險。
 *
 * 全專案轉 Kotlin 後，從「class + companion getInstance()」收割成道地的 `object`：
 * Kotlin 的 `object` 就是執行緒安全、延遲初始化的單例，呼叫端直接寫 `AssetRepository.xxx`。
 */
object AssetRepository {

    // MutableList：ScanActivity 會對這份清單 add 盤盈的新財產，需能變動。
    var assets: MutableList<Asset>? = null
    var csvUri: Uri? = null

    private var dirty = false    // 記憶體有未落檔的變更
    private var writing = false  // 寫檔進行中，避免重入

    /** 標記記憶體已變更、待落檔。 */
    fun markDirty() {
        dirty = true
    }

    val isDirty: Boolean
        get() = dirty

    /**
     * 若有未落檔變更則寫一次檔（含 Big5 編碼、序列化先行）。
     * 會阻塞做 I/O，呼叫端請放在背景執行緒。回傳是否真的寫了檔。
     */
    @Synchronized
    fun flush(resolver: ContentResolver): Boolean {
        // 先取到區域變數，Kotlin 才能對可變屬性做 smart-cast（保證非 null 後直接使用）。
        val currentAssets = assets
        val uri = csvUri
        if (!dirty || writing || currentAssets == null || uri == null) return false

        writing = true
        return try {
            CsvManager.write(resolver, uri, currentAssets)
            dirty = false
            true
        } finally {
            writing = false
        }
    }
}
