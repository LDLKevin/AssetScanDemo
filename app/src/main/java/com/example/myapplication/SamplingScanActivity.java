package com.example.myapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.myapplication.camera.QrScanner;
import com.example.myapplication.logic.AssetIdFormat;
import com.example.myapplication.logic.ScannedTag;

public class SamplingScanActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 100;
    private static final long TOAST_COOLDOWN_MS = 1500;

    // ── Intent extras ────────────────────────────────────
    public static final String EXTRA_TARGET_ID    = "target_id";
    public static final String EXTRA_TARGET_NAME  = "target_name";

    // ── Result extras（回傳給 SamplingActivity）─────────
    public static final String RESULT_RAW = "raw"; // 原始 QR Code 字串

    private PreviewView previewView;
    private TextView tvTargetId, tvTargetName, tvHint, btnTorch;
    private Button btnCancel;

    private String targetId;
    private long lastWrongScanToast = 0;

    private QrScanner scanner; // 相機 + 解碼管線（deep module）

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sampling_scan);

        previewView  = findViewById(R.id.preview_view);
        tvTargetId   = findViewById(R.id.tv_target_id);
        tvTargetName = findViewById(R.id.tv_target_name);
        tvHint       = findViewById(R.id.tv_hint);
        btnTorch     = findViewById(R.id.btn_torch);
        btnCancel    = findViewById(R.id.btn_cancel);

        // 處理瀏海：頂部資訊條和底部取消按鈕往內推
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.scan_root),
                (view, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

                    // 頂部資訊條加上瀏海高度
                    View topBar = findViewById(R.id.top_bar);
                    ViewGroup.MarginLayoutParams topParams =
                            (ViewGroup.MarginLayoutParams) topBar.getLayoutParams();
                    topParams.topMargin = bars.top;
                    topBar.setLayoutParams(topParams);

                    // 底部取消按鈕加上導覽鍵高度
                    View btnCancel = findViewById(R.id.btn_cancel);
                    ViewGroup.MarginLayoutParams cancelParams =
                            (ViewGroup.MarginLayoutParams) btnCancel.getLayoutParams();
                    cancelParams.bottomMargin = bars.bottom + 24 * (int) getResources().getDisplayMetrics().density;
                    btnCancel.setLayoutParams(cancelParams);

                    // 手電筒按鈕也跟著頂部偏移
                    View btnTorch = findViewById(R.id.btn_torch);
                    ViewGroup.MarginLayoutParams torchParams =
                            (ViewGroup.MarginLayoutParams) btnTorch.getLayoutParams();
                    torchParams.topMargin = bars.top + 50 * (int) getResources().getDisplayMetrics().density;
                    btnTorch.setLayoutParams(torchParams);

                    return WindowInsetsCompat.CONSUMED;
                }
        );

        // 從 Intent 取得目標
        targetId = getIntent().getStringExtra(EXTRA_TARGET_ID);
        String targetName = getIntent().getStringExtra(EXTRA_TARGET_NAME);

        if (targetId == null || targetId.isEmpty()) {
            Toast.makeText(this, "缺少目標資產", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        tvTargetId.setText(targetId);
        tvTargetName.setText(targetName != null ? targetName : "");

        scanner = new QrScanner();

        btnCancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        btnTorch.setOnClickListener(v -> toggleTorch());

        // 相機權限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.CAMERA }, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "需要相機權限才能掃描", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void startCamera() {
        scanner.bind(this, previewView, this::handleScanResult);
    }

    // ── 掃到結果 ────────────────────────────────────────
    private void handleScanResult(String raw) {
        String scannedId = ScannedTag.parse(raw).id;

        if (scannedId.equals(targetId)) {
            // ✅ 正確的資產，回傳結果
            Intent intent = new Intent();
            intent.putExtra(RESULT_RAW, raw);
            setResult(RESULT_OK, intent);
            vibrate();
            finish();
        } else if (AssetIdFormat.isValid(scannedId)) {
            // ❌ 是別的（成格式的）資產，才提示；不成格式的雜訊視為誤觸，靜默忽略
            long now = System.currentTimeMillis();
            if (now - lastWrongScanToast > TOAST_COOLDOWN_MS) {
                lastWrongScanToast = now;
                Toast.makeText(this,
                        "⚠️ 這不是當前要找的資產（" + scannedId + "）",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void vibrate() {
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) v.vibrate(60);
    }

    // ── 手電筒 ───────────────────────────────────────────
    private void toggleTorch() {
        if (!scanner.hasFlashUnit()) {
            Toast.makeText(this, "此裝置不支援手電筒", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean on = scanner.toggleTorch();
        btnTorch.setText(on ? "💡" : "🔦");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanner != null) scanner.close();
    }
}
