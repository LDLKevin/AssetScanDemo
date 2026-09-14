package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
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

/**
 * 全盤掃描頁（方案 A：相機全螢幕、連續掃描、最少觸碰）。
 *
 * <p>掃描結果一律以底部結果卡回饋：相符綠卡自動消散、重複橘卡自動消散、
 * 不相符紅卡需確認、盤盈（清單外編號）確認卡需確認新增。底部操作列集中
 * 補光／拍照存證／返回。舊的可編輯表單與歷史導覽已移除。
 */
public class ScanActivity extends AppCompatActivity {

    private static final String TAG = "ScanActivity";
    private static final int REQ_CAMERA = 100;
    // 結果停留由結果卡承載，冷卻只需防手震連拍，故較短。
    private static final long COOLDOWN_MS = 900;

    private PreviewView previewView;
    private ScannerOverlayView scannerOverlay;
    private ScanResultCard resultCard;
    private TextView tvProgressChip;
    private TextView tvHint;
    private TextView lblTorch;
    private ImageView icTorch;
    private View btnTorch, btnCancel, awaitingOverlay, chipTorch;

    private List<Asset> assets;          // 來自 Repository

    private long lastScanTime  = 0;
    private String lastScannedRaw = "";
    private boolean awaitingConfirm = false;  // 不相符／盤盈卡等待確認／略過期間，暫停處理後續掃描

    private QrScanner scanner;           // 相機 + 解碼管線（deep module）
    private ScanFeedback feedback;       // 聲音＋震動回饋
    private View permissionDenied;       // 權限被拒引導畫面
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
        setContentView(R.layout.activity_scan);

        // 系統列（瀏海／導覽列）內距：全部以「絕對值＝基底 dp＋系統列」計算，避免多次
        // dispatch 疊加。底部操作列以 paddingBottom 讓底色填到螢幕底（含導覽列之後），
        // 浮動的提示與結果卡則跟著導覽列高度上移，避免三按鈕導覽時被操作列擠壓／重疊。
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.scan_root),
                (view, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    float d = getResources().getDisplayMetrics().density;

                    setTopMargin(findViewById(R.id.tv_progress_chip), bars.top + (int) (12 * d));

                    View bar = findViewById(R.id.bottom_bar);
                    bar.setPadding((int) (8 * d), (int) (12 * d), (int) (8 * d), (int) (12 * d) + bars.bottom);

                    setBottomMargin(findViewById(R.id.tv_scan_hint), (int) (112 * d) + bars.bottom);
                    setBottomMargin(findViewById(R.id.scan_result_card), (int) (104 * d) + bars.bottom);

                    return WindowInsetsCompat.CONSUMED;
                }
        );

        previewView     = findViewById(R.id.preview_view);
        scannerOverlay  = findViewById(R.id.scanner_overlay);
        resultCard      = findViewById(R.id.scan_result_card);
        tvProgressChip  = findViewById(R.id.tv_progress_chip);
        tvHint          = findViewById(R.id.tv_scan_hint);
        icTorch         = findViewById(R.id.ic_torch);
        chipTorch       = findViewById(R.id.chip_torch);
        lblTorch        = findViewById(R.id.lbl_torch);
        btnTorch        = findViewById(R.id.btn_torch);
        btnCancel       = findViewById(R.id.btn_cancel);
        awaitingOverlay = findViewById(R.id.awaiting_overlay);

        assets  = AssetRepository.full().getAssets();

        scanner = new QrScanner();
        feedback = new ScanFeedback(this);

        updateProgressChip();

        permissionDenied = findViewById(R.id.permission_denied);
        permissionDenied.findViewById(R.id.btn_open_settings)
                .setOnClickListener(v -> PermissionGuide.openAppSettings(this));

        findViewById(R.id.btn_capture).setOnClickListener(v -> capturePhoto());
        btnCancel.setOnClickListener(v -> finish());
        btnTorch.setOnClickListener(v -> toggleTorch());

        // 請求相機權限
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
            permissionDenied.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
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

    // ── 掃到結果 ────────────────────────────────────────
    private void handleScanResult(String raw) {
        // 待確認期間暫停，避免直接掃下一張時靜默丟失這筆，也避免嗶聲／卡片重播。
        if (awaitingConfirm) return;

        long now = System.currentTimeMillis();
        if (now - lastScanTime < COOLDOWN_MS) return;
        if (raw.equals(lastScannedRaw) && now - lastScanTime < COOLDOWN_MS * 2) return;

        ScanClassifier.Result r =
                ScanClassifier.classify(raw, assets, AssetIdFormat::isValid);

        // 誤觸：不成格式 → 靜默忽略，不動冷卻、不刷新畫面
        if (r.outcome == ScanClassifier.Outcome.IGNORED_INVALID) return;

        lastScannedRaw = raw;
        lastScanTime   = now;

        switch (r.outcome) {
            case MATCHED:
            case UNMATCHED: {
                Asset asset = r.asset;

                // 重複掃描：已盤點過的財產再掃 → 橘卡提示，不覆蓋原記錄
                if (asset.status != Asset.Status.UNCHECKED) {
                    resultCard.showDuplicate("此財產已完成盤點", asset.id + "　" + asset.name);
                    break;
                }

                if (r.outcome == ScanClassifier.Outcome.MATCHED) {
                    // 相符：即時落檔，綠卡自動消散
                    AssetRepository.full().recordCheck(asset, Asset.Status.MATCHED);
                    if (scannerOverlay != null) scannerOverlay.flashSuccess();
                    feedback.success();
                    resultCard.showSuccess("✅ 盤點成功", asset.id + "　" + asset.name);
                } else {
                    // 不相符：先不落檔，紅卡列差異對比，確認才寫；期間暗遮罩暫停掃描。
                    if (scannerOverlay != null) scannerOverlay.flashError();
                    feedback.unmatched();
                    awaitingConfirm = true;
                    setAwaiting(true);
                    ScannedTag scanned = ScannedTag.parse(raw);
                    resultCard.showUnmatched("⚠️ 部門或地點不相符", scanned, asset,
                            () -> {   // 確認寫入不相符
                                AssetRepository.full().recordCheck(asset, Asset.Status.UNMATCHED);
                                setAwaiting(false);
                                awaitingConfirm = false;
                                updateProgressChip();
                            },
                            () -> {   // 略過（不寫入）
                                setAwaiting(false);
                                awaitingConfirm = false;
                            });
                }
                break;
            }
            case SURPLUS: {
                // 盤盈（清單外編號）：確認卡提示，確認才新增並標記「不相符」（清單外屬差異）
                if (scannerOverlay != null) scannerOverlay.flashError();
                feedback.unmatched();
                awaitingConfirm = true;
                setAwaiting(true);
                String detail = r.id + "　" + safe(r.name) + "\n"
                        + safe(r.department) + " · " + safe(r.location);
                resultCard.showConfirm("⚠️ 此編號不在清單", detail, "確認新增（不相符）",
                        R.color.status_warning_bg, R.color.status_warning,
                        () -> {   // 確認新增為不相符
                            Asset newAsset = new Asset(r.id, r.name, r.department, r.location,
                                    Asset.Status.UNCHECKED, "");
                            AssetRepository.full().addChecked(newAsset, Asset.Status.UNMATCHED);
                            setAwaiting(false);
                            awaitingConfirm = false;
                            updateProgressChip();
                            Toast.makeText(this, "⚠️ 已新增（不相符）：" + r.id, Toast.LENGTH_SHORT).show();
                        },
                        () -> {   // 略過
                            setAwaiting(false);
                            awaitingConfirm = false;
                        });
                break;
            }
            default:
                break;
        }

        updateProgressChip();
    }

    /** 待確認狀態：暗遮罩＋取景框紅角停線＋提示改字（A4）。 */
    private void setAwaiting(boolean awaiting) {
        if (awaitingOverlay != null) {
            awaitingOverlay.setVisibility(awaiting ? View.VISIBLE : View.GONE);
        }
        if (scannerOverlay != null) scannerOverlay.setAwaitingConfirm(awaiting);
        tvHint.setText(awaiting ? "請先處理待確認項目" : "對準 QR Code 自動掃描");
        tvHint.setTextColor(ContextCompat.getColor(this,
                awaiting ? R.color.scan_flash_error : R.color.text_on_primary));
    }

    // QR 破損：拍整張標籤存到 CSV 同資料夾（QR 破損無從得知編號，故檔名以 UNKNOWN 起頭）
    private void capturePhoto() {
        String name = PhotoEvidence.buildFileName("");
        Uri uri = PhotoEvidence.createInCsvFolder(this, name);
        if (uri == null) {
            Toast.makeText(this, "請先載入 CSV（需資料夾權限）", Toast.LENGTH_LONG).show();
            return;
        }
        pendingPhotoUri = uri;
        pendingPhotoName = name;
        takePhoto.launch(uri);
    }

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
        lblTorch.setText(on ? "補光開" : "補光");
        lblTorch.setTextColor(ContextCompat.getColor(this,
                on ? R.color.scan_torch_on : R.color.scan_label));
    }

    // 常駐進度：已盤（相符＋不符）/ 總數
    private void updateProgressChip() {
        if (tvProgressChip == null) return;
        if (assets == null) {
            tvProgressChip.setText("已盤 0 / 0");
            return;
        }
        int total = assets.size();
        long checked = 0;
        for (Asset a : assets) {
            if (a.status != Asset.Status.UNCHECKED) checked++;
        }
        tvProgressChip.setText("已盤 " + checked + " / " + total);
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

    // 定點寫檔：只有記憶體有未落檔變更時才真正寫一次（背景執行緒）。
    private void flushCsvAsync() {
        new Thread(() -> {
            try {
                AssetRepository.full().flush(getContentResolver());
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
        if (feedback != null) feedback.release();
    }
}
