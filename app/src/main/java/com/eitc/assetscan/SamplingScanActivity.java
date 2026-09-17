package com.eitc.assetscan;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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

import com.eitc.assetscan.camera.QrScanner;
import com.eitc.assetscan.data.AssetRepository;
import com.eitc.assetscan.feedback.ScanFeedback;
import com.eitc.assetscan.logic.AssetIdFormat;
import com.eitc.assetscan.logic.ScannedTag;
import com.eitc.assetscan.model.Asset;
import com.eitc.assetscan.ui.ScanResultCard;
import com.eitc.assetscan.ui.ScannerOverlayView;
import com.eitc.assetscan.util.PermissionGuide;
import com.eitc.assetscan.util.PhotoEvidence;

import java.util.List;

public class SamplingScanActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 100;
    private static final long TOAST_COOLDOWN_MS = 3000;
    // 掃描提示貼著取景框下緣的間距
    private static final float HINT_GAP_DP = 4f;

    // ── Intent extras ────────────────────────────────────
    public static final String EXTRA_TARGET_ID    = "target_id";
    public static final String EXTRA_TARGET_NAME  = "target_name";

    // ── Result extras（回傳給 SamplingActivity）─────────
    public static final String RESULT_RAW = "raw"; // 原始 QR Code 字串

    private PreviewView previewView;
    private ScannerOverlayView scannerOverlay;
    private ScanResultCard resultCard;
    private TextView tvTargetId, tvTargetName, tvTargetMeta, tvHint, lblTorch;
    private ImageView icTorch;
    private View btnTorch, btnCancel, chipTorch;

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

        previewView     = findViewById(R.id.preview_view);
        scannerOverlay  = findViewById(R.id.scanner_overlay);
        resultCard      = findViewById(R.id.scan_result_card);
        tvTargetId      = findViewById(R.id.tv_target_id);
        tvTargetName    = findViewById(R.id.tv_target_name);
        tvTargetMeta    = findViewById(R.id.tv_target_meta);
        tvHint          = findViewById(R.id.tv_hint);
        icTorch         = findViewById(R.id.ic_torch);
        chipTorch       = findViewById(R.id.chip_torch);
        lblTorch        = findViewById(R.id.lbl_torch);
        btnTorch        = findViewById(R.id.btn_torch);
        btnCancel       = findViewById(R.id.btn_cancel);

        // 掃描提示貼著取景框下緣：取景框位置一變（含第一次量測）就跟著重新定位
        float hintGapPx = HINT_GAP_DP * getResources().getDisplayMetrics().density;
        scannerOverlay.setOnFrameChangeListener((RectF frame) -> {
            tvHint.setY(frame.bottom + hintGapPx);
            if (scanner != null) {
                scanner.setDecodeRegion(scannerOverlay.getWidth(), scannerOverlay.getHeight(),
                        frame.left, frame.top, frame.right, frame.bottom);
            }
        });

        // 系統列（瀏海／導覽列）內距：以「絕對值」計算避免多次 dispatch 疊加。底部操作列
        // 以 paddingBottom 讓底色填到螢幕底；浮動的提示與結果卡跟導覽列高度上移，
        // 避免三按鈕導覽時被操作列擠壓／重疊。
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.scan_root),
                (view, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    float d = getResources().getDisplayMetrics().density;

                    setTopMargin(findViewById(R.id.top_bar), bars.top);

                    View bar = findViewById(R.id.bottom_bar);
                    bar.setPadding((int) (8 * d), (int) (12 * d), (int) (8 * d), (int) (12 * d) + bars.bottom);

                    setBottomMargin(findViewById(R.id.scan_result_card), (int) (124 * d) + bars.bottom);

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

        // 目標的部門·地點（供掃描員核對），從記憶體清單取
        Asset target = findTarget();
        if (target != null) {
            tvTargetMeta.setText(safe(target.department) + " · " + safe(target.location));
        }

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
            // ✅ 相符：只比對財產編號，掃到目標編號即直接落檔已盤點（QR 內容不再影響判定）。
            finishing = true;
            if (scannerOverlay != null) scannerOverlay.flashSuccess();
            feedback.success();
            resultCard.showSuccess("✅ 相符", targetId + "　" + safe(targetName));
            returnRawDelayed(raw, 1200L);
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

    /** 從記憶體清單找出目前目標資產（僅供畫面顯示部門・地點參考，不參與相符判定）。 */
    private Asset findTarget() {
        List<Asset> list = AssetRepository.sampling().getAssets();
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

    private static void setTopMargin(View v, int px) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        lp.topMargin = px;
        v.setLayoutParams(lp);
    }

    private static void setBottomMargin(View v, int px) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        lp.bottomMargin = px;
        v.setLayoutParams(lp);
    }

    // ── 手電筒 ───────────────────────────────────────────
    private void toggleTorch() {
        if (!scanner.hasFlashUnit()) {
            Toast.makeText(this, "此裝置不支援手電筒", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean on = scanner.toggleTorch();
        setTorchVisual(on);
    }

    /** 補光開／關的視覺：圖示座橘色高亮、圖示與標籤變色、標籤改「補光開」。 */
    private void setTorchVisual(boolean on) {
        chipTorch.setBackgroundResource(on ? R.drawable.bg_scan_chip_on : R.drawable.bg_scan_chip);
        icTorch.setImageTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, on ? R.color.scan_torch_on : R.color.scan_ico_dim)));
        lblTorch.setTextColor(ContextCompat.getColor(this,
                on ? R.color.scan_torch_on : R.color.scan_label));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanner != null) scanner.close();
        if (feedback != null) feedback.release();
    }
}
