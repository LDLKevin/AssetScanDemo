package com.eitc.assetscan.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.eitc.assetscan.model.Asset;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CsvManager 讀寫往返測試（需在裝置／模擬器上跑）。
 */
@RunWith(AndroidJUnit4.class)
public class CsvManagerTest {

    @Test
    public void roundTrip_preservesChineseAndStatus_asUtf8WithoutBom() throws Exception {
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

        // (1) 不得有 UTF-8 BOM（下游用程式讀取，不需要 BOM）
        byte[] bytes = readAll(file);
        boolean hasBom = bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF;
        assertFalse("UTF-8 輸出不應含 BOM", hasBom);

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

        // (3) 正向鎖：位元組本身就是合法 UTF-8，直接解碼可得原中文
        //     （若哪天編碼被改回 Big5/MS950，這條會失敗）
        String asUtf8 = new String(bytes, StandardCharsets.UTF_8);
        assertTrue("以 UTF-8 解讀應得到原中文", asUtf8.contains("43人座大型巴士"));
    }

    @Test
    public void read_throwsClearError_whenFileIsNotUtf8() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ContentResolver resolver = ctx.getContentResolver();

        File file = new File(ctx.getCacheDir(), "csvmanager_non_utf8_test.csv");
        if (file.exists()) //noinspection ResultOfMethodCallIgnored
            file.delete();

        // 用 Big5 寫一份誤放進來的舊檔：中文多位元組序列在 UTF-8 下大多不合法。
        String line = "F010701V37,43人座大型巴士,財務部,一樓機房,V,2026-08-20 10:00:00\n";
        byte[] big5Bytes = line.getBytes(Charset.forName("Big5"));
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(big5Bytes);
        }

        try {
            CsvManager.read(resolver, Uri.fromFile(file));
            fail("非 UTF-8 檔案應該要拋出例外，而不是靜默解出亂碼");
        } catch (Exception expected) {
            // 預期行為：明確失敗，訊息可辨識為編碼問題
        }
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
