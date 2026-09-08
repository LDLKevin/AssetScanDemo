package com.example.myapplication.logic;

/**
 * 純幾何：算出「置中正方形取景框」的邊界。
 *
 * <p>不依賴任何 Android 型別（不用 {@code android.graphics.RectF}），
 * 故可在本機 JVM 單元測試，也讓取景框覆蓋層的位置邏輯有可驗證的接縫。
 * 繪製端（{@code ScannerOverlayView}）只負責把這個矩形畫出來。
 */
public final class ReticleGeometry {

    private ReticleGeometry() {}

    /** 純資料的矩形，避免引入 Android 型別。 */
    public static final class Rect {
        public final float left, top, right, bottom;

        public Rect(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public float width() { return right - left; }
        public float height() { return bottom - top; }
    }

    /**
     * 在 {@code width x height} 的容器內，取一個置中的正方形取景框。
     *
     * @param width       容器寬（px）
     * @param height      容器高（px）
     * @param fraction    邊長相對於「較短邊」的比例（0~1）
     * @param maxSizePx   邊長上限（px）；&lt;= 0 表示不設上限
     */
    public static Rect centeredSquare(int width, int height, float fraction, float maxSizePx) {
        float side = Math.min(width, height) * fraction;
        if (maxSizePx > 0) {
            side = Math.min(side, maxSizePx);
        }
        side = Math.max(0f, side);

        float cx = width / 2f;
        float cy = height / 2f;
        float half = side / 2f;
        return new Rect(cx - half, cy - half, cx + half, cy + half);
    }
}
