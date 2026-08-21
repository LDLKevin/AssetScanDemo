package com.example.myapplication.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.myapplication.model.Asset;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CsvManager 讀寫往返測試（需在裝置／模擬器上跑）。
 * Big5 是 Android 平台行為（無真正 CP950，MS950 canonical 成基礎 Big5），JVM 測不準，故用 instrumented。
 * 建議至少涵蓋 minSdk。
 */
@RunWith(AndroidJUnit4.class)
public class CsvManagerTest {

    @Test
    public void roundTrip_preservesChineseAndStatus_withoutBom() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ContentResolver resolver = ctx.getContentResolver();

        File file = new File(ctx.getCacheDir(), "csvmanager_roundtrip_test.csv");
        if (file.exists()) //noinspection ResultOfMethodCallIgnored
            file.delete();
        Uri uri = Uri.fromFile(file);

        List<Asset> original = new ArrayList<>();
        original.add(new Asset("F010701V37", "43人座大型巴士", "財務部", "一樓機房",
                Asset.Status.MATCHED, "2026-08-20 10:00:00"));
        original.add(new Asset("F010702V38", "辦公桌椅", "總務科", "三樓辦公室",
                Asset.Status.UNMATCHED, "2026-08-20 10:01:00"));
        original.add(new Asset("F010703V39", "液晶螢幕", "資訊室", "二樓",
                Asset.Status.UNCHECKED, ""));

        CsvManager.write(resolver, uri, original);

        // (1) 不得有 UTF-8 BOM
        byte[] bytes = readAll(file);
        boolean hasBom = bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF;
        assertFalse("Big5 檔不應含 BOM", hasBom);

        // (2) 往返一致：中文、狀態（V/X/空）、時間皆保留
        List<Asset> readBack = CsvManager.read(resolver, uri);
        assertEquals(original.size(), readBack.size());
        for (int i = 0; i < original.size(); i++) {
            Asset a = original.get(i);
            Asset b = readBack.get(i);
            assertEquals(a.id, b.id);
            assertEquals(a.name, b.name);
            assertEquals(a.department, b.department);
            assertEquals(a.location, b.location);
            assertEquals(a.status, b.status);
            assertEquals(a.checkedAt, b.checkedAt);
        }

        // (3) 反向鎖：以 UTF-8 解讀這份 Big5 位元組，中文串不應原樣出現
        //     （若哪天編碼被改回預設 UTF-8，這條會失敗）
        String asUtf8 = new String(bytes, StandardCharsets.UTF_8);
        assertFalse("以 UTF-8 解讀 Big5 位元組不應得到原中文",
                asUtf8.contains("43人座大型巴士"));
    }

    private static byte[] readAll(File file) throws Exception {
        try (InputStream is = new java.io.FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
