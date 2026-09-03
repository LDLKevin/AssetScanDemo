package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.camera.view.PreviewView
import com.example.myapplication.data.AssetRepository
import com.example.myapplication.logic.AssetIdFormat
import com.example.myapplication.logic.ScanClassifier
import com.example.myapplication.model.Asset
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 全盤掃描頁：連續掃描、四種判定（相符／不符／盤盈／誤觸）、盤盈可寫入為新財產。
 *
 * Kotlin 筆記：
 * - `assets` 用 `MutableList<Asset>?`：盤盈寫入時要 `assets.add(newAsset)`，唯讀的 List 沒有 add。
 * - EditText 讀取用 `.text`，但「寫入」要用 `setText(...)`：因為 EditText.getText() 回傳 Editable，
 *   直接寫 `etId.text = ""` 會型別不符（String 不是 Editable）。這是 Kotlin/Android 常見的一個坑。
 * - `ScanClassifier.classify(raw, assets) { AssetIdFormat.isValid(it) }`：最後一個參數是 fun interface，
 *   用尾隨 lambda 傳入格式驗證器。
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvWarning: TextView
    private lateinit var etId: EditText
    private lateinit var etName: EditText
    private lateinit var etDepartment: EditText
    private lateinit var etLocation: EditText
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnWrite: Button
    private lateinit var btnDone: Button

    private var assets: MutableList<Asset>? = null       // 來自 Repository
    private val history = ArrayList<Asset>()             // 本次盤點過的財產（依時間順序）
    private var historyIndex = -1                         // 目前顯示的是 history 第幾筆

    private var isNewAsset = false  // 當前顯示的是否為新增財產
    private var isEdited = false    // 使用者是否編輯過部門或地點
    private var lastScanTime = 0L
    private var lastScannedRaw = ""

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var zxingReader: MultiFormatReader
    private var camera: Camera? = null // 用來控制手電筒
    private var torchOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        // 處理瀏海
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scan_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        previewView = findViewById(R.id.preview_view)
        tvWarning = findViewById(R.id.tv_warning)
        etId = findViewById(R.id.et_id)
        etName = findViewById(R.id.et_name)
        etDepartment = findViewById(R.id.et_department)
        etLocation = findViewById(R.id.et_location)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        btnWrite = findViewById(R.id.btn_write)
        btnDone = findViewById(R.id.btn_done)

        assets = AssetRepository.assets

        cameraExecutor = Executors.newSingleThreadExecutor()
        zxingReader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.TRY_HARDER to true))
        }

        // 註：原本有一段以 TextWatcher 監聽部門/地點編輯、把「寫入」變「更新」的程式碼，
        // 在 Java 版即已被註解停用（isEdited 因此恆為 false），轉檔時一併省略。

        btnPrev.setOnClickListener { showHistory(historyIndex - 1) }
        btnNext.setOnClickListener { showHistory(historyIndex + 1) }
        btnWrite.setOnClickListener { onWriteClicked() }
        btnDone.setOnClickListener { finish() }

        clearForm()
        updateNavButtons()

        // 請求相機權限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }

        val btnTorch = findViewById<TextView>(R.id.btn_torch)
        btnTorch.setOnClickListener {
            val camera = camera ?: return@setOnClickListener
            if (!camera.cameraInfo.hasFlashUnit()) {
                Toast.makeText(this, "此裝置不支援手電筒", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            torchOn = !torchOn
            camera.cameraControl.enableTorch(torchOn)
            btnTorch.text = if (torchOn) "💡" else "🔦"
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
                // 接住回傳的 Camera 物件
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                )
            } catch (e: Exception) {
                Log.e(TAG, "啟動相機失敗", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        // 冷卻中
        if (System.currentTimeMillis() - lastScanTime < COOLDOWN_MS) {
            imageProxy.close()
            return
        }

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

    private fun handleScanResult(raw: String) {
        // 同一張 QR 短時間內去重（避免停在鏡頭前被連續記錄）
        val now = System.currentTimeMillis()
        if (raw == lastScannedRaw && now - lastScanTime < COOLDOWN_MS * 2) {
            return
        }

        val r = ScanClassifier.classify(raw, assets) { AssetIdFormat.isValid(it) }

        // 誤觸：不成格式 → 靜默忽略，不動冷卻、不刷新畫面
        if (r.outcome == ScanClassifier.Outcome.IGNORED_INVALID) {
            return
        }

        // 到這裡才算一次有效掃描，才起算冷卻
        lastScannedRaw = raw
        lastScanTime = now

        when (r.outcome) {
            ScanClassifier.Outcome.MATCHED, ScanClassifier.Outcome.UNMATCHED -> {
                val matched = r.asset!! // MATCHED/UNMATCHED 時清單命中，必為非 null
                val isMatched = r.outcome == ScanClassifier.Outcome.MATCHED
                matched.status = if (isMatched) Asset.Status.MATCHED else Asset.Status.UNMATCHED
                matched.checkedAt = currentTime()
                AssetRepository.markDirty() // 只改記憶體，落檔延到 onPause/onStop

                history.add(matched)
                historyIndex = history.size - 1
                displayAsset(matched, false)

                if (isMatched) {
                    Toast.makeText(this, "✅ ${r.id} 盤點成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "⚠️ ${r.id} 部門或地點不相符", Toast.LENGTH_LONG).show()
                }
            }

            ScanClassifier.Outcome.SURPLUS -> {
                val newAsset = Asset(r.id, r.name, r.department, r.location, Asset.Status.UNCHECKED, "")
                displayAsset(newAsset, true)
                Toast.makeText(this, "⚠️ 未列入清單", Toast.LENGTH_SHORT).show()
            }

            ScanClassifier.Outcome.IGNORED_INVALID -> {
                // 已在上面提前 return，這裡不會走到；列出讓 when 分支完整
            }
        }

        updateNavButtons()
    }

    // 顯示財產到表格
    private fun displayAsset(asset: Asset, isNew: Boolean) {
        isNewAsset = isNew
        isEdited = false

        etId.setText(asset.id)
        etName.setText(asset.name)
        etDepartment.setText(asset.department)
        etLocation.setText(asset.location)

        if (isNew) {
            tvWarning.visibility = View.VISIBLE
            btnWrite.setText("寫入")
            btnWrite.isEnabled = true
            btnWrite.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#34c759"))
        } else {
            tvWarning.visibility = View.GONE
            btnWrite.setText("寫入")
            btnWrite.isEnabled = false
            btnWrite.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#cccccc"))
        }
    }

    private fun clearForm() {
        etId.setText("")
        etName.setText("")
        etDepartment.setText("")
        etLocation.setText("")
        tvWarning.visibility = View.GONE
        btnWrite.isEnabled = false
        btnWrite.setText("寫入")
        btnWrite.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#cccccc"))
    }

    // 上一筆 / 下一筆
    private fun showHistory(newIndex: Int) {
        if (newIndex < 0 || newIndex >= history.size) return
        historyIndex = newIndex
        displayAsset(history[newIndex], false)
        updateNavButtons()
    }

    private fun updateNavButtons() {
        btnPrev.isEnabled = historyIndex > 0
        btnNext.isEnabled = historyIndex in 0 until history.size - 1
    }

    // 寫入按鈕
    private fun onWriteClicked() {
        val id = etId.text.toString().trim()
        val name = etName.text.toString().trim()
        val department = etDepartment.text.toString().trim()
        val location = etLocation.text.toString().trim()

        if (isNewAsset) {
            // 新增財產：直接視為已盤點且相符
            val newAsset = Asset(id, name, department, location, Asset.Status.MATCHED, currentTime())
            assets?.add(newAsset)
            history.add(newAsset)
            historyIndex = history.size - 1
            AssetRepository.markDirty()
            Toast.makeText(this, "✅ 已新增：$id", Toast.LENGTH_SHORT).show()

            isNewAsset = false
            displayAsset(newAsset, false)
        } else if (isEdited) {
            // 更新既有財產
            val target = assets?.firstOrNull { it.id == id }
            if (target != null) {
                target.department = department
                target.location = location
                AssetRepository.markDirty()
                Toast.makeText(this, "✅ 已更新：$id", Toast.LENGTH_SHORT).show()
                isEdited = false
                btnWrite.setText("寫入")
                btnWrite.isEnabled = false
                btnWrite.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#cccccc"))
            }
        }

        updateNavButtons()
    }

    // 定點寫檔：只有記憶體有未落檔變更時才真正寫一次（背景執行緒）。
    private fun flushCsvAsync() {
        Thread {
            try {
                AssetRepository.flush(contentResolver)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "CSV 寫入失敗：${e.message}", Toast.LENGTH_LONG).show()
                }
                Log.e(TAG, "CSV 寫入失敗", e)
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        flushCsvAsync()
    }

    override fun onStop() {
        super.onStop()
        flushCsvAsync()
    }

    private fun currentTime(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "ScanActivity"
        private const val REQ_CAMERA = 100

        // 結果停留畫面已由 history/displayAsset 承載，冷卻只需防手震連拍，故縮短。
        private const val COOLDOWN_MS = 900L
    }
}
