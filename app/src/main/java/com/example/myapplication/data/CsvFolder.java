package com.example.myapplication.data;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 以「資料夾（Tree）」為單位存取 CSV 與其同資料夾檔案。
 *
 * <p>改用資料夾選取後，App 持有整個資料夾的讀寫權限，才能在 CSV 旁邊建立其他檔案
 * （例如 QR 破損時的標籤存證照片，見拍照存證）。單一檔案的 SAF Uri 取不到上層
 * 資料夾，故無法做到。
 */
public final class CsvFolder {

    private CsvFolder() {}

    /** 列出資料夾內所有 .csv 檔（不遞迴）。 */
    public static List<DocumentFile> findCsvFiles(Context context, Uri treeUri) {
        List<DocumentFile> csvs = new ArrayList<>();
        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null) return csvs;
        for (DocumentFile f : tree.listFiles()) {
            String name = f.getName();
            if (f.isFile() && name != null && name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                csvs.add(f);
            }
        }
        return csvs;
    }

    /**
     * 在資料夾內建立一個新檔並回傳其 Uri（供拍照存證把照片寫到 CSV 同資料夾）。
     *
     * @param mimeType 例如 {@code "image/jpeg"}
     * @param displayName 檔名（含副檔名）
     * @return 新檔 Uri；建立失敗回傳 null
     */
    public static Uri createFile(Context context, Uri treeUri, String mimeType, String displayName) {
        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null) return null;
        DocumentFile created = tree.createFile(mimeType, displayName);
        return created == null ? null : created.getUri();
    }
}
