package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
import com.example.myapplication.data.AssetRepository;
import com.example.myapplication.logic.AssetIdFormat;
import com.example.myapplication.logic.ScanClassifier;
import com.example.myapplication.logic.ScannedTag;
import com.example.myapplication.model.Asset;
import com.example.myapplication.ui.ScanResultCard;
import com.example.myapplication.ui.ScannerOverlayView;

import java.util.ArrayList;
import java.util.List;

public class ScanActivity extends AppCompatActivity {

    private static final String TAG = "ScanActivity";
    private static final int REQ_CAMERA = 100;
    // 結果停留畫面已由 history/displayAsset 承載，冷卻只需防手震連拍，故縮短。
    private static final long COOLDOWN_MS = 900;

    private PreviewView previewView;
    private ScannerOverlayView scannerOverlay;
    private ScanResultCard resultCard;
    private TextView tvWarning;
    private EditText etId, etName, etDepartment, etLocation;
    private Button btnPrev, btnNext, btnWrite, btnDone;

    private List<Asset> assets;          // 來自 Repository
    private List<Asset> history;         // 本次盤點過的財產（依時間順序）
    private int historyIndex = -1;       // 目前顯示的是 history 第幾筆

    private boolean isNewAsset = false;  // 當前顯示的是否為新增財產
    private boolean isEdited   = false;  // 使用者是否編輯過部門或地點
    private long lastScanTime  = 0;
    private String lastScannedRaw = "";

    private QrScanner scanner;           // 相機 + 解碼管線（deep module）

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

        previewView    = findViewById(R.id.preview_view);
        scannerOverlay = findViewById(R.id.scanner_overlay);
        resultCard     = findViewById(R.id.scan_result_card);
        tvWarning      = findViewById(R.id.tv_warning);
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

        scanner = new QrScanner();

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
            if (!scanner.hasFlashUnit()) {
                Toast.makeText(this, "此裝置不支援手電筒", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean on = scanner.toggleTorch();
            btnTorch.setText(on ? "💡" : "🔦");
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
        scanner.bind(this, previewView, this::handleScanResult);
    }

    private void handleScanResult(String raw) {
        long now = System.currentTimeMillis();

        // 防手震連拍：距上次有效掃描太近 → 忽略（取代原本相機層級的幀冷卻）。
        if (now - lastScanTime < COOLDOWN_MS) {
            return;
        }
        // 同一張 QR 短時間內去重（避免停在鏡頭前被連續記錄）。
        if (raw.equals(lastScannedRaw) && now - lastScanTime < COOLDOWN_MS * 2) {
            return;
        }

        ScanClassifier.Result r =
                ScanClassifier.classify(raw, assets, AssetIdFormat::isValid);

        // 誤觸：不成格式 → 靜默忽略，不動冷卻、不刷新畫面
        if (r.outcome == ScanClassifier.Outcome.IGNORED_INVALID) {
            return;
        }

        // 到這裡才算一次有效掃描，才起算冷卻
        lastScannedRaw = raw;
        lastScanTime   = now;

        switch (r.outcome) {
            case MATCHED:
            case UNMATCHED: {
                Asset asset = r.asset;

                // 重複掃描：已盤點過的財產再掃 → 橘卡提示，不覆蓋原記錄
                if (asset.status != Asset.Status.UNCHECKED) {
                    displayAsset(asset, false);
                    resultCard.showDuplicate("此財產已完成盤點", asset.id + "　" + asset.name);
                    break;
                }

                if (r.outcome == ScanClassifier.Outcome.MATCHED) {
                    // 相符：即時落檔，綠卡自動消散
                    AssetRepository.getInstance().recordCheck(asset, Asset.Status.MATCHED);
                    history.add(asset);
                    historyIndex = history.size() - 1;
                    displayAsset(asset, false);
                    if (scannerOverlay != null) scannerOverlay.flashSuccess();
                    resultCard.showSuccess("✅ 盤點成功", asset.id + "　" + asset.name);
                } else {
                    // 不相符：先不落檔，紅卡列出差異對比，按「確認寫入不相符」才寫
                    if (scannerOverlay != null) scannerOverlay.flashError();
                    ScannedTag scanned = ScannedTag.parse(raw);
                    resultCard.showUnmatched("⚠️ 部門或地點不相符", scanned, asset, () -> {
                        AssetRepository.getInstance().recordCheck(asset, Asset.Status.UNMATCHED);
                        history.add(asset);
                        historyIndex = history.size() - 1;
                        displayAsset(asset, false);
                        updateNavButtons();
                    });
                }
                break;
            }
            case SURPLUS: {
                // 盤盈（未列入清單）：沿用既有表單流程（顯示警告並啟用「寫入」）
                Asset newAsset = new Asset(r.id, r.name, r.department, r.location,
                        Asset.Status.UNCHECKED, "");
                displayAsset(newAsset, true);
                break;
            }
            default:
                break;
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
            // 新增財產：直接視為已盤點且相符（狀態、時間、加入清單、dirty 皆由 repository 處理）
            Asset newAsset = new Asset(id, name, department, location,
                    Asset.Status.UNCHECKED, "");
            AssetRepository.getInstance().addAsMatched(newAsset);
            history.add(newAsset);
            historyIndex = history.size() - 1;
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
                AssetRepository.getInstance().recordEdit(target, department, location);
                Toast.makeText(this, "✅ 已更新：" + id, Toast.LENGTH_SHORT).show();
                isEdited = false;
                btnWrite.setText("寫入");
                btnWrite.setEnabled(false);
                btnWrite.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#cccccc")));
            }
        }

        updateNavButtons();
    }

    // 定點寫檔：只有記憶體有未落檔變更時才真正寫一次（背景執行緒）。
    private void flushCsvAsync() {
        new Thread(() -> {
            try {
                AssetRepository.getInstance().flush(getContentResolver());
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

    @Override
    protected void onPause() {
        super.onPause();
        flushCsvAsync();
    }

    @Override
    protected void onStop() {
        super.onStop();
        flushCsvAsync();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanner != null) scanner.close();
    }
}
