package com.example.myapplication.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.example.myapplication.model.Asset;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * AssetRepository 寫入動詞的純邏輯測試（JVM，不碰 I/O）。
 * 用 new AssetRepository() 建立獨立實例，避免 getInstance() 單例狀態互相污染。
 * 只驗證對外行為：呼叫動詞後的欄位變化與 isDirty()。
 */
public class AssetRepositoryTest {

    private List<Asset> sampleList() {
        List<Asset> list = new ArrayList<>();
        list.add(new Asset("ASSET001", "辦公桌", "財務部", "一樓", Asset.Status.UNCHECKED, ""));
        return list;
    }

    @Test
    public void freshRepository_isNotDirty() {
        assertFalse(new AssetRepository().isDirty());
    }

    @Test
    public void recordCheck_setsStatusTimeAndDirty() {
        AssetRepository repo = new AssetRepository();
        List<Asset> list = sampleList();
        repo.setAssets(list);
        Asset asset = list.get(0);

        repo.recordCheck(asset, Asset.Status.MATCHED);

        assertEquals(Asset.Status.MATCHED, asset.status);
        assertFalse("checkedAt 應被蓋上時間", asset.checkedAt.isEmpty());
        assertTrue(repo.isDirty());
    }

    @Test
    public void recordCheck_canMarkUnmatched() {
        AssetRepository repo = new AssetRepository();
        List<Asset> list = sampleList();
        repo.setAssets(list);

        repo.recordCheck(list.get(0), Asset.Status.UNMATCHED);

        assertEquals(Asset.Status.UNMATCHED, list.get(0).status);
        assertTrue(repo.isDirty());
    }

    @Test
    public void addAsMatched_appendsAsMatchedAndDirty() {
        AssetRepository repo = new AssetRepository();
        List<Asset> list = sampleList();
        repo.setAssets(list);

        Asset surplus = new Asset("ZZZZ999", "冷氣機", "採購科", "四樓", Asset.Status.UNCHECKED, "");
        repo.addAsMatched(surplus);

        assertEquals(2, list.size());
        assertSame(surplus, list.get(1));
        assertEquals(Asset.Status.MATCHED, surplus.status);
        assertFalse(surplus.checkedAt.isEmpty());
        assertTrue(repo.isDirty());
    }

    @Test
    public void recordEdit_updatesFieldsAndDirty_leavesCheckedAt() {
        AssetRepository repo = new AssetRepository();
        List<Asset> list = sampleList();
        repo.setAssets(list);
        Asset asset = list.get(0);

        repo.recordEdit(asset, "總務科", "三樓");

        assertEquals("總務科", asset.department);
        assertEquals("三樓", asset.location);
        assertEquals("checkedAt 不應被 recordEdit 動到", "", asset.checkedAt);
        assertTrue(repo.isDirty());
    }
}
