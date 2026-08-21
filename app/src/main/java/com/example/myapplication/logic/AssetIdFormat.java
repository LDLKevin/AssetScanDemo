package com.example.myapplication.logic;

/**
 * 資產編號格式判斷。用來把「相機誤觸到的別的 QR／條碼」擋在盤點流程之外。
 *
 * ⚠️ 暫定實作：真正的編號規則（幾碼／開頭是否固定／英數位置）待使用單位確認。
 * 這裡先用「長度 + 純英數字元」的臨時規則擋掉明顯不成格式的誤觸（URL、含空白或中文的字串等）。
 * 拿到正式規則後，只需替換 {@link #isValid(String)} 的實作，不動其他邏輯。
 */
public final class AssetIdFormat {

    private static final int MIN_LEN = 6;
    private static final int MAX_LEN = 24;

    private AssetIdFormat() {}

    public static boolean isValid(String id) {
        if (id == null) return false;
        int n = id.length();
        if (n < MIN_LEN || n > MAX_LEN) return false;
        for (int i = 0; i < n; i++) {
            char c = id.charAt(i);
            boolean alnum = (c >= '0' && c <= '9')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z');
            if (!alnum) return false;
        }
        return true;
    }
}
