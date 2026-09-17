package com.eitc.assetscan.logic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * QR 字串解析純邏輯測試（JVM，不需相機／裝置）。
 * 格式 {@code id;name;department}；缺欄位補空字串、前後空白去除。
 */
public class ScannedTagTest {

    @Test
    public void parsesThreeFields() {
        ScannedTag tag = ScannedTag.parse("ASSET001;辦公桌;財務部");
        assertEquals("ASSET001", tag.id);
        assertEquals("辦公桌", tag.name);
        assertEquals("財務部", tag.department);
    }

    @Test
    public void trimsWhitespace() {
        ScannedTag tag = ScannedTag.parse("  ASSET001 ; 辦公桌 ; 財務部 ");
        assertEquals("ASSET001", tag.id);
        assertEquals("辦公桌", tag.name);
        assertEquals("財務部", tag.department);
    }

    @Test
    public void missingFields_fillEmpty() {
        ScannedTag tag = ScannedTag.parse("ASSET001;辦公桌");
        assertEquals("ASSET001", tag.id);
        assertEquals("辦公桌", tag.name);
        assertEquals("", tag.department);
    }

    @Test
    public void onlyId_restEmpty() {
        ScannedTag tag = ScannedTag.parse("ASSET001");
        assertEquals("ASSET001", tag.id);
        assertEquals("", tag.name);
        assertEquals("", tag.department);
    }

    @Test
    public void emptyRaw_allEmpty() {
        ScannedTag tag = ScannedTag.parse("");
        assertEquals("", tag.id);
        assertEquals("", tag.name);
        assertEquals("", tag.department);
    }

    @Test
    public void nullRaw_allEmpty() {
        ScannedTag tag = ScannedTag.parse(null);
        assertEquals("", tag.id);
        assertEquals("", tag.name);
        assertEquals("", tag.department);
    }

    @Test
    public void extraFields_ignoredBeyondDepartment() {
        ScannedTag tag = ScannedTag.parse("ASSET001;辦公桌;財務部;一樓;多餘");
        assertEquals("ASSET001", tag.id);
        assertEquals("辦公桌", tag.name);
        assertEquals("財務部", tag.department);
    }
}
