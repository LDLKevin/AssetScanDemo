package com.example.myapplication.logic;

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
}
