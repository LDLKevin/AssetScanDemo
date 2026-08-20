package com.example.myapplication.model;

public class Asset {
    public String id;          // 財產編號
    public String name;        // 財產名稱
    public String department;  // 歸屬部門
    public String location;    // 地點
    public Status status;      // 盤點狀態
    public String checkedAt;   // 盤點時間

    public enum Status {
        UNCHECKED,   // 未盤點 → CSV 空白
        MATCHED,     // 已盤點且相符 → CSV "V"
        UNMATCHED    // 已盤點但不相符 → CSV "X"
    }

    public Asset(String id, String name, String department, String location,
                 Status status, String checkedAt) {
        this.id         = id;
        this.name       = name;
        this.department = department;
        this.location   = location;
        this.status     = status;
        this.checkedAt  = checkedAt;
    }
}