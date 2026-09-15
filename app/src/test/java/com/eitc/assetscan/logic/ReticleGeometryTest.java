package com.eitc.assetscan.logic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ReticleGeometryTest {

    private static final float EPS = 0.001f;

    @Test
    public void squareContainer_centersAndScalesByFraction() {
        ReticleGeometry.Rect r = ReticleGeometry.centeredSquare(1000, 1000, 0.5f, 0);
        // side = min(1000,1000)*0.5 = 500，置中
        assertEquals(250f, r.left, EPS);
        assertEquals(250f, r.top, EPS);
        assertEquals(750f, r.right, EPS);
        assertEquals(750f, r.bottom, EPS);
    }

    @Test
    public void wideContainer_usesShorterSide_staysSquareAndCentered() {
        ReticleGeometry.Rect r = ReticleGeometry.centeredSquare(1000, 600, 0.8f, 0);
        // side = min(1000,600)*0.8 = 480
        assertEquals(480f, r.width(), EPS);
        assertEquals(480f, r.height(), EPS);
        assertEquals(260f, r.left, EPS);
        assertEquals(60f, r.top, EPS);
        assertEquals(740f, r.right, EPS);
        assertEquals(540f, r.bottom, EPS);
    }

    @Test
    public void maxSize_capsTheSide() {
        ReticleGeometry.Rect r = ReticleGeometry.centeredSquare(1000, 1000, 0.9f, 300);
        // side = min(900, 300) = 300
        assertEquals(300f, r.width(), EPS);
        assertEquals(350f, r.left, EPS);
        assertEquals(650f, r.right, EPS);
    }

    @Test
    public void maxSizeZeroOrNegative_isIgnored() {
        ReticleGeometry.Rect r = ReticleGeometry.centeredSquare(1000, 1000, 0.6f, -5);
        assertEquals(600f, r.width(), EPS);
    }

    @Test
    public void zeroContainer_yieldsEmptyRectWithoutError() {
        ReticleGeometry.Rect r = ReticleGeometry.centeredSquare(0, 0, 0.7f, 0);
        assertEquals(0f, r.width(), EPS);
        assertEquals(0f, r.height(), EPS);
    }

    @Test
    public void centerYFraction_movesFrameVertically_keepsHorizontalCenter() {
        // 360x780 容器，邊長 fraction=0.5 → side=180，中心點上移到 35%（cy=273）
        ReticleGeometry.Rect r = ReticleGeometry.centeredSquare(360, 780, 0.5f, 0, 0.35f);
        assertEquals(90f, r.left, EPS);    // 水平仍置中：(360-180)/2
        assertEquals(183f, r.top, EPS);    // cy(273) - half(90)
        assertEquals(270f, r.right, EPS);
        assertEquals(363f, r.bottom, EPS);
    }

    @Test
    public void fourArgOverload_defaultsToVerticalCenter() {
        ReticleGeometry.Rect withDefault = ReticleGeometry.centeredSquare(360, 780, 0.5f, 0);
        ReticleGeometry.Rect explicitHalf = ReticleGeometry.centeredSquare(360, 780, 0.5f, 0, 0.5f);
        assertEquals(explicitHalf.top, withDefault.top, EPS);
        assertEquals(explicitHalf.bottom, withDefault.bottom, EPS);
    }
}
