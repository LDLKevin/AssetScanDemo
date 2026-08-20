package com.example.myapplication;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;


import com.example.myapplication.data.AssetRepository;
import com.example.myapplication.data.CsvManager;
import com.example.myapplication.model.Asset;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SamplingActivity extends AppCompatActivity {

    private static final String TAG = "SamplingActivity";

    private List<Asset> assets;
    private int currentIndex = 0; // 目前顯示第幾筆

    // ── DOM ──────────────────────────────────────────────
    private TextView tvProgress, tvPosition, tvResult;
    private TextView tvId, tvName, tvDepartment, tvLocation;
    private Button btnLoad, btnPrev, btnNext, btnScan, btnDone;
    private Button btnJumpPrev, btnJumpNext;

    // 掃描啟動器
    private final ActivityResultLauncher<Intent> scanLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null) {
                            String raw = result.getData()
                                    .getStringExtra(SamplingScanActivity.RESULT_RAW);
                            if (raw != null) {
                                handleScanResult(raw);
                            }
                        }
                    }
            );

    // ── 檔案選擇器 ──────────────────────────────────────
    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) {
                            getContentResolver().takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            );
                            AssetRepository.getInstance().setCsvUri(uri);
                            loadCsv(uri);
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sampling);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.sampling_root),
                (view, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    view.setPadding(0, bars.top, 0, bars.bottom);
                    return WindowInsetsCompat.CONSUMED;
                }
        );

        bindViews();
        setupListeners();
    }


    private void bindViews() {
        tvProgress    = findViewById(R.id.tv_progress);
        tvPosition    = findViewById(R.id.tv_position);
        tvResult      = findViewById(R.id.tv_result);
        tvId          = findViewById(R.id.tv_id);
        tvName        = findViewById(R.id.tv_name);
        tvDepartment  = findViewById(R.id.tv_department);
        tvLocation    = findViewById(R.id.tv_location);
        btnLoad       = findViewById(R.id.btn_load);
        btnPrev       = findViewById(R.id.btn_prev);
        btnNext       = findViewById(R.id.btn_next);
        btnScan       = findViewById(R.id.btn_scan);
        btnDone       = findViewById(R.id.btn_done);
        btnJumpPrev   = findViewById(R.id.btn_jump_prev);
        btnJumpNext   = findViewById(R.id.btn_jump_next);
    }

    private void setupListeners() {
        btnLoad.setOnClickListener(v ->
                filePicker.launch(new String[]{ "*/*" })
        );

        btnPrev.setOnClickListener(v -> showAssetAt(currentIndex - 1));
        btnNext.setOnClickListener(v -> showAssetAt(currentIndex + 1));

        btnJumpPrev.setOnClickListener(v -> jumpToUnchecked(false));
        btnJumpNext.setOnClickListener(v -> jumpToUnchecked(true));

        btnScan.setOnClickListener(v -> {
            if (assets == null || assets.isEmpty()) return;
            Asset target = assets.get(currentIndex);

            Intent intent = new Intent(this, SamplingScanActivity.class);
            intent.putExtra(SamplingScanActivity.EXTRA_TARGET_ID, target.id);
            intent.putExtra(SamplingScanActivity.EXTRA_TARGET_NAME, target.name);
            scanLauncher.launch(intent);
        });

        btnDone.setOnClickListener(v -> finish());
    }

    // ── 載入 CSV ─────────────────────────────────────────
    private void loadCsv(Uri uri) {
        new Thread(() -> {
            try {
                List<Asset> result = CsvManager.read(getContentResolver(), uri);
                runOnUiThread(() -> {
                    assets = result;
                    AssetRepository.getInstance().setAssets(assets);

                    if (assets.isEmpty()) {
                        Toast.makeText(this, "CSV 是空的", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    currentIndex = 0;
                    showAssetAt(0);
                    enableActionButtons(true);
                    Toast.makeText(this,
                            "載入成功：" + assets.size() + " 筆",
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this,
                                "讀取失敗：" + e.getMessage(),
                                Toast.LENGTH_LONG).show()
                );
                Log.e(TAG, "讀取失敗", e);
            }
        }).start();
    }

    // ── 顯示指定位置的資產 ───────────────────────────────
    private void showAssetAt(int index) {
        if (assets == null || assets.isEmpty()) return;
        if (index < 0 || index >= assets.size()) return;

        currentIndex = index;
        Asset a = assets.get(index);

        tvId.setText(a.id);
        tvName.setText(a.name);
        tvDepartment.setText(a.department.isEmpty() ? "－" : a.department);
        tvLocation.setText(a.location.isEmpty() ? "－" : a.location);

        tvPosition.setText("第 " + (index + 1) + " 筆 / 共 " + assets.size() + " 筆");

        // 比對結果顯示
        switch (a.status) {
            case MATCHED:
                tvResult.setText("✅ 相符");
                tvResult.setTextColor(Color.parseColor("#16a34a"));
                tvResult.setBackgroundColor(Color.parseColor("#f0fdf4"));
                break;
            case UNMATCHED:
                tvResult.setText("⚠️ 不相符");
                tvResult.setTextColor(Color.parseColor("#c2410c"));
                tvResult.setBackgroundColor(Color.parseColor("#fff7ed"));
                break;
            case UNCHECKED:
            default:
                tvResult.setText("待掃描");
                tvResult.setTextColor(Color.parseColor("#8e8e93"));
                tvResult.setBackgroundColor(Color.parseColor("#f2f2f7"));
                break;
        }

        updateNavButtons();
        updateProgress();
    }

    // ── 跳到上/下一筆未盤點 ──────────────────────────────
    private void jumpToUnchecked(boolean forward) {
        if (assets == null) return;
        int total = assets.size();
        int step  = forward ? 1 : -1;

        for (int i = 1; i <= total; i++) {
            int idx = currentIndex + step * i;
            if (idx < 0 || idx >= total) break; // 不繞圈，到頂/到底就停

            if (assets.get(idx).status == Asset.Status.UNCHECKED) {
                showAssetAt(idx);
                return;
            }
        }

        Toast.makeText(this,
                forward ? "無下筆未盤點的資產" : "無上筆未盤點的資產",
                Toast.LENGTH_SHORT).show();
    }

    // ── 更新導覽按鈕狀態 ─────────────────────────────────
    private void updateNavButtons() {
        if (assets == null) return;
        btnPrev.setEnabled(currentIndex > 0);
        btnNext.setEnabled(currentIndex < assets.size() - 1);

        // 跳轉按鈕：只要還有未盤點的就可用
        boolean hasUnchecked = assets.stream()
                .anyMatch(a -> a.status == Asset.Status.UNCHECKED);
        btnJumpPrev.setEnabled(hasUnchecked);
        btnJumpNext.setEnabled(hasUnchecked);
    }

    // ── 更新進度顯示 ─────────────────────────────────────
    private void updateProgress() {
        if (assets == null) return;
        long checked = assets.stream()
                .filter(a -> a.status != Asset.Status.UNCHECKED)
                .count();
        tvProgress.setText("進度：" + checked + " / " + assets.size() + " 已盤點");
    }

    // ── 啟用底部操作按鈕 ─────────────────────────────────
    private void enableActionButtons(boolean enabled) {
        btnScan.setEnabled(enabled);
    }

    // ── 處理掃描結果 ─────────────────────────────────────
    private void handleScanResult(String raw) {
        if (assets == null || assets.isEmpty()) return;

        Asset target = assets.get(currentIndex);

        // 拆解 QR Code
        String[] parts = raw.split(";");
        String scannedId         = parts.length > 0 ? parts[0].trim() : "";
        String scannedDepartment = parts.length > 2 ? parts[2].trim() : "";
        String scannedLocation   = parts.length > 3 ? parts[3].trim() : "";

        // 雙重保險：理論上 SamplingScanActivity 已經過濾掉非目標的資產
        if (!scannedId.equals(target.id)) {
            Toast.makeText(this, "掃描結果與目標不符", Toast.LENGTH_SHORT).show();
            return;
        }

        // 比對部門和地點
        boolean isMatched = target.department.equals(scannedDepartment)
                && target.location.equals(scannedLocation);

        target.status    = isMatched ? Asset.Status.MATCHED : Asset.Status.UNMATCHED;
        target.checkedAt = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
        ).format(new Date());

        saveCsv();

        // 重新顯示這一筆，更新 UI
        showAssetAt(currentIndex);

        Toast.makeText(this,
                isMatched ? "✅ 盤點成功（相符）" : "⚠️ 盤點完成（不相符）",
                Toast.LENGTH_SHORT).show();
    }

    // ── 寫回 CSV ─────────────────────────────────────────
    private void saveCsv() {
        new Thread(() -> {
            try {
                CsvManager.write(
                        getContentResolver(),
                        AssetRepository.getInstance().getCsvUri(),
                        assets
                );
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this,
                                "CSV 寫入失敗：" + e.getMessage(),
                                Toast.LENGTH_LONG).show()
                );
                Log.e(TAG, "CSV 寫入失敗", e);
            }
        }).start();
    }
}