package com.eitc.assetscan;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class ModeSelectActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mode_select);

        // 處理瀏海／系統列：設「絕對」內距（基底 32dp＋系統列），避免多次 dispatch 疊加
        // 造成三按鈕導覽列上內距愈墊愈高、版面被擠壓。
        final int base = Math.round(32 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.mode_root),
                (view, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    view.setPadding(base, base + bars.top, base, base + bars.bottom);
                    return WindowInsetsCompat.CONSUMED;
                }
        );

        // 全盤
        findViewById(R.id.btn_full).setOnClickListener(v ->
                startActivity(new Intent(this, FullActivity.class))
        );

        // 抽盤
        findViewById(R.id.btn_sampling).setOnClickListener(v ->
                startActivity(new Intent(this, SamplingActivity.class))
        );
    }
}
