package com.eitc.assetscan.ui;

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

import com.eitc.assetscan.R;
import com.eitc.assetscan.logic.ScannedTag;
import com.eitc.assetscan.model.Asset;

/**
 * 掃描結果回饋卡（可重用）：綠（相符）／橘（重複、警告）／紅（不相符）／琥珀（盤盈待確認）四態。
 *
 * <p>統一規則：所有卡片持續顯示，直到下一筆格式正確的財產被掃描到（由呼叫端在下一次掃描時
 * 換一張新卡取代），不再用計時器自動消散；右上角一律提供關閉（✕）。
 *
 * <ul>
 *   <li>{@link #showSuccess} 相符：綠卡，已即時寫入，✕ 純粹關畫面。</li>
 *   <li>{@link #showDuplicate} / {@link #showWarning} 重複、一般提示：橘卡，✕ 純粹關畫面。</li>
 *   <li>{@link #showUnmatchedWritten} 不相符（部門）：紅卡，已即時寫入，列出「掃到 vs 清單」
 *       的部門差異，✕ 純粹關畫面。</li>
 *   <li>{@link #showConfirm} 盤盈（清單外編號）：琥珀卡，需按「確認寫入不相符」才落檔；
 *       四態中唯一會暫停相機的一種，✕ 等同「略過」（放棄寫入、解除暫停）。</li>
 * </ul>
 *
 * 全盤掃描頁使用；抽盤掃描頁沿用同一元件。
 */
public class ScanResultCard extends FrameLayout {

    private static final long ENTER_MS = 190L;

    private final LinearLayout cardBody;
    private final TextView title;
    private final TextView message;
    private final LinearLayout diffContainer;
    private final TextView badge;
    private final LinearLayout actionsRow;
    private final Button btnConfirm;
    private final Button btnDismiss;
    private final TextView btnClose;

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
        badge         = findViewById(R.id.result_badge);
        actionsRow    = findViewById(R.id.result_actions);
        btnConfirm    = findViewById(R.id.result_confirm);
        btnDismiss    = findViewById(R.id.result_dismiss);
        btnClose      = findViewById(R.id.result_close);
        setVisibility(GONE);
    }

    // ── 四種結果 ────────────────────────────────────────

    /** 相符：綠卡，已即時寫入，不阻塞。 */
    public void showSuccess(String heading, String detail) {
        showAmbient(R.color.status_success_bg, R.color.status_success, heading, detail,
                "已寫入・相符");
    }

    /** 重複掃描：橘卡，不落檔、不覆蓋原記錄，不阻塞。 */
    public void showDuplicate(String heading, String detail) {
        showAmbient(R.color.status_warning_bg, R.color.status_warning, heading, detail,
                "不覆蓋原記錄");
    }

    /** 一般橘色提示（不阻塞），如抽盤「請掃描指定財產」。 */
    public void showWarning(String heading, String detail) {
        showAmbient(R.color.status_warning_bg, R.color.status_warning, heading, detail, null);
    }

    private void showAmbient(int bgColorRes, int fgColorRes, String heading, String detail,
                             String badgeText) {
        style(bgColorRes, fgColorRes);
        title.setText(heading);
        setMessage(detail);
        diffContainer.setVisibility(GONE);
        setBadge(badgeText, fgColorRes);
        actionsRow.setVisibility(GONE);
        btnClose.setOnClickListener(v -> hide());
        reveal();
    }

    /**
     * 不相符（部門比對失敗）：紅卡，掃到當下已自動落檔，列出「掃到 vs 清單」的部門差異。
     * 不阻塞相機，✕ 純粹關畫面（與是否已寫入無關）。
     */
    public void showUnmatchedWritten(String heading, ScannedTag scanned, Asset listed) {
        style(R.color.status_error_bg, R.color.status_error);
        title.setText(heading);
        setMessage(listed.id + "　" + listed.name);
        buildDepartmentDiff(scanned, listed);
        setBadge("已自動寫入不相符", R.color.status_error);
        actionsRow.setVisibility(GONE);
        btnClose.setOnClickListener(v -> hide());
        reveal();
    }

    /**
     * 盤盈（清單外編號）：琥珀卡，尚未寫入，需按「確認寫入不相符」才落檔。
     * 四態中唯一會阻塞相機的一種；✕ 等同「略過」，放棄寫入並解除阻塞。
     */
    public void showConfirm(String heading, String detail, String confirmLabel,
                            int bgColorRes, int fgColorRes,
                            Runnable onConfirm, Runnable onDismiss) {
        style(bgColorRes, fgColorRes);
        title.setText(heading);
        setMessage(detail);
        diffContainer.setVisibility(GONE);
        setBadge("尚未寫入・待確認", fgColorRes);

        btnConfirm.setText(confirmLabel);
        btnConfirm.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(getContext(), fgColorRes)));
        btnConfirm.setOnClickListener(v -> {
            if (onConfirm != null) onConfirm.run();
            hide();
        });

        btnDismiss.setOnClickListener(v -> {
            if (onDismiss != null) onDismiss.run();
            hide();
        });
        actionsRow.setVisibility(VISIBLE);

        // 此卡會阻塞相機：✕ 不能只是關畫面，等同「略過」明確解除阻塞。
        btnClose.setOnClickListener(v -> {
            if (onDismiss != null) onDismiss.run();
            hide();
        });

        reveal();
    }

    public void hide() {
        animate().cancel();
        setAlpha(1f);
        setTranslationY(0f);
        setVisibility(GONE);
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    // ── 內部 ────────────────────────────────────────────

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

    private void setBadge(String text, int fgColorRes) {
        if (text == null) {
            badge.setVisibility(GONE);
            return;
        }
        badge.setVisibility(VISIBLE);
        badge.setText(text);
        badge.setTextColor(ContextCompat.getColor(getContext(), fgColorRes));
    }

    private void style(int bgColorRes, int fgColorRes) {
        cardBody.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(getContext(), bgColorRes)));
        int fg = ContextCompat.getColor(getContext(), fgColorRes);
        title.setTextColor(fg);
        message.setTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
    }

    /** 掃到 vs 清單的歸屬部門對比（唯一參與比對的欄位）；不一致以紅字標示。 */
    private void buildDepartmentDiff(ScannedTag scanned, Asset listed) {
        diffContainer.removeAllViews();
        addDiffRow("部門", scanned.department, listed.department);
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
        super.onDetachedFromWindow();
    }
}
