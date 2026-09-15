package com.eitc.assetscan.logic;

/**
 * 純幾何：把取景框（PreviewView／畫面座標）換算成相機影像 buffer 座標系裡的裁切矩形，
 * 讓 QR 解碼只吃取景框範圍內的畫面，而不是整個相機視野。
 *
 * <p>假設 {@code PreviewView} 用預設的 {@code FILL_CENTER}（置中裁切、填滿整個 View）。
 * 旋轉角度依 CameraX {@code ImageProxy.getImageInfo().getRotationDegrees()} 的定義：
 * 把原始 buffer 依此角度「順時針」轉正後，才是螢幕上看到的方向。
 *
 * <p>不依賴任何 Android 型別，可在本機 JVM 單元測試。繪製／相機端（{@code QrScanner}）
 * 只負責把換算結果套進解碼參數。
 */
public final class CropGeometry {

    private CropGeometry() {}

    /** 純資料的整數矩形，供 ZXing 的裁切參數直接使用。 */
    public static final class Rect {
        public final int left, top, right, bottom;

        public Rect(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public int width() { return right - left; }
        public int height() { return bottom - top; }
    }

    /**
     * @param previewWidth     PreviewView 寬（px）
     * @param previewHeight    PreviewView 高（px）
     * @param frameLeft        取景框在 PreviewView 座標系裡的左邊界（px）
     * @param frameTop         取景框在 PreviewView 座標系裡的上邊界（px）
     * @param frameRight       取景框在 PreviewView 座標系裡的右邊界（px）
     * @param frameBottom      取景框在 PreviewView 座標系裡的下邊界（px）
     * @param marginFraction   取景框四邊各放大的比例（例如 0.15 = 邊長多 15%），當作對準誤差的緩衝
     * @param imageWidth       相機影像 buffer 寬（px，轉正前的原始方向）
     * @param imageHeight      相機影像 buffer 高（px，轉正前的原始方向）
     * @param rotationDegrees  buffer 轉正成螢幕方向所需的順時針角度，只接受 0／90／180／270
     * @return 換算後的裁切矩形；輸入不合理或換算結果退化（寬或高 &lt;= 0）時回傳 {@code null}，
     *         呼叫端應退回整張影像解碼。
     */
    public static Rect mapFrameToImageCrop(
            float previewWidth, float previewHeight,
            float frameLeft, float frameTop, float frameRight, float frameBottom,
            float marginFraction,
            int imageWidth, int imageHeight,
            int rotationDegrees) {

        if (previewWidth <= 0 || previewHeight <= 0
                || imageWidth <= 0 || imageHeight <= 0) {
            return null;
        }
        if (rotationDegrees != 0 && rotationDegrees != 90
                && rotationDegrees != 180 && rotationDegrees != 270) {
            return null;
        }

        // 1) 取景框加緩衝（四邊各放大 marginFraction/2，邊長共放大 marginFraction）
        float side = frameRight - frameLeft;
        float pad = side * marginFraction / 2f;
        float pLeft = frameLeft - pad;
        float pTop = frameTop - pad;
        float pRight = frameRight + pad;
        float pBottom = frameBottom + pad;

        // 2) 換算成 PreviewView 的比例（0~1），並夾在邊界內
        float fLeft = clamp01(pLeft / previewWidth);
        float fTop = clamp01(pTop / previewHeight);
        float fRight = clamp01(pRight / previewWidth);
        float fBottom = clamp01(pBottom / previewHeight);

        // 3) 轉正後（螢幕方向）的影像尺寸：90/270 會把寬高互換
        boolean swapped = rotationDegrees == 90 || rotationDegrees == 270;
        float rotatedW = swapped ? imageHeight : imageWidth;
        float rotatedH = swapped ? imageWidth : imageHeight;

        // 4) FILL_CENTER：PreviewView 顯示的是「轉正後影像」置中裁切後的樣子
        float scale = Math.max(previewWidth / rotatedW, previewHeight / rotatedH);
        float visibleW = previewWidth / scale;
        float visibleH = previewHeight / scale;
        float offsetX = (rotatedW - visibleW) / 2f;
        float offsetY = (rotatedH - visibleH) / 2f;

        // 5) 取景框四個角換算到「轉正後影像」座標系，再各自轉回原始 buffer 座標系，
        //    取 min/max 得到一個 axis-aligned 的裁切矩形（旋轉後 left/top 不一定還是 left/top）。
        float[] fx = { fLeft, fRight, fLeft, fRight };
        float[] fy = { fTop, fTop, fBottom, fBottom };
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        for (int i = 0; i < 4; i++) {
            float rx = offsetX + fx[i] * visibleW;
            float ry = offsetY + fy[i] * visibleH;

            float ox, oy;
            switch (rotationDegrees) {
                case 90:
                    ox = ry;
                    oy = imageHeight - rx;
                    break;
                case 180:
                    ox = imageWidth - rx;
                    oy = imageHeight - ry;
                    break;
                case 270:
                    ox = imageWidth - ry;
                    oy = rx;
                    break;
                default: // 0
                    ox = rx;
                    oy = ry;
                    break;
            }
            minX = Math.min(minX, ox);
            maxX = Math.max(maxX, ox);
            minY = Math.min(minY, oy);
            maxY = Math.max(maxY, oy);
        }

        int left = clampInt(Math.round(minX), 0, imageWidth);
        int top = clampInt(Math.round(minY), 0, imageHeight);
        int right = clampInt(Math.round(maxX), 0, imageWidth);
        int bottom = clampInt(Math.round(maxY), 0, imageHeight);

        if (right <= left || bottom <= top) return null;

        return new Rect(left, top, right, bottom);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static int clampInt(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
