package com.example.myapplication.logic;

import com.example.myapplication.model.Asset;

import java.util.List;

/**
 * 掃描判定：把「掃到的原始字串 + 目前財產清單 + id 格式規則」判成四種結果之一。
 * 純邏輯，不碰相機／UI，可用單元測試涵蓋。
 *
 * QR 解析格式：{@code id;name;department;location}
 */
public final class ScanClassifier {

    public enum Outcome {
        IGNORED_INVALID, // id 不成格式 → 誤觸，靜默忽略
        MATCHED,         // 在清單、部門+地點相符
        UNMATCHED,       // 在清單、部門或地點不符
        SURPLUS          // 格式正確但不在清單 → 盤盈（未列入清單）
    }

    /** id 格式判斷，可抽換（正式規則待使用單位確認）。 */
    public interface IdFormatValidator {
        boolean isValid(String id);
    }

    public static final class Result {
        public final Outcome outcome;
        /** MATCHED / UNMATCHED 時為清單中被命中的 Asset；其餘為 null。 */
        public final Asset asset;
        /** 解析出的欄位（SURPLUS 用來建立新財產；其餘僅供參考）。 */
        public final String id;
        public final String name;
        public final String department;
        public final String location;

        private Result(Outcome outcome, Asset asset,
                       String id, String name, String department, String location) {
            this.outcome    = outcome;
            this.asset      = asset;
            this.id         = id;
            this.name       = name;
            this.department = department;
            this.location   = location;
        }
    }

    private ScanClassifier() {}

    public static Result classify(String raw, List<Asset> assets, IdFormatValidator validator) {
        String[] parts = raw == null ? new String[0] : raw.split(";");
        String id         = parts.length > 0 ? parts[0].trim() : "";
        String name       = parts.length > 1 ? parts[1].trim() : "";
        String department = parts.length > 2 ? parts[2].trim() : "";
        String location   = parts.length > 3 ? parts[3].trim() : "";

        // 誤觸：id 空或不成格式 → 靜默忽略
        if (id.isEmpty() || validator == null || !validator.isValid(id)) {
            return new Result(Outcome.IGNORED_INVALID, null, id, name, department, location);
        }

        Asset matched = null;
        if (assets != null) {
            for (Asset a : assets) {
                if (a != null && a.id != null && a.id.equals(id)) {
                    matched = a;
                    break;
                }
            }
        }

        // 格式正確但不在清單 → 盤盈
        if (matched == null) {
            return new Result(Outcome.SURPLUS, null, id, name, department, location);
        }

        boolean isMatched = equalsSafe(matched.department, department)
                && equalsSafe(matched.location, location);
        Outcome outcome = isMatched ? Outcome.MATCHED : Outcome.UNMATCHED;
        return new Result(outcome, matched, id, name, department, location);
    }

    private static boolean equalsSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
