package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
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


import com.example.myapplication.data.CsvManager;
import com.example.myapplication.model.Asset;
import com.example.myapplication.data.AssetRepository;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScanActivity extends AppCompatActivity {

    private static final String TAG = "ScanActivity";
    private static final int REQ_CAMERA = 100;
    private static final long COOLDOWN_MS = 5000;

    private PreviewView previewView;
    private TextView tvWarning;
    private EditText etId, etName, etDepartment, etLocation;
    private Button btnPrev, btnNext, btnWrite, btnDone, btnTorch;

    private List<Asset> assets;          // 來自 Repository
    private List<Asset> history;         // 本次盤點過的財產（依時間順序）
    private int historyIndex = -1;       // 目前顯示的是 history 第幾筆

    private boolean isNewAsset = false;  // 當前顯示的是否為新增財產
    private boolean isEdited   = false;  // 使用者是否編輯過部門或地點
    private long lastScanTime  = 0;
    private String lastScannedRaw = "";

    private ExecutorService cameraExecutor;
    private MultiFormatReader zxingReader;
    private androidx.camera.core.Camera camera; // 用來控制手電筒
    private boolean torchOn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        // 處理瀏海
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.scan_root),
                (view, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    view.setPadding(0, bars.top, 0, bars.bottom);
                    return WindowInsetsCompat.CONSUMED;
                }
        );

        previewView  = findViewById(R.id.preview_view);
        tvWarning    = findViewById(R.id.tv_warning);
        etId         = findViewById(R.id.et_id);
        etName       = findViewById(R.id.et_name);
        etDepartment = findViewById(R.id.et_department);
        etLocation   = findViewById(R.id.et_location);
        btnPrev      = findViewById(R.id.btn_prev);
        btnNext      = findViewById(R.id.btn_next);
        btnWrite     = findViewById(R.id.btn_write);
        btnDone      = findViewById(R.id.btn_done);

        assets  = AssetRepository.getInstance().getAssets();
        history = new ArrayList<>();

        cameraExecutor = Executors.newSingleThreadExecutor();
        zxingReader    = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new HashMap<>();
        hints.put(DecodeHintType.TRY_HARDER, true);
        zxingReader.setHints(hints);

        // 編輯監聽：使用者改部門或地點時，把「寫入」按鈕從灰變成「更新」
        /*
        TextWatcher editWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (etId.getText().length() == 0) return; // 還沒掃到資料
                if (!isNewAsset) {
                    isEdited = true;
                    btnWrite.setText("更新");
                    btnWrite.setEnabled(true);
                    btnWrite.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9500")));
                }
            }
        };
        etDepartment.addTextChangedListener(editWatcher);
        etLocation.addTextChangedListener(editWatcher);
        */

        btnPrev.setOnClickListener(v -> showHistory(historyIndex - 1));
        btnNext.setOnClickListener(v -> showHistory(historyIndex + 1));
        btnWrite.setOnClickListener(v -> onWriteClicked());
        btnDone.setOnClickListener(v -> finish());

        clearForm();
        updateNavButtons();

        // 請求相機權限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.CAMERA }, REQ_CAMERA);
        }

        TextView btnTorch = findViewById(R.id.btn_torch);
        btnTorch.setOnClickListener(v -> {
            if (camera == null) return;
            if (!camera.getCameraInfo().hasFlashUnit()) {
                Toast.makeText(this, "此裝置不支援手電筒", Toast.LENGTH_SHORT).show();
                return;
            }
            torchOn = !torchOn;
            camera.getCameraControl().enableTorch(torchOn);
            btnTorch.setText(torchOn ? "💡" : "🔦");
        });
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

                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;

                provider.unbindAll();
                // 接住回傳的 Camera 物件
                camera = provider.bindToLifecycle(this, selector, preview, analysis);

            } catch (Exception e) {
                Log.e(TAG, "啟動相機失敗", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(ImageProxy imageProxy) {
        // 冷卻中
        if (System.currentTimeMillis() - lastScanTime < COOLDOWN_MS) {
            imageProxy.close();
            return;
        }

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

    private void handleScanResult(String raw) {
        if (raw.equals(lastScannedRaw) && System.currentTimeMillis() - lastScanTime < COOLDOWN_MS * 2) {
            return;
        }
        lastScannedRaw = raw;
        lastScanTime   = System.currentTimeMillis();

        String[] parts = raw.split(";");
        String targetId   = parts.length > 0 ? parts[0].trim() : "";
        String name       = parts.length > 1 ? parts[1].trim() : "";
        String department = parts.length > 2 ? parts[2].trim() : "";
        String location   = parts.length > 3 ? parts[3].trim() : "";

        if (targetId.isEmpty()) return;

        Asset matched = null;
        for (Asset a : assets) {
            if (a.id.equals(targetId)) { matched = a; break; }
        }

        if (matched != null) {
            boolean isMatched = matched.department.equals(department)
                    && matched.location.equals(location);

            matched.status    = isMatched ? Asset.Status.MATCHED : Asset.Status.UNMATCHED;
            matched.checkedAt = currentTime();
            saveCsv();

            history.add(matched);
            historyIndex = history.size() - 1;
            displayAsset(matched, false);

            if (isMatched) {
                Toast.makeText(this, "✅ " + targetId + " 盤點成功", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "⚠️ " + targetId + " 部門或地點不相符", Toast.LENGTH_LONG).show();
            }
        } else {
            Asset newAsset = new Asset(targetId, name, department, location,
                    Asset.Status.UNCHECKED, "");
            displayAsset(newAsset, true);
            Toast.makeText(this, "⚠️ 未列入清單", Toast.LENGTH_SHORT).show();
        }

        updateNavButtons();
    }


    // 顯示財產到表格
    private void displayAsset(Asset asset, boolean isNew) {
        isNewAsset = isNew;
        isEdited   = false;

        etId.setText(asset.id);
        etName.setText(asset.name);
        etDepartment.setText(asset.department);
        etLocation.setText(asset.location);

        if (isNew) {
            tvWarning.setVisibility(View.VISIBLE);
            btnWrite.setText("寫入");
            btnWrite.setEnabled(true);
            btnWrite.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#34c759")));
        } else {
            tvWarning.setVisibility(View.GONE);
            btnWrite.setText("寫入");
            btnWrite.setEnabled(false);
            btnWrite.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#cccccc")));
        }
    }

    private void clearForm() {
        etId.setText("");
        etName.setText("");
        etDepartment.setText("");
        etLocation.setText("");
        tvWarning.setVisibility(View.GONE);
        btnWrite.setEnabled(false);
        btnWrite.setText("寫入");
        btnWrite.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#cccccc")));
    }

    // 上一筆 / 下一筆
    private void showHistory(int newIndex) {
        if (newIndex < 0 || newIndex >= history.size()) return;
        historyIndex = newIndex;
        displayAsset(history.get(newIndex), false);
        updateNavButtons();
    }

    private void updateNavButtons() {
        btnPrev.setEnabled(historyIndex > 0);
        btnNext.setEnabled(historyIndex >= 0 && historyIndex < history.size() - 1);
    }

    // 寫入按鈕
    private void onWriteClicked() {
        String id         = etId.getText().toString().trim();
        String name       = etName.getText().toString().trim();
        String department = etDepartment.getText().toString().trim();
        String location   = etLocation.getText().toString().trim();

        if (isNewAsset) {
            /*
            if (department.isEmpty() || location.isEmpty()) {
                Toast.makeText(this, "請填入部門及地點", Toast.LENGTH_SHORT).show();
                return;
            }
            */
            // 新增財產：直接視為已盤點且相符
            Asset newAsset = new Asset(id, name, department, location,
                    Asset.Status.MATCHED, currentTime());
            assets.add(newAsset);
            history.add(newAsset);
            historyIndex = history.size() - 1;
            saveCsv();
            Toast.makeText(this, "✅ 已新增：" + id, Toast.LENGTH_SHORT).show();

            isNewAsset = false;
            displayAsset(newAsset, false);
        } else if (isEdited) {
            // 更新既有財產
            Asset target = null;
            for (Asset a : assets) {
                if (a.id.equals(id)) { target = a; break; }
            }
            if (target != null) {
                target.department = department;
                target.location   = location;
                saveCsv();
                Toast.makeText(this, "✅ 已更新：" + id, Toast.LENGTH_SHORT).show();
                isEdited = false;
                btnWrite.setText("寫入");
                btnWrite.setEnabled(false);
                btnWrite.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#cccccc")));
            }
        }

        updateNavButtons();
    }

    // 寫回 CSV
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

    private String currentTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}