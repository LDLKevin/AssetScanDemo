package com.eitc.assetscan.logic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * QR 字串解析純邏輯測試（JVM，不需相機／裝置）。
 * 格式 {@code id;name;department;location}；缺欄位補空字串、前後空白去除。
 */
public class ScannedTagTest {

    @Test
    public void parsesFourFields() {
        ScannedTag tag = ScannedTag.parse("ASSET001;辦公桌;財務部;一樓");
        assertEquals("ASSET001", tag.id);
        assertEquals("辦公桌", tag.name);
        assertEquals("財務部", tag.department);
        assertEquals("一樓", tag.location);
    }

    @Test
    public void trimsWhitespace() {
        ScannedTag tag = ScannedTag.parse("  ASSET001 ; 辦公桌 ; 財務部 ; 一樓 ");
        assertEquals("ASSET001", tag.id);
        assertEquals("辦公桌", tag.name);
        assertEquals("財務部", tag.department);
        assertEquals("一樓", tag.location);
    }

    @Test
    public void missingFields_fillEmpty() {
        ScannedTag tag = ScannedTag.parse("ASSET001;辦公桌");
        assertEquals("ASSET001", tag.id);
        assertEquals("辦公桌", tag.name);
        assertEquals("", tag.department);
        assertEquals("", tag.location);
    }

    @Test
    public void onlyId_restEmpty() {
        ScannedTag tag = ScannedTag.parse("ASSET001");
        assertEquals("ASSET001", tag.id);
        assertEquals("", tag.name);
        assertEquals("", tag.department);
        assertEquals("", tag.location);
    }

    @Test
    public void emptyRaw_allEmpty() {
        ScannedTag tag = ScannedTag.parse("");
        assertEquals("", tag.id);
        assertEquals("", tag.name);
        assertEquals("", tag.department);
        assertEquals("", tag.location);
    }

    @Test
    public void nullRaw_allEmpty() {
        ScannedTag tag = ScannedTag.parse(null);
        assertEquals("", tag.id);
        assertEquals("", tag.name);
        assertEquals("", tag.department);
        assertEquals("", tag.location);
    }

    @Test
    public void extraFields_ignoredBeyondLocation() {
        ScannedTag tag = ScannedTag.parse("ASSET001;辦公桌;財務部;一樓;多餘;欄位");
        assertEquals("ASSET001", tag.id);
        assertEquals("辦公桌", tag.name);
        assertEquals("財務部", tag.department);
        assertEquals("一樓", tag.location);
    }
}
