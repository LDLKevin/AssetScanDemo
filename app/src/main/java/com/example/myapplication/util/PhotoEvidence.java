package com.example.myapplication.util;

import android.content.Context;
import android.net.Uri;

import com.example.myapplication.data.AssetRepository;
import com.example.myapplication.data.CsvFolder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 拍照存證：QR 破損無法掃描時，拍整張標籤存到 CSV 同資料夾，供後續人工上傳 ERP。
 *
 * <p>檔名以財產編號＋時間命名，讓 ERP 端可人工對應；<b>不</b>修改 CSV 欄位結構
 * （維持 {@code id,name,department,location,status,checkedAt} 供 ERP 匯入）。
 */
public final class PhotoEvidence {

    private PhotoEvidence() {}

    /** {@code <財產編號>_<yyyyMMdd_HHmmss>.jpg}；無編號時以 UNKNOWN 命名。 */
    public static String buildFileName(String assetId) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String id = (assetId == null || assetId.trim().isEmpty()) ? "UNKNOWN" : assetId.trim();
        return id + "_" + ts + ".jpg";
    }

    /**
     * 在 CSV 所在資料夾建立一個 jpg 空檔，回傳其 Uri（供系統相機寫入）。
     * 尚未載入資料夾（無 treeUri）時回傳 null。檔名請以 {@link #buildFileName(String)} 產生。
     */
    public static Uri createInCsvFolder(Context context, String fileName) {
        Uri tree = AssetRepository.getInstance().getTreeUri();
        if (tree == null) return null;
        return CsvFolder.createFile(context, tree, "image/jpeg", fileName);
    }
}
