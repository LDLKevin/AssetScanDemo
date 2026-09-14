package com.example.myapplication.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.myapplication.R;
import com.example.myapplication.logic.ScannedTag;
import com.example.myapplication.model.Asset;

/**
 * 掃描結果回饋卡（可重用）：綠（相符）／橘（重複）／紅（不相符）三態，取代 Toast。
 *
 * <ul>
 *   <li>{@link #showSuccess} 相符：綠卡，1.5 秒自動消散。</li>
 *   <li>{@link #showDuplicate} 重複掃描：橘卡，自動消散、不覆蓋原記錄。</li>
 *   <li>{@link #showUnmatched} 不相符：紅卡，列出「掃到 vs 清單」差異對比，
 *       需按「確認寫入不相符」才落檔（不自動消散）。</li>
 * </ul>
 *
 * 全盤掃描頁使用；抽盤掃描頁（#10）可沿用同一元件。
 */
public class ScanResultCard extends FrameLayout {

    private static final long AUTO_DISMISS_MS = 1500L;
    private static final long ENTER_MS = 190L;

    private final LinearLayout cardBody;
    private final TextView title;
    private final TextView message;
    private final LinearLayout diffContainer;
    private final Button btnConfirm;
    private final Button btnDismiss;

    private final Runnable autoHide = this::hide;

    public ScanResultCard(Context context) {
        this(context, null);
    }

    public ScanResultCard(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(context).inflate(R.layout.view_scan_result_card, this, true);
        cardBody      = findViewById(R.id.card_body);
        title         = findViewById(R.id.result_title);
        message       = findViewById(R.id.result_message);
        diffContainer = findViewById(R.id.result_diff);
        btnConfirm    = findViewById(R.id.result_confirm);
        btnDismiss    = findViewById(R.id.result_dismiss);
        setVisibility(GONE);
    }

    // ── 三種結果 ────────────────────────────────────────

    /** 相符：綠卡，自動消散。 */
    public void showSuccess(String heading, String detail) {
        style(R.color.status_success_bg, R.color.status_success);
        title.setText(heading);
        setMessage(detail);
        diffContainer.setVisibility(GONE);
        btnConfirm.setVisibility(GONE);
        btnDismiss.setVisibility(GONE);
        showAutoDismiss();
    }

    /** 重複掃描：橘卡，自動消散，不覆蓋。 */
    public void showDuplicate(String heading, String detail) {
        showWarning(heading, detail);
    }

    /** 一般橘色提示（自動消散），如抽盤「請掃描指定財產」。 */
    public void showWarning(String heading, String detail) {
        style(R.color.status_warning_bg, R.color.status_warning);
        title.setText(heading);
        setMessage(detail);
        diffContainer.setVisibility(GONE);
        btnConfirm.setVisibility(GONE);
        btnDismiss.setVisibility(GONE);
        showAutoDismiss();
    }

    /**
     * 不相符：紅卡，含差異對比，需確認才寫入（不自動消散）。
     * 提供「略過（不寫入）」讓使用者明確放棄，避免直接掃下一張時靜默丟失這筆。
     */
    public void showUnmatched(String heading, ScannedTag scanned, Asset listed,
                              Runnable onConfirm, Runnable onDismiss) {
        style(R.color.status_error_bg, R.color.status_error);
        title.setText(heading);
        setMessage(listed.id + "　" + listed.name);
        buildDiff(scanned, listed);

        btnConfirm.setText("確認寫入不相符");
        btnConfirm.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(getContext(), R.color.status_error)));
        btnConfirm.setVisibility(VISIBLE);
        btnConfirm.setOnClickListener(v -> {
            if (onConfirm != null) onConfirm.run();
            hide();
        });

        btnDismiss.setVisibility(VISIBLE);
        btnDismiss.setOnClickListener(v -> {
            if (onDismiss != null) onDismiss.run();
            hide();
        });

        removeCallbacks(autoHide);       // 不相符需使用者確認，不自動消散
        reveal();
    }

    public void hide() {
        animate().cancel();
        removeCallbacks(autoHide);
        setAlpha(1f);
        setTranslationY(0f);
        btnDismiss.setVisibility(GONE);
        setVisibility(GONE);
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    // ── 內部 ────────────────────────────────────────────

    private void showAutoDismiss() {
        removeCallbacks(autoHide);
        reveal();
        postDelayed(autoHide, AUTO_DISMISS_MS);
    }

    /** 由下方滑入＋淡入；系統關閉動畫時直接顯示。 */
    private void reveal() {
        setVisibility(VISIBLE);
        animate().cancel();
        if (animationsEnabled()) {
            setAlpha(0f);
            setTranslationY(16f * getResources().getDisplayMetrics().density);
            animate().alpha(1f).translationY(0f)
                    .setDuration(ENTER_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            setAlpha(1f);
            setTranslationY(0f);
        }
    }

    private boolean animationsEnabled() {
        float scale = Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        return scale != 0f;
    }

    private void setMessage(String detail) {
        if (detail == null || detail.isEmpty()) {
            message.setVisibility(GONE);
        } else {
            message.setVisibility(VISIBLE);
            message.setText(detail);
        }
    }

    private void style(int bgColorRes, int fgColorRes) {
        cardBody.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(getContext(), bgColorRes)));
        int fg = ContextCompat.getColor(getContext(), fgColorRes);
        title.setTextColor(fg);
        message.setTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
    }

    /** 逐列列出「掃到 vs 清單」，不一致的欄位以紅字標示。 */
    private void buildDiff(ScannedTag scanned, Asset listed) {
        diffContainer.removeAllViews();
        addDiffRow("部門", scanned.department, listed.department);
        addDiffRow("地點", scanned.location, listed.location);
        diffContainer.setVisibility(VISIBLE);
    }

    private void addDiffRow(String label, String scannedVal, String listedVal) {
        boolean mismatch = !equalsSafe(scannedVal, listedVal);
        TextView row = new TextView(getContext());
        row.setTextSize(13f);
        row.setText(label + "　掃到「" + orDash(scannedVal) + "」・清單「" + orDash(listedVal) + "」");
        row.setTextColor(ContextCompat.getColor(getContext(),
                mismatch ? R.color.status_error : R.color.text_secondary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (2 * getResources().getDisplayMetrics().density);
        row.setLayoutParams(lp);
        row.setGravity(Gravity.START);
        diffContainer.addView(row);
    }

    private static String orDash(String s) {
        return (s == null || s.isEmpty()) ? "－" : s;
    }

    private static boolean equalsSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    protected void onDetachedFromWindow() {
        animate().cancel();
        removeCallbacks(autoHide);
        super.onDetachedFromWindow();
    }
}
