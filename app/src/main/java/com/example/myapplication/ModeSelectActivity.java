package com.example.myapplication;

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

        // 抽盤（暫時留空，下一步實作）
        findViewById(R.id.btn_sampling).setOnClickListener(v ->
                startActivity(new Intent(this, SamplingActivity.class))
        );
    }
}