package com.example.myapplication.logic

import com.example.myapplication.model.Asset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 掃描判定純邏輯測試（JVM，不需相機／裝置）。
 * 只驗證對外行為：給 raw + 清單 + 格式規則 → 判定結果分類與命中的 Asset。
 */
class ScanClassifierTest {

    // fun interface 可用 SAM 建構子帶入 lambda
    private val validator = ScanClassifier.IdFormatValidator { AssetIdFormat.isValid(it) }

    private fun sampleList(): MutableList<Asset> = mutableListOf(
        Asset("ASSET001", "辦公桌", "財務部", "一樓", Asset.Status.UNCHECKED, ""),
        Asset("ASSET002", "螢幕", "資訊室", "二樓", Asset.Status.UNCHECKED, ""),
        // 部門與地點皆為空的財產，測試「只有 id、無其他欄位」的相符判定
        Asset("EMPTY001", "無標地資產", "", "", Asset.Status.UNCHECKED, ""),
    )

    @Test
    fun matched_whenIdInListAndDeptLocationEqual() {
        val list = sampleList()
        val r = ScanClassifier.classify("ASSET001;辦公桌;財務部;一樓", list, validator)

        assertEquals(ScanClassifier.Outcome.MATCHED, r.outcome)
        assertSame(list[0], r.asset)
    }

    @Test
    fun unmatched_whenDepartmentDiffers() {
        val list = sampleList()
        val r = ScanClassifier.classify("ASSET001;辦公桌;總務科;一樓", list, validator)

        assertEquals(ScanClassifier.Outcome.UNMATCHED, r.outcome)
        assertSame(list[0], r.asset)
    }

    @Test
    fun unmatched_whenLocationDiffers() {
        val list = sampleList()
        val r = ScanClassifier.classify("ASSET002;螢幕;資訊室;三樓", list, validator)

        assertEquals(ScanClassifier.Outcome.UNMATCHED, r.outcome)
        assertSame(list[1], r.asset)
    }

    @Test
    fun surplus_whenValidFormatButNotInList() {
        val list = sampleList()
        val r = ScanClassifier.classify("ZZZZ999;冷氣機;採購科;四樓", list, validator)

        assertEquals(ScanClassifier.Outcome.SURPLUS, r.outcome)
        assertNull(r.asset)
        assertEquals("ZZZZ999", r.id)
        assertEquals("冷氣機", r.name)
        assertEquals("採購科", r.department)
        assertEquals("四樓", r.location)
    }

    @Test
    fun ignored_whenFormatInvalid_url() {
        val list = sampleList()
        val r = ScanClassifier.classify("http://example.com/promo", list, validator)

        assertEquals(ScanClassifier.Outcome.IGNORED_INVALID, r.outcome)
    }

    @Test
    fun ignored_whenIdTooShort() {
        val list = sampleList()
        val r = ScanClassifier.classify("AB;辦公桌;財務部;一樓", list, validator)

        assertEquals(ScanClassifier.Outcome.IGNORED_INVALID, r.outcome)
    }

    @Test
    fun ignored_whenEmptyRaw() {
        val list = sampleList()
        assertEquals(
            ScanClassifier.Outcome.IGNORED_INVALID,
            ScanClassifier.classify("", list, validator).outcome,
        )
    }

    @Test
    fun matched_whenOnlyIdPresent_andListedAssetHasEmptyDeptLocation() {
        val list = sampleList()
        // 只有 id、沒有分號其他欄位 → dept/location 解析為空，與 EMPTY001 的空欄位相符
        val r = ScanClassifier.classify("EMPTY001", list, validator)

        assertEquals(ScanClassifier.Outcome.MATCHED, r.outcome)
        assertSame(list[2], r.asset)
    }
}
