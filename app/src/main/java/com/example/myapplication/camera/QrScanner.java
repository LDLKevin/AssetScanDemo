package com.example.myapplication.camera;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

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

    /** 解碼結果回呼；保證在主執行緒觸發。 */
    public interface OnDecoded {
        void onDecoded(String raw);
    }

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final MultiFormatReader zxingReader = new MultiFormatReader();

    private Camera camera; // 綁定後用來控制手電筒
    private boolean torchOn = false;

    public QrScanner() {
        Map<DecodeHintType, Object> hints = new HashMap<>();
        hints.put(DecodeHintType.TRY_HARDER, true);
        zxingReader.setHints(hints);
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

                ImageAnalysis analysis = new ImageAnalysis.Builder()
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

            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
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
