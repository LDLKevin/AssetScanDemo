package com.example.myapplication.data

import android.content.ContentResolver
import android.net.Uri
import com.example.myapplication.model.Asset
import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import org.apache.commons.io.input.BOMInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.Charset

/**
 * CSV 讀寫（Big5 編碼）。
 *
 * Kotlin 筆記：
 * - `use { }` 取代 Java 的 try-with-resources：區塊結束（含例外）時自動 close，且會保留原始例外。
 * - Kotlin 沒有受檢例外：函式照樣可能拋例外，呼叫端用 try/catch 接即可，不需宣告 throws。
 * - 遷移期間曾為 Java 呼叫端加 @JvmStatic / @Throws，全專案轉 Kotlin 後已移除。
 */
object CsvManager {

    // ERP 報表匯出為 MS950；Android 無真正 CP950 codec，MS950/windows-950 都會 canonical
    // 成基礎 Big5，故直接指定 Big5 讀寫。Big5 家族無 BOM，寫檔不得加 BOM，否則 ERP 再匯入會出錯。
    private val BIG5: Charset = Charset.forName("Big5")

    // 讀取：透過 Uri（系統檔案選擇器）
    // 回傳 MutableList：ScanActivity 需要對這份清單做 add（盤盈新增），
    // 唯讀的呼叫端（MainActivity/SamplingActivity）再各自以 List 型別接收即可。
    fun read(resolver: ContentResolver, uri: Uri): MutableList<Asset> {
        val list = ArrayList<Asset>()

        val input = resolver.openInputStream(uri)
            ?: throw IOException("無法開啟輸入串流：$uri")

        // 三層資源依序包起來，最外層 use 結束時一路 close 到底層 InputStream。
        input.use { rawIn ->
            BOMInputStream.builder().setInputStream(rawIn).get().use { bomIn ->
                CSVReader(InputStreamReader(bomIn, BIG5)).use { reader ->
                    // Java 的 while ((row = readNext()) != null) → Kotlin 用 generateSequence 讀到 null 為止
                    generateSequence { reader.readNext() }
                        .filter { it.size >= 4 }
                        .forEach { row ->
                            val id = row[0].trim()
                            val name = row[1].trim()
                            val department = row[2].trim()
                            val location = row[3].trim()

                            // 第 5 欄：盤點狀態
                            val status = when (row.getOrElse(4) { "" }.trim()) {
                                "V" -> Asset.Status.MATCHED
                                "X" -> Asset.Status.UNMATCHED
                                else -> Asset.Status.UNCHECKED
                            }

                            val checkedAt = row.getOrElse(5) { "" }.trim()

                            list.add(Asset(id, name, department, location, status, checkedAt))
                        }
                }
            }
        }
        return list
    }

    fun write(resolver: ContentResolver, uri: Uri, assets: List<Asset>) {
        // 序列化先於落檔：先在記憶體用 Big5 把整份 CSV 產生完成，確認無誤後才單次寫入目的檔，
        // 避免序列化中途出錯留下半份損毀的既有檔案。
        val buffer = ByteArrayOutputStream()
        CSVWriter(OutputStreamWriter(buffer, BIG5)).use { writer ->
            for (a in assets) {
                val s = when (a.status) {
                    Asset.Status.MATCHED -> "V"
                    Asset.Status.UNMATCHED -> "X"
                    else -> ""
                }
                writer.writeNext(
                    arrayOf(
                        a.id,
                        a.name,
                        a.department,
                        a.location,
                        s,
                        a.checkedAt, // Asset.checkedAt 現在是非空 String，原本的 null 判斷可省
                    )
                )
            }
        }
        val payload = buffer.toByteArray()

        val os = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("無法開啟輸出串流：$uri")
        os.use {
            it.write(payload)
            it.flush()
        }
    }
}
