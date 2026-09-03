package com.example.myapplication.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.model.Asset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * CsvManager 讀寫往返測試（需在裝置／模擬器上跑）。
 * Big5 是 Android 平台行為（無真正 CP950，MS950 canonical 成基礎 Big5），JVM 測不準，故用 instrumented。
 * 建議至少涵蓋 minSdk。
 */
@RunWith(AndroidJUnit4::class)
class CsvManagerTest {

    @Test
    fun roundTrip_preservesChineseAndStatus_withoutBom() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = ctx.contentResolver

        val file = File(ctx.cacheDir, "csvmanager_roundtrip_test.csv")
        if (file.exists()) file.delete()
        val uri = android.net.Uri.fromFile(file)

        val original = mutableListOf(
            Asset("F010701V37", "43人座大型巴士", "財務部", "一樓機房", Asset.Status.MATCHED, "2026-08-20 10:00:00"),
            Asset("F010702V38", "辦公桌椅", "總務科", "三樓辦公室", Asset.Status.UNMATCHED, "2026-08-20 10:01:00"),
            Asset("F010703V39", "液晶螢幕", "資訊室", "二樓", Asset.Status.UNCHECKED, ""),
        )

        CsvManager.write(resolver, uri, original)

        // (1) 不得有 UTF-8 BOM　（File.readBytes() 一次讀完整份，取代 Java 的手動迴圈）
        val bytes = file.readBytes()
        val hasBom = bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        assertFalse("Big5 檔不應含 BOM", hasBom)

        // (2) 往返一致：中文、狀態（V/X/空）、時間皆保留
        val readBack = CsvManager.read(resolver, uri)
        assertEquals(original.size, readBack.size)
        for (i in original.indices) {
            val a = original[i]
            val b = readBack[i]
            assertEquals(a.id, b.id)
            assertEquals(a.name, b.name)
            assertEquals(a.department, b.department)
            assertEquals(a.location, b.location)
            assertEquals(a.status, b.status)
            assertEquals(a.checkedAt, b.checkedAt)
        }

        // (3) 反向鎖：以 UTF-8 解讀這份 Big5 位元組，中文串不應原樣出現
        //     （若哪天編碼被改回預設 UTF-8，這條會失敗）
        val asUtf8 = String(bytes, StandardCharsets.UTF_8)
        assertFalse("以 UTF-8 解讀 Big5 位元組不應得到原中文", asUtf8.contains("43人座大型巴士"))
    }
}
