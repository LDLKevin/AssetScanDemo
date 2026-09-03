package com.example.myapplication.model

/**
 * 財產項目。
 *
 * 全專案轉為 Kotlin 後，這裡從「保守版 class + @JvmField」收割成乾淨的 data class：
 * - 不再需要 @JvmField（沒有 Java 呼叫端要用欄位存取了）。
 * - id / name 盤點過程中不會變動 → 用 val；department / location / status / checkedAt 會被更新 → 用 var。
 * - data class 自動附送 equals / hashCode / toString / copy。
 */
data class Asset(
    val id: String,          // 財產編號
    val name: String,        // 財產名稱
    var department: String,  // 歸屬部門
    var location: String,    // 地點
    var status: Status,      // 盤點狀態
    var checkedAt: String,   // 盤點時間
) {
    enum class Status {
        UNCHECKED,   // 未盤點 → CSV 空白
        MATCHED,     // 已盤點且相符 → CSV "V"
        UNMATCHED    // 已盤點但不相符 → CSV "X"
    }
}
