package com.eitc.assetscan.camera;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.eitc.assetscan.logic.CropGeometry;
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

/**
 * 相機 + QR 解碼管線的 deep module。把 CameraX 綁定、ZXing 解碼、YUV 轉換、手電筒與
 * 執行緒收尾全藏在小介面之後；呼叫端只需在授權後 {@link #bind}、收到主執行緒上的
 * {@link OnDecoded#onDecoded}、並在銷毀時 {@link #close}。
 *
 * <p>純傳輸層：每次成功解碼即回呼一次，不做冷卻／去重——那屬於各畫面的政策。
 *
 * <p>前置條件：{@link #bind} 前相機權限須已授權。全盤與抽盤兩掃描頁共用。
 */
public final class QrScanner {

    private static final String TAG = "QrScanner";

    // 只解取景框範圍內的畫面：四邊各放大這個比例當對準誤差的緩衝（見 CropGeometry）。
    private static final float DECODE_MARGIN_FRACTION = 0.15f;

    /** 解碼結果回呼；保證在主執行緒觸發。 */
    public interface OnDecoded {
        void onDecoded(String raw);
    }

    /** 取景框位置＋所在 View 尺寸的快照；由呼叫端在框位置變動時更新。 */
    private static final class DecodeRegion {
        final float viewWidth, viewHeight;
        final float frameLeft, frameTop, frameRight, frameBottom;

        DecodeRegion(float viewWidth, float viewHeight,
                     float frameLeft, float frameTop, float frameRight, float frameBottom) {
            this.viewWidth = viewWidth;
            this.viewHeight = viewHeight;
            this.frameLeft = frameLeft;
            this.frameTop = frameTop;
            this.frameRight = frameRight;
            this.frameBottom = frameBottom;
        }
    }

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final MultiFormatReader zxingReader = new MultiFormatReader();

    private Camera camera; // 綁定後用來控制手電筒
    private boolean torchOn = false;

    // 取景框位置；analyze() 跑在背景執行緒，setDecodeRegion() 跑在主執行緒，
    // 用 volatile 整包替換（而非個別欄位）避免讀到新舊參數混雜的半套狀態。
    private volatile DecodeRegion decodeRegion;
    // 換算失敗時退回整張影像解碼；同一段持續失敗期間只記一次 log，避免洗版。
    private volatile boolean cropFailureLogged = false;

    public QrScanner() {
        Map<DecodeHintType, Object> hints = new HashMap<>();
        hints.put(DecodeHintType.TRY_HARDER, true);
        zxingReader.setHints(hints);
    }

    /**
     * 更新取景框位置：解碼只認框內（含緩衝）範圍。呼叫端在取景框位置變動（含第一次量測
     * 完成）時呼叫，通常直接掛在 {@code ScannerOverlayView.OnFrameChangeListener} 上。
     *
     * @param viewWidth  取景框所在 View 的寬（px，即 PreviewView 寬）
     * @param viewHeight 取景框所在 View 的高（px，即 PreviewView 高）
     */
    public void setDecodeRegion(float viewWidth, float viewHeight,
                                 float frameLeft, float frameTop, float frameRight, float frameBottom) {
        decodeRegion = new DecodeRegion(viewWidth, viewHeight, frameLeft, frameTop, frameRight, frameBottom);
    }

    /** 綁定相機到 previewView，成功解碼時在主執行緒回呼 onDecoded。 */
    public void bind(@NonNull LifecycleOwner owner,
                     @NonNull PreviewView previewView,
                     @NonNull OnDecoded onDecoded) {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(previewView.getContext());

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // 解析度盡量選跟螢幕相近的長寬比，縮小 PreviewView FILL_CENTER 裁切
                // 跟 analysis 影像之間的比例落差，讓取景框裁切範圍的換算更準。
                ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .build();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor,
                        imageProxy -> analyze(imageProxy, previewView, onDecoded));

                provider.unbindAll();
                camera = provider.bindToLifecycle(
                        owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);

            } catch (Exception e) {
                Log.e(TAG, "啟動相機失敗", e);
            }
        }, ContextCompat.getMainExecutor(previewView.getContext()));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyze(ImageProxy imageProxy, PreviewView previewView, OnDecoded onDecoded) {
        try {
            ByteBuffer buffer = imageProxy.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();
            CropGeometry.Rect crop = computeCropRect(
                    width, height, imageProxy.getImageInfo().getRotationDegrees());

            PlanarYUVLuminanceSource source = crop != null
                    ? new PlanarYUVLuminanceSource(
                            bytes, width, height, crop.left, crop.top, crop.width(), crop.height(), false)
                    : new PlanarYUVLuminanceSource(
                            bytes, width, height, 0, 0, width, height, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

            try {
                Result result = zxingReader.decodeWithState(bitmap);
                String raw = result.getText();
                // 切回主執行緒交付，呼叫端不必再 runOnUiThread
                previewView.post(() -> onDecoded.onDecoded(raw));
            } catch (Exception decodeError) {
                // 沒掃到，正常情況
            } finally {
                zxingReader.reset();
            }
        } finally {
            imageProxy.close();
        }
    }

    /**
     * 取景框換算成當前這幀影像的裁切矩形；還沒收到過 {@link #setDecodeRegion} 或換算失敗
     * （尺寸退化等，理論上不該發生）時回傳 {@code null}，呼叫端退回整張影像解碼。
     */
    private CropGeometry.Rect computeCropRect(int imageWidth, int imageHeight, int rotationDegrees) {
        DecodeRegion region = decodeRegion;
        if (region == null) return null;

        CropGeometry.Rect crop = CropGeometry.mapFrameToImageCrop(
                region.viewWidth, region.viewHeight,
                region.frameLeft, region.frameTop, region.frameRight, region.frameBottom,
                DECODE_MARGIN_FRACTION,
                imageWidth, imageHeight, rotationDegrees);

        if (crop == null) {
            if (!cropFailureLogged) {
                Log.w(TAG, "取景框裁切範圍換算失敗，本次退回整張影像解碼");
                cropFailureLogged = true;
            }
        } else {
            cropFailureLogged = false;
        }
        return crop;
    }

    /** 此裝置是否具備閃光燈；未綁定或無相機時回傳 false。 */
    public boolean hasFlashUnit() {
        return camera != null && camera.getCameraInfo().hasFlashUnit();
    }

    /** 切換手電筒，回傳切換後的開/關狀態。無相機或無閃光燈時回傳 false 不動作。 */
    public boolean toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return false;
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        return torchOn;
    }

    /** 關閉解碼執行緒。呼叫端請在 onDestroy() 呼叫。 */
    public void close() {
        cameraExecutor.shutdown();
    }
}
