package com.example.myapplication;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.myapplication.data.AssetRepository;
import com.example.myapplication.model.Asset;

import java.util.List;

public class ModeSelectActivity extends AppCompatActivity {

    private static final String PREFS = "inventory";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mode_select);

        // 處理瀏海
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.mode_root),
                (view, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    view.setPadding(
                            view.getPaddingLeft(),
                            bars.top + view.getPaddingTop(),
                            view.getPaddingRight(),
                            bars.bottom + view.getPaddingBottom()
                    );
                    return WindowInsetsCompat.CONSUMED;
                }
        );

        // 全盤
        findViewById(R.id.btn_full).setOnClickListener(v ->
                startActivity(new Intent(this, MainActivity.class))
        );

        // 抽盤
        findViewById(R.id.btn_sampling).setOnClickListener(v ->
                startActivity(new Intent(this, SamplingActivity.class))
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSummary();
    }

    /** 顯示上次作業摘要：優先用記憶體現況並存續到 prefs；無資料時讀 prefs，仍無則顯示空狀態。 */
    private void updateSummary() {
        TextView main = findViewById(R.id.tv_summary_main);
        TextView sub  = findViewById(R.id.tv_summary_sub);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        List<Asset> assets = AssetRepository.getInstance().getAssets();
        if (assets != null && !assets.isEmpty()) {
            int total = assets.size();
            int checked = 0, unmatched = 0;
            String latest = "";
            for (Asset a : assets) {
                if (a.status != Asset.Status.UNCHECKED) checked++;
                if (a.status == Asset.Status.UNMATCHED) unmatched++;
                if (a.checkedAt != null && a.checkedAt.compareTo(latest) > 0) latest = a.checkedAt;
            }
            prefs.edit()
                    .putInt("last_total", total)
                    .putInt("last_checked", checked)
                    .putInt("last_unmatched", unmatched)
                    .putString("last_time", latest)
                    .apply();
            showSummary(main, sub, total, checked, unmatched, latest);
        } else {
            int total = prefs.getInt("last_total", 0);
            if (total > 0) {
                showSummary(main, sub, total,
                        prefs.getInt("last_checked", 0),
                        prefs.getInt("last_unmatched", 0),
                        prefs.getString("last_time", ""));
            } else {
                main.setText("尚無資料");
                sub.setText("請進入下方作業並載入 CSV");
            }
        }
    }

    private void showSummary(TextView main, TextView sub,
                             int total, int checked, int unmatched, String latest) {
        main.setText("進度 " + checked + " / " + total);
        String when = (latest == null || latest.isEmpty()) ? "尚未開始" : latest;
        sub.setText("不相符 " + unmatched + "　·　" + when);
    }
}