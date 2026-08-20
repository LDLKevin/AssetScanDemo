package com.example.myapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SamplingScanActivity extends AppCompatActivity {

    private static final String TAG = "SamplingScanActivity";
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

    private Camera camera;
    private boolean torchOn = false;
    private ExecutorService cameraExecutor;
    private MultiFormatReader zxingReader;

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

        cameraExecutor = Executors.newSingleThreadExecutor();
        zxingReader    = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new HashMap<>();
        hints.put(DecodeHintType.TRY_HARDER, true);
        zxingReader.setHints(hints);

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

    // ── 啟動相機 ─────────────────────────────────────────
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                provider.unbindAll();
                camera = provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                );

            } catch (Exception e) {
                Log.e(TAG, "啟動相機失敗", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ── 解碼 ─────────────────────────────────────────────
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(ImageProxy imageProxy) {
        try {
            ByteBuffer buffer = imageProxy.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            int width  = imageProxy.getWidth();
            int height = imageProxy.getHeight();

            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    bytes, width, height, 0, 0, width, height, false
            );
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

            try {
                Result result = zxingReader.decodeWithState(bitmap);
                String raw = result.getText();
                runOnUiThread(() -> handleScanResult(raw));
            } catch (Exception decodeError) {
                // 沒掃到，正常情況
            } finally {
                zxingReader.reset();
            }
        } finally {
            imageProxy.close();
        }
    }

    // ── 掃到結果 ────────────────────────────────────────
    private void handleScanResult(String raw) {
        String scannedId = raw.split(";")[0].trim();

        if (scannedId.equals(targetId)) {
            // ✅ 正確的資產，回傳結果
            Intent intent = new Intent();
            intent.putExtra(RESULT_RAW, raw);
            setResult(RESULT_OK, intent);
            if (navigator_vibrate()) { /* 觸發震動已包在方法內 */ }
            finish();
        } else {
            // ❌ 不是當前要找的資產
            long now = System.currentTimeMillis();
            if (now - lastWrongScanToast > TOAST_COOLDOWN_MS) {
                lastWrongScanToast = now;
                Toast.makeText(this,
                        "⚠️ 這不是當前要找的資產（" + scannedId + "）",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean navigator_vibrate() {
        // 震動 + 簡單視覺回饋
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) v.vibrate(60);
        return true;
    }

    // ── 手電筒 ───────────────────────────────────────────
    private void toggleTorch() {
        if (camera == null) return;
        if (!camera.getCameraInfo().hasFlashUnit()) {
            Toast.makeText(this, "此裝置不支援手電筒", Toast.LENGTH_SHORT).show();
            return;
        }
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        btnTorch.setText(torchOn ? "💡" : "🔦");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}