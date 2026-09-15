package com.eitc.assetscan.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CropGeometryTest {

    @Test
    public void rotation0_noScaleMismatch_noMargin_cropEqualsFrame() {
        // previewView 跟 image 尺寸／長寬比完全一致，無旋轉、無緩衝：裁切應該原樣等於取景框。
        CropGeometry.Rect r = CropGeometry.mapFrameToImageCrop(
                200, 400,
                50, 150, 150, 250,
                0f,
                200, 400,
                0);
        assertRect(r, 50, 150, 150, 250);
    }

    @Test
    public void rotation0_fillCenterOffset_isAccountedFor() {
        // preview 是直的(1:2)，image 是正方形(1:1)：FILL_CENTER 只會顯示 image 中間那條窄帶。
        // 取景框覆蓋整個 preview 時，裁切應該正好是那條被裁掉兩側後留下的可見窄帶。
        CropGeometry.Rect r = CropGeometry.mapFrameToImageCrop(
                100, 200,
                0, 0, 100, 200,
                0f,
                200, 200,
                0);
        assertRect(r, 50, 0, 150, 200);
    }

    @Test
    public void rotation90_swapsAxesCorrectly() {
        // 感光元件是橫的(640x480)，畫面是直的(480x640)，旋轉90度轉正後尺寸跟 preview 完全吻合。
        CropGeometry.Rect r = CropGeometry.mapFrameToImageCrop(
                480, 640,
                100, 200, 300, 400,
                0f,
                640, 480,
                90);
        assertRect(r, 200, 180, 400, 380);
    }

    @Test
    public void marginFraction_padsFrameBeforeMapping() {
        CropGeometry.Rect r = CropGeometry.mapFrameToImageCrop(
                200, 200,
                80, 80, 120, 120,
                0.15f,
                200, 200,
                0);
        assertRect(r, 77, 77, 123, 123);
    }

    @Test
    public void degenerateInputs_returnNull() {
        // 取景框完全落在 preview 範圍外，夾邊界後寬度變 0
        assertNull(CropGeometry.mapFrameToImageCrop(
                200, 200,
                300, 0, 400, 100,
                0f,
                200, 200,
                0));
        // 影像尺寸不合理
        assertNull(CropGeometry.mapFrameToImageCrop(
                200, 200,
                50, 50, 150, 150,
                0f,
                0, 200,
                0));
        // 不支援的旋轉角度
        assertNull(CropGeometry.mapFrameToImageCrop(
                200, 200,
                50, 50, 150, 150,
                0f,
                200, 200,
                45));
    }

    private static void assertRect(CropGeometry.Rect r, int left, int top, int right, int bottom) {
        org.junit.Assert.assertNotNull(r);
        assertEquals(left, r.left);
        assertEquals(top, r.top);
        assertEquals(right, r.right);
        assertEquals(bottom, r.bottom);
    }
}
