package com.example.myapplication.logic

import com.example.myapplication.model.Asset

/**
 * 掃描判定：把「掃到的原始字串 + 目前財產清單 + id 格式規則」判成四種結果之一。
 * 純邏輯，不碰相機／UI，可用單元測試涵蓋。
 *
 * QR 解析格式：`id;name;department;location`
 *
 * Kotlin 筆記：
 * - `object` 取代 Java 的「final class + private 建構子 + static 方法」。
 * - `IdFormatValidator` 用 `fun interface`（Kotlin 的單一抽象方法介面），
 *   仍可讓 Java 端用 `AssetIdFormat::isValid` 這種方法參照傳入。
 * - `Result` 用 data class 收掉原本一整包 final 欄位 + 建構子的樣板（附送 equals/hashCode/toString/copy）。
 */
object ScanClassifier {

    enum class Outcome {
        IGNORED_INVALID, // id 不成格式 → 誤觸，靜默忽略
        MATCHED,         // 在清單、部門+地點相符
        UNMATCHED,       // 在清單、部門或地點不符
        SURPLUS          // 格式正確但不在清單 → 盤盈（未列入清單）
    }

    /** id 格式判斷，可抽換（正式規則待使用單位確認）。 */
    fun interface IdFormatValidator {
        fun isValid(id: String): Boolean
    }

    data class Result(
        val outcome: Outcome,
        /** MATCHED / UNMATCHED 時為清單中被命中的 Asset；其餘為 null。 */
        val asset: Asset?,
        /** 解析出的欄位（SURPLUS 用來建立新財產；其餘僅供參考）。 */
        val id: String,
        val name: String,
        val department: String,
        val location: String,
    )

    fun classify(raw: String?, assets: List<Asset>?, validator: IdFormatValidator?): Result {
        val parts = raw?.split(";").orEmpty()
        val id = parts.getOrNull(0)?.trim().orEmpty()
        val name = parts.getOrNull(1)?.trim().orEmpty()
        val department = parts.getOrNull(2)?.trim().orEmpty()
        val location = parts.getOrNull(3)?.trim().orEmpty()

        // 誤觸：id 空或不成格式 → 靜默忽略
        if (id.isEmpty() || validator == null || !validator.isValid(id)) {
            return Result(Outcome.IGNORED_INVALID, null, id, name, department, location)
        }

        // 格式正確但不在清單 → 盤盈
        val matched = assets?.firstOrNull { it.id == id }
            ?: return Result(Outcome.SURPLUS, null, id, name, department, location)

        // Kotlin 的 == 本來就 null 安全，原本的 equalsSafe 輔助方法可以整個省掉。
        val isMatched = matched.department == department && matched.location == location
        val outcome = if (isMatched) Outcome.MATCHED else Outcome.UNMATCHED
        return Result(outcome, matched, id, name, department, location)
    }
}
