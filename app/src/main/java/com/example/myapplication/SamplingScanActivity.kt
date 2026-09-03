package com.example.myapplication

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.logic.AssetIdFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 抽盤掃描頁：只認「當前目標」那一張 QR，掃到別的成格式資產會提示（含冷卻）。
 *
 * Kotlin 筆記：
 * - Java 的 `public static final` 常數 → `companion object` 裡的 `const val`，Java 端仍可用
 *   `SamplingScanActivity.RESULT_RAW` 存取（給還沒轉 Kotlin 的呼叫端用）。
 * - `layoutParams as ViewGroup.MarginLayoutParams`：Kotlin 的型別轉換用 `as`。
 * - `getSystemService(Vibrator::class.java)` 用型別安全的多載，省掉 Java 的手動 cast。
 */
class SamplingScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvTargetId: TextView
    private lateinit var tvTargetName: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnTorch: TextView
    private lateinit var btnCancel: Button

    private var targetId: String? = null
    private var lastWrongScanToast = 0L

    private var camera: Camera? = null
    private var torchOn = false
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var zxingReader: MultiFormatReader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sampling_scan)

        previewView = findViewById(R.id.preview_view)
        tvTargetId = findViewById(R.id.tv_target_id)
        tvTargetName = findViewById(R.id.tv_target_name)
        tvHint = findViewById(R.id.tv_hint)
        btnTorch = findViewById(R.id.btn_torch)
        btnCancel = findViewById(R.id.btn_cancel)

        // 處理瀏海：頂部資訊條和底部取消按鈕往內推
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scan_root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density

            // 頂部資訊條加上瀏海高度
            val topBar = findViewById<View>(R.id.top_bar)
            (topBar.layoutParams as ViewGroup.MarginLayoutParams).let {
                it.topMargin = bars.top
                topBar.layoutParams = it
            }

            // 底部取消按鈕加上導覽鍵高度
            val cancel = findViewById<View>(R.id.btn_cancel)
            (cancel.layoutParams as ViewGroup.MarginLayoutParams).let {
                it.bottomMargin = bars.bottom + 24 * density.toInt()
                cancel.layoutParams = it
            }

            // 手電筒按鈕也跟著頂部偏移
            val torch = findViewById<View>(R.id.btn_torch)
            (torch.layoutParams as ViewGroup.MarginLayoutParams).let {
                it.topMargin = bars.top + 50 * density.toInt()
                torch.layoutParams = it
            }

            WindowInsetsCompat.CONSUMED
        }

        // 從 Intent 取得目標
        targetId = intent.getStringExtra(EXTRA_TARGET_ID)
        val targetName = intent.getStringExtra(EXTRA_TARGET_NAME)

        if (targetId.isNullOrEmpty()) {
            Toast.makeText(this, "缺少目標資產", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        tvTargetId.text = targetId
        tvTargetName.text = targetName ?: ""

        cameraExecutor = Executors.newSingleThreadExecutor()
        zxingReader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.TRY_HARDER to true))
        }

        btnCancel.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        btnTorch.setOnClickListener { toggleTorch() }

        // 相機權限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            Toast.makeText(this, "需要相機權限才能掃描", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ── 啟動相機 ─────────────────────────────────────────
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor, ::analyzeImage)

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                )
            } catch (e: Exception) {
                Log.e(TAG, "啟動相機失敗", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── 解碼 ─────────────────────────────────────────────
    private fun analyzeImage(imageProxy: ImageProxy) {
        try {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val width = imageProxy.width
            val height = imageProxy.height

            val source = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))

            try {
                val result = zxingReader.decodeWithState(bitmap)
                val raw = result.text
                runOnUiThread { handleScanResult(raw) }
            } catch (decodeError: Exception) {
                // 沒掃到，正常情況
            } finally {
                zxingReader.reset()
            }
        } finally {
            imageProxy.close()
        }
    }

    // ── 掃到結果 ────────────────────────────────────────
    private fun handleScanResult(raw: String) {
        val scannedId = raw.split(";")[0].trim()

        if (scannedId == targetId) {
            // ✅ 正確的資產，回傳結果
            val intent = Intent()
            intent.putExtra(RESULT_RAW, raw)
            setResult(Activity.RESULT_OK, intent)
            navigatorVibrate()
            finish()
        } else if (AssetIdFormat.isValid(scannedId)) {
            // ❌ 是別的（成格式的）資產，才提示；不成格式的雜訊視為誤觸，靜默忽略
            val now = System.currentTimeMillis()
            if (now - lastWrongScanToast > TOAST_COOLDOWN_MS) {
                lastWrongScanToast = now
                Toast.makeText(this, "⚠️ 這不是當前要找的資產（$scannedId）", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun navigatorVibrate() {
        // 震動回饋
        val vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(60)
    }

    // ── 手電筒 ───────────────────────────────────────────
    private fun toggleTorch() {
        val camera = camera ?: return
        if (!camera.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "此裝置不支援手電筒", Toast.LENGTH_SHORT).show()
            return
        }
        torchOn = !torchOn
        camera.cameraControl.enableTorch(torchOn)
        btnTorch.text = if (torchOn) "💡" else "🔦"
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "SamplingScanActivity"
        private const val REQ_CAMERA = 100
        private const val TOAST_COOLDOWN_MS = 1500L

        // ── Intent extras ────────────────────────────────
        const val EXTRA_TARGET_ID = "target_id"
        const val EXTRA_TARGET_NAME = "target_name"

        // ── Result extras（回傳給 SamplingActivity）─────────
        const val RESULT_RAW = "raw" // 原始 QR Code 字串
    }
}
