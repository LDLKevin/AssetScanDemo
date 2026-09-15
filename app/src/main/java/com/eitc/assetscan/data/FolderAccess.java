package com.eitc.assetscan.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;

/**
 * 記住使用者授權的資料夾（Tree Uri），讓 CSV 載入只需授權一次。
 *
 * <p>SAF 的資料夾授權本身是可長期保留的（{@code takePersistableUriPermission}），
 * 但需自行把 Uri 存下、下次啟動時還原並確認權限仍在，否則每次冷啟動都得重選資料夾。
 */
public final class FolderAccess {

    private static final String PREFS = "folder_access";
    private static final String KEY_TREE = "tree_uri";

    private FolderAccess() {}

    /** 記住這個資料夾（授權後呼叫）。 */
    public static void remember(Context context, Uri treeUri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TREE, treeUri.toString())
                .apply();
    }

    /**
     * 還原上次記住的資料夾；若權限已不在（使用者撤銷、資料被清）則回傳 null，
     * 呼叫端此時應重新請使用者選資料夾。
     */
    public static Uri restore(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_TREE, null);
        if (saved == null) return null;

        Uri uri = Uri.parse(saved);
        for (UriPermission p : context.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(uri) && p.isReadPermission() && p.isWritePermission()) {
                return uri;
            }
        }
        return null; // 權限已失效
    }
}
