package com.example.myapplication.logic

/**
 * 資產編號格式判斷。用來把「相機誤觸到的別的 QR／條碼」擋在盤點流程之外。
 *
 * ⚠️ 暫定實作：真正的編號規則（幾碼／開頭是否固定／英數位置）待使用單位確認。
 * 這裡先用「長度 + 純英數字元」的臨時規則擋掉明顯不成格式的誤觸（URL、含空白或中文的字串等）。
 * 拿到正式規則後，只需替換 [isValid] 的實作，不動其他邏輯。
 *
 * Kotlin 筆記：
 * - 原本的「final class + private 建構子」靜態工具類，在 Kotlin 用 `object`（單例）表達最自然。
 * - 遷移期間曾為 Java 呼叫端加上 @JvmStatic；全專案轉 Kotlin 後已移除（Kotlin 直接 `AssetIdFormat.isValid(x)` 即可）。
 */
object AssetIdFormat {

    private const val MIN_LEN = 6
    private const val MAX_LEN = 24

    fun isValid(id: String?): Boolean {
        if (id == null) return false
        if (id.length !in MIN_LEN..MAX_LEN) return false
        // Java 版是逐字元 for 迴圈；Kotlin 用 all 一行表達「每個字元都是英數」。
        return id.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' }
    }
}
