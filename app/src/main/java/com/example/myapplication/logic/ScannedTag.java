package com.example.myapplication.logic;

/**
 * 掃到的 QR 字串解析結果。格式的<b>唯一</b>解讀者——全盤、抽盤、抽盤掃描頁都經此解析，
 * 避免同一格式在多處各自手解而漂移。
 *
 * <p>QR 格式：{@code id;name;department;location}；缺少的欄位補空字串。純資料、可單元測試。
 */
public final class ScannedTag {

    public final String id;
    public final String name;
    public final String department;
    public final String location;

    private ScannedTag(String id, String name, String department, String location) {
        this.id         = id;
        this.name       = name;
        this.department = department;
        this.location   = location;
    }

    public static ScannedTag parse(String raw) {
        String[] parts = raw == null ? new String[0] : raw.split(";");
        String id         = parts.length > 0 ? parts[0].trim() : "";
        String name       = parts.length > 1 ? parts[1].trim() : "";
        String department = parts.length > 2 ? parts[2].trim() : "";
        String location   = parts.length > 3 ? parts[3].trim() : "";
        return new ScannedTag(id, name, department, location);
    }
}
