package com.eitc.assetscan.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.eitc.assetscan.model.Asset;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 掃描判定純邏輯測試（JVM，不需相機／裝置）。
 * 只驗證對外行為：給 raw + 清單 + 格式規則 → 判定結果分類與命中的 Asset。
 */
public class ScanClassifierTest {

    private final ScanClassifier.IdFormatValidator validator = AssetIdFormat::isValid;

    private List<Asset> sampleList() {
        List<Asset> list = new ArrayList<>();
        list.add(new Asset("ASSET001", "辦公桌", "財務部", "一樓", Asset.Status.UNCHECKED, ""));
        list.add(new Asset("ASSET002", "螢幕", "資訊室", "二樓", Asset.Status.UNCHECKED, ""));
        // 部門與地點皆為空的財產，測試「只有 id、無其他欄位」的相符判定
        list.add(new Asset("EMPTY001", "無標地資產", "", "", Asset.Status.UNCHECKED, ""));
        return list;
    }

    @Test
    public void matched_whenIdInListAndDeptLocationEqual() {
        List<Asset> list = sampleList();
        ScanClassifier.Result r =
                ScanClassifier.classify("ASSET001;辦公桌;財務部;一樓", list, validator);

        assertEquals(ScanClassifier.Outcome.MATCHED, r.outcome);
        assertSame(list.get(0), r.asset);
    }

    @Test
    public void unmatched_whenDepartmentDiffers() {
        List<Asset> list = sampleList();
        ScanClassifier.Result r =
                ScanClassifier.classify("ASSET001;辦公桌;總務科;一樓", list, validator);

        assertEquals(ScanClassifier.Outcome.UNMATCHED, r.outcome);
        assertSame(list.get(0), r.asset);
    }

    @Test
    public void unmatched_whenLocationDiffers() {
        List<Asset> list = sampleList();
        ScanClassifier.Result r =
                ScanClassifier.classify("ASSET002;螢幕;資訊室;三樓", list, validator);

        assertEquals(ScanClassifier.Outcome.UNMATCHED, r.outcome);
        assertSame(list.get(1), r.asset);
    }

    @Test
    public void surplus_whenValidFormatButNotInList() {
        List<Asset> list = sampleList();
        ScanClassifier.Result r =
                ScanClassifier.classify("ZZZZ999;冷氣機;採購科;四樓", list, validator);

        assertEquals(ScanClassifier.Outcome.SURPLUS, r.outcome);
        assertNull(r.asset);
        assertEquals("ZZZZ999", r.id);
        assertEquals("冷氣機", r.name);
        assertEquals("採購科", r.department);
        assertEquals("四樓", r.location);
    }

    @Test
    public void ignored_whenFormatInvalid_url() {
        List<Asset> list = sampleList();
        ScanClassifier.Result r =
                ScanClassifier.classify("http://example.com/promo", list, validator);

        assertEquals(ScanClassifier.Outcome.IGNORED_INVALID, r.outcome);
    }

    @Test
    public void ignored_whenIdTooShort() {
        List<Asset> list = sampleList();
        ScanClassifier.Result r =
                ScanClassifier.classify("AB;辦公桌;財務部;一樓", list, validator);

        assertEquals(ScanClassifier.Outcome.IGNORED_INVALID, r.outcome);
    }

    @Test
    public void ignored_whenEmptyRaw() {
        List<Asset> list = sampleList();
        assertEquals(ScanClassifier.Outcome.IGNORED_INVALID,
                ScanClassifier.classify("", list, validator).outcome);
    }

    @Test
    public void matched_whenOnlyIdPresent_andListedAssetHasEmptyDeptLocation() {
        List<Asset> list = sampleList();
        // 只有 id、沒有分號其他欄位 → dept/location 解析為空，與 EMPTY001 的空欄位相符
        ScanClassifier.Result r =
                ScanClassifier.classify("EMPTY001", list, validator);

        assertEquals(ScanClassifier.Outcome.MATCHED, r.outcome);
        assertSame(list.get(2), r.asset);
    }

    // ── 共用相符規則 matches()（全盤與抽盤共用）─────────────

    @Test
    public void matches_trueWhenDeptAndLocationEqual() {
        Asset asset = new Asset("ASSET001", "辦公桌", "財務部", "一樓", Asset.Status.UNCHECKED, "");
        ScannedTag tag = ScannedTag.parse("ASSET001;辦公桌;財務部;一樓");
        assertTrue(ScanClassifier.matches(asset, tag));
    }

    @Test
    public void matches_falseWhenDepartmentDiffers() {
        Asset asset = new Asset("ASSET001", "辦公桌", "財務部", "一樓", Asset.Status.UNCHECKED, "");
        ScannedTag tag = ScannedTag.parse("ASSET001;辦公桌;總務科;一樓");
        assertFalse(ScanClassifier.matches(asset, tag));
    }

    @Test
    public void matches_falseWhenLocationDiffers() {
        Asset asset = new Asset("ASSET001", "辦公桌", "財務部", "一樓", Asset.Status.UNCHECKED, "");
        ScannedTag tag = ScannedTag.parse("ASSET001;辦公桌;財務部;三樓");
        assertFalse(ScanClassifier.matches(asset, tag));
    }

    @Test
    public void matches_trueWhenBothEmpty_nullSafe() {
        // 財產欄位為 null、標籤解析為空字串 → 視為不相等？equalsSafe：null vs "" 不等
        Asset nullFields = new Asset("EMPTY001", "無標地資產", null, null, Asset.Status.UNCHECKED, "");
        ScannedTag emptyTag = ScannedTag.parse("EMPTY001");
        assertFalse(ScanClassifier.matches(nullFields, emptyTag));

        // 財產空字串、標籤空字串 → 相等
        Asset emptyFields = new Asset("EMPTY001", "無標地資產", "", "", Asset.Status.UNCHECKED, "");
        assertTrue(ScanClassifier.matches(emptyFields, emptyTag));
    }
}
