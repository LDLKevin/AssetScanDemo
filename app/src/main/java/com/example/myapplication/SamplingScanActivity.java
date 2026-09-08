package com.example.myapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
import com.example.myapplication.feedback.ScanFeedback;
import com.example.myapplication.logic.AssetIdFormat;
import com.example.myapplication.logic.ScanClassifier;
import com.example.myapplication.logic.ScannedTag;
import com.example.myapplication.model.Asset;
import com.example.myapplication.ui.ScanResultCard;
import com.example.myapplication.ui.ScannerOverlayView;
import com.example.myapplication.util.PermissionGuide;
import com.example.myapplication.util.PhotoEvidence;

import java.util.List;

public class SamplingScanActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 100;
    private static final long TOAST_COOLDOWN_MS = 1500;

    // ── Intent extras ────────────────────────────────────
    public static final String EXTRA_TARGET_ID    = "target_id";
    public static final String EXTRA_TARGET_NAME  = "target_name";

    // ── Result extras（回傳給 SamplingActivity）─────────
    public static final String RESULT_RAW = "raw"; // 原始 QR Code 字串

    private PreviewView previewView;
    private ScannerOverlayView scannerOverlay;
    private ScanResultCard resultCard;
    private TextView tvTargetId, tvTargetName, tvHint, btnTorch;
    private Button btnCancel;

    private String targetId;
    private String targetName;
    private long lastWrongScanToast = 0;
    private boolean finishing = false;   // 命中後延遲返回期間，忽略後續掃描

    private QrScanner scanner; // 相機 + 解碼管線（deep module）
    private ScanFeedback feedback; // 聲音＋震動回饋
    private View permissionDenied; // 權限被拒引導畫面
    private boolean cameraStarted = false;

    // 拍照存證（QR 破損）
    private Uri pendingPhotoUri;
    private String pendingPhotoName;
    private final ActivityResultLauncher<Uri> takePhoto =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (Boolean.TRUE.equals(success)) {
                    String n = pendingPhotoName != null ? pendingPhotoName : "照片";
                    Toast.makeText(this, "已存證：" + n, Toast.LENGTH_SHORT).show();
                } else {
                    // 取消或失敗（TakePicture 無法區分）：刪掉剛建立的空檔
                    if (pendingPhotoUri != null) {
                        try {
                            DocumentsContract.deleteDocument(getContentResolver(), pendingPhotoUri);
                        } catch (Exception ignored) { }
                    }
                    Toast.makeText(this, "照片未儲存", Toast.LENGTH_SHORT).show();
                }
                pendingPhotoUri = null;
                pendingPhotoName = null;
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sampling_scan);

        previewView    = findViewById(R.id.preview_view);
        scannerOverlay = findViewById(R.id.scanner_overlay);
        resultCard     = findViewById(R.id.scan_result_card);
        tvTargetId     = findViewById(R.id.tv_target_id);
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
        targetName = getIntent().getStringExtra(EXTRA_TARGET_NAME);

        if (targetId == null || targetId.isEmpty()) {
            Toast.makeText(this, "缺少目標資產", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        tvTargetId.setText(targetId);
        tvTargetName.setText(targetName != null ? targetName : "");

        scanner = new QrScanner();
        feedback = new ScanFeedback(this);

        permissionDenied = findViewById(R.id.permission_denied);
        permissionDenied.findViewById(R.id.btn_open_settings)
                .setOnClickListener(v -> PermissionGuide.openAppSettings(this));

        findViewById(R.id.btn_capture).setOnClickListener(v -> capturePhoto());

        btnCancel.setOnClickListener(v -> {
            if (finishing) return;   // 命中延遲返回期間，別讓取消覆蓋 RESULT_OK
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
            // 不直接關頁：顯示引導畫面，提供「前往設定」
            permissionDenied.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 從系統設定授權返回：若已取得權限則收起引導、啟動相機
        if (!cameraStarted
                && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            permissionDenied.setVisibility(View.GONE);
            startCamera();
        }
    }

    private void startCamera() {
        if (cameraStarted) return;
        cameraStarted = true;
        scanner.bind(this, previewView, this::handleScanResult);
    }

    // QR 破損：拍整張標籤存到 CSV 同資料夾（檔名帶目前尋找的目標編號）
    private void capturePhoto() {
        String name = PhotoEvidence.buildFileName(targetId);
        Uri uri = PhotoEvidence.createInCsvFolder(this, name);
        if (uri == null) {
            Toast.makeText(this, "請先載入 CSV（需資料夾權限）", Toast.LENGTH_LONG).show();
            return;
        }
        pendingPhotoUri = uri;
        pendingPhotoName = name;
        takePhoto.launch(uri);
    }

    // ── 掃到結果 ────────────────────────────────────────
    private void handleScanResult(String raw) {
        if (finishing) return;
        ScannedTag tag = ScannedTag.parse(raw);
        String scannedId = tag.id;

        if (scannedId.equals(targetId)) {
            Asset target = findTarget();
            boolean matched = target != null && ScanClassifier.matches(target, tag);

            if (matched) {
                // ✅ 相符：綠卡自動消散，短暫停留後回傳（由抽盤主畫面落檔）
                finishing = true;
                if (scannerOverlay != null) scannerOverlay.flashSuccess();
                feedback.success();
                resultCard.showSuccess("✅ 相符", targetId + "　" + safe(targetName));
                returnRawDelayed(raw, 1200L);
            } else {
                // ⚠️ 目標不符（部門／地點）：紅卡列差異對比，確認才回傳落檔
                finishing = true;
                if (scannerOverlay != null) scannerOverlay.flashError();
                feedback.unmatched();
                if (target != null) {
                    resultCard.showUnmatched("⚠️ 部門或地點不相符", tag, target,
                            () -> returnRaw(raw),          // 確認寫入不相符 → 回傳落檔
                            () -> finishing = false);      // 略過 → 恢復掃描
                } else {
                    // 找不到目標資產（理論上不會發生）：直接回傳，交由主畫面處理
                    returnRaw(raw);
                }
            }
        } else if (AssetIdFormat.isValid(scannedId)) {
            // 掃到別的（成格式的）資產：橘卡提示、不寫入、繼續掃；不成格式的雜訊靜默忽略
            long now = System.currentTimeMillis();
            if (now - lastWrongScanToast > TOAST_COOLDOWN_MS) {
                lastWrongScanToast = now;
                if (scannerOverlay != null) scannerOverlay.flashError();
                resultCard.showWarning("請掃描指定財產", "目前尋找：" + targetId);
            }
        }
    }

    /** 從記憶體清單找出目前目標資產（取其部門／地點以判定相符）。 */
    private Asset findTarget() {
        List<Asset> list = AssetRepository.getInstance().getAssets();
        if (list != null) {
            for (Asset a : list) {
                if (a != null && targetId.equals(a.id)) return a;
            }
        }
        return null;
    }

    private void returnRaw(String raw) {
        Intent intent = new Intent();
        intent.putExtra(RESULT_RAW, raw);
        setResult(RESULT_OK, intent);
        finish();
    }

    private void returnRawDelayed(String raw, long delayMs) {
        Intent intent = new Intent();
        intent.putExtra(RESULT_RAW, raw);
        setResult(RESULT_OK, intent);
        previewView.postDelayed(this::finish, delayMs);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
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
        if (feedback != null) feedback.release();
    }
}
