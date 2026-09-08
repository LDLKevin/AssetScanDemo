package com.example.myapplication.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.example.myapplication.R;
import com.example.myapplication.logic.ReticleGeometry;

/**
 * 掃描取景框覆蓋層（可重用）：框外半透明暗遮罩、四角括號、框內上下掃動的掃描線，
 * 以及命中時的閃色回饋（相符綠／不符紅）。全盤與抽盤兩掃描頁共用。
 *
 * <p>放在相機 {@code PreviewView} 之上、提示文字／手電筒之下即可。位置邏輯（置中正方形）
 * 抽到純可測的 {@link ReticleGeometry}；本類只負責繪製與動畫。
 *
 * <p>尊重系統動畫設定：當 {@code ANIMATOR_DURATION_SCALE} 為 0（使用者關閉動畫／省電）時，
 * 掃描線降級為靜態置中，不啟動動畫。掃描線動畫綁定 window 可見性，畫面退到背景即停止，
 * 避免相機已解綁後仍持續空轉重繪。
 */
public class ScannerOverlayView extends View {

    // 取景框大小：相對較短邊的比例，並設一個上限避免平板上過大。
    private static final float SIZE_FRACTION = 0.72f;
    private static final float MAX_SIZE_DP = 280f;

    private static final long LINE_SWEEP_MS = 2200L;
    private static final long FLASH_MS = 550L;
    private static final long STATIC_FLASH_MS = 250L;

    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bracketPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF frame = new RectF();
    private final Path bracketPath = new Path();   // 幾何只在 onSizeChanged 變動，建一次、每幀只畫

    private final float density;
    private final float bracketLen;
    private final float bracketRadius;
    private final float frameRadius;
    private final float lineInset;

    private final int scanLineColor;
    private final int flashSuccessColor;
    private final int flashErrorColor;

    // 系統動畫是否開啟；一次工作階段內視為固定，於 window 可見時刷新，避免每次掃描都查 Settings。
    private boolean animationsEnabled;

    private float scanT = 0.5f;              // 掃描線位置 0~1（0=上緣、1=下緣）
    @Nullable private ValueAnimator lineAnimator;

    private int flashColor = Color.TRANSPARENT;
    private float flashAlpha = 0f;           // 0~1
    @Nullable private ValueAnimator flashAnimator;
    private final Runnable clearFlash = () -> { flashAlpha = 0f; invalidate(); };

    public ScannerOverlayView(Context context) {
        this(context, null);
    }

    public ScannerOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        bracketLen = 30f * density;
        bracketRadius = 18f * density;
        frameRadius = 18f * density;
        lineInset = 6f * density;

        maskPaint.setStyle(Paint.Style.FILL);
        maskPaint.setColor(ContextCompat.getColor(context, R.color.scan_mask));

        bracketPaint.setStyle(Paint.Style.STROKE);
        bracketPaint.setStrokeWidth(4f * density);
        bracketPaint.setStrokeCap(Paint.Cap.ROUND);
        bracketPaint.setStrokeJoin(Paint.Join.ROUND);
        bracketPaint.setColor(ContextCompat.getColor(context, R.color.scan_reticle));

        scanLineColor = ContextCompat.getColor(context, R.color.scan_line);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f * density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(scanLineColor);

        lineGlowPaint.setStyle(Paint.Style.STROKE);
        lineGlowPaint.setStrokeWidth(9f * density);
        lineGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        lineGlowPaint.setColor(ColorUtils.setAlphaComponent(scanLineColor, 60));

        flashSuccessColor = ContextCompat.getColor(context, R.color.scan_flash_success);
        flashErrorColor = ContextCompat.getColor(context, R.color.scan_flash_error);
        flashPaint.setStyle(Paint.Style.STROKE);
        flashPaint.setStrokeWidth(6f * density);

        animationsEnabled = readAnimationsEnabled();
    }

    // ── 對外 API（兩掃描頁共用）─────────────────────────────

    /** 命中相符：取景框短暫閃綠。 */
    public void flashSuccess() {
        startFlash(flashSuccessColor);
    }

    /** 命中不符：取景框短暫閃紅。 */
    public void flashError() {
        startFlash(flashErrorColor);
    }

    // ── 生命週期與動畫 ───────────────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        ReticleGeometry.Rect r =
                ReticleGeometry.centeredSquare(w, h, SIZE_FRACTION, MAX_SIZE_DP * density);
        frame.set(r.left, r.top, r.right, r.bottom);
        buildBracketPath();
    }

    /** 掃描線動畫綁 window 可見性：畫面退到背景即停，回前景再啟。 */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            animationsEnabled = readAnimationsEnabled();
            startLineAnimation();
        } else {
            stopLineAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopLineAnimation();
        removeCallbacks(clearFlash);
        if (flashAnimator != null) {
            flashAnimator.cancel();
            flashAnimator = null;
        }
        super.onDetachedFromWindow();
    }

    private boolean readAnimationsEnabled() {
        float scale = Settings.Global.getFloat(
                getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        return scale != 0f;
    }

    private void startLineAnimation() {
        if (lineAnimator != null) return;
        if (!animationsEnabled) {
            scanT = 0.5f;                  // 降級：靜態置中
            invalidate();
            return;
        }
        lineAnimator = ValueAnimator.ofFloat(0f, 1f);
        lineAnimator.setDuration(LINE_SWEEP_MS);
        lineAnimator.setInterpolator(new LinearInterpolator());
        lineAnimator.setRepeatCount(ValueAnimator.INFINITE);
        lineAnimator.setRepeatMode(ValueAnimator.REVERSE);   // 上下往復
        lineAnimator.addUpdateListener(a -> {
            scanT = (float) a.getAnimatedValue();
            invalidate();
        });
        lineAnimator.start();
    }

    private void stopLineAnimation() {
        if (lineAnimator != null) {
            lineAnimator.cancel();
            lineAnimator = null;
        }
    }

    private void startFlash(int color) {
        flashColor = color;
        if (!animationsEnabled) {
            // 動畫關閉時仍給一次可見回饋：清掉前一個待清除，短暫顯示後再清。
            removeCallbacks(clearFlash);
            flashAlpha = 1f;
            invalidate();
            postDelayed(clearFlash, STATIC_FLASH_MS);
            return;
        }
        if (flashAnimator != null) flashAnimator.cancel();
        flashAnimator = ValueAnimator.ofFloat(1f, 0f);
        flashAnimator.setDuration(FLASH_MS);
        flashAnimator.addUpdateListener(a -> {
            flashAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        flashAnimator.start();
    }

    // ── 繪製 ────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (frame.width() <= 0 || frame.height() <= 0) return;

        drawMask(canvas);
        canvas.drawPath(bracketPath, bracketPaint);
        drawScanLine(canvas);
        drawFlash(canvas);
    }

    /** 框外四塊暗遮罩，框內透亮。 */
    private void drawMask(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        canvas.drawRect(0, 0, w, frame.top, maskPaint);            // 上
        canvas.drawRect(0, frame.bottom, w, h, maskPaint);         // 下
        canvas.drawRect(0, frame.top, frame.left, frame.bottom, maskPaint);   // 左
        canvas.drawRect(frame.right, frame.top, w, frame.bottom, maskPaint);  // 右
    }

    /** 四角括號（L 形、圓角）：幾何只在尺寸變動時重建，每幀只 drawPath。 */
    private void buildBracketPath() {
        float l = frame.left, t = frame.top, r = frame.right, b = frame.bottom;

        bracketPath.reset();
        if (frame.width() <= 0 || frame.height() <= 0) return;

        // 左上
        bracketPath.moveTo(l, t + bracketLen);
        bracketPath.lineTo(l, t + bracketRadius);
        bracketPath.quadTo(l, t, l + bracketRadius, t);
        bracketPath.lineTo(l + bracketLen, t);

        // 右上
        bracketPath.moveTo(r - bracketLen, t);
        bracketPath.lineTo(r - bracketRadius, t);
        bracketPath.quadTo(r, t, r, t + bracketRadius);
        bracketPath.lineTo(r, t + bracketLen);

        // 右下
        bracketPath.moveTo(r, b - bracketLen);
        bracketPath.lineTo(r, b - bracketRadius);
        bracketPath.quadTo(r, b, r - bracketRadius, b);
        bracketPath.lineTo(r - bracketLen, b);

        // 左下
        bracketPath.moveTo(l + bracketLen, b);
        bracketPath.lineTo(l + bracketRadius, b);
        bracketPath.quadTo(l, b, l, b - bracketRadius);
        bracketPath.lineTo(l, b - bracketLen);
    }

    private void drawScanLine(Canvas canvas) {
        float y = frame.top + lineInset + (frame.height() - 2 * lineInset) * clamp01(scanT);
        float x0 = frame.left + lineInset;
        float x1 = frame.right - lineInset;
        canvas.drawLine(x0, y, x1, y, lineGlowPaint);
        canvas.drawLine(x0, y, x1, y, linePaint);
    }

    private void drawFlash(Canvas canvas) {
        if (flashAlpha <= 0f || flashColor == Color.TRANSPARENT) return;
        int alpha = (int) (flashAlpha * 255);
        flashPaint.setColor(ColorUtils.setAlphaComponent(flashColor, alpha));
        canvas.drawRoundRect(frame, frameRadius, frameRadius, flashPaint);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
