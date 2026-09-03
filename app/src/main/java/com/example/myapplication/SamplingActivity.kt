package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.data.AssetRepository
import com.example.myapplication.data.CsvManager
import com.example.myapplication.model.Asset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 抽盤頁：逐筆瀏覽財產，對單一目標啟動掃描比對。
 *
 * Kotlin 筆記：
 * - 只讀取清單，故 `assets` 用唯讀的 `List<Asset>?`（與 ScanActivity 的 MutableList 對照）。
 * - `return@setOnClickListener` / `return@runOnUiThread`：從 lambda 中提前返回要標記回哪個 lambda。
 * - `assets.any { }` / `count { }` 取代 Java Stream。
 */
class SamplingActivity : AppCompatActivity() {

    private var assets: List<Asset>? = null
    private var currentIndex = 0 // 目前顯示第幾筆

    // ── DOM ──────────────────────────────────────────────
    private lateinit var tvProgress: TextView
    private lateinit var tvPosition: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvId: TextView
    private lateinit var tvName: TextView
    private lateinit var tvDepartment: TextView
    private lateinit var tvLocation: TextView
    private lateinit var btnLoad: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnScan: Button
    private lateinit var btnDone: Button
    private lateinit var btnJumpPrev: Button
    private lateinit var btnJumpNext: Button

    // 掃描啟動器
    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val raw = result.data?.getStringExtra(SamplingScanActivity.RESULT_RAW)
                if (raw != null) {
                    handleScanResult(raw)
                }
            }
        }

    // ── 檔案選擇器 ──────────────────────────────────────
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                AssetRepository.csvUri = uri
                loadCsv(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sampling)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.sampling_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        bindViews()
        setupListeners()
    }

    private fun bindViews() {
        tvProgress = findViewById(R.id.tv_progress)
        tvPosition = findViewById(R.id.tv_position)
        tvResult = findViewById(R.id.tv_result)
        tvId = findViewById(R.id.tv_id)
        tvName = findViewById(R.id.tv_name)
        tvDepartment = findViewById(R.id.tv_department)
        tvLocation = findViewById(R.id.tv_location)
        btnLoad = findViewById(R.id.btn_load)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        btnScan = findViewById(R.id.btn_scan)
        btnDone = findViewById(R.id.btn_done)
        btnJumpPrev = findViewById(R.id.btn_jump_prev)
        btnJumpNext = findViewById(R.id.btn_jump_next)
    }

    private fun setupListeners() {
        btnLoad.setOnClickListener { filePicker.launch(arrayOf("*/*")) }

        btnPrev.setOnClickListener { showAssetAt(currentIndex - 1) }
        btnNext.setOnClickListener { showAssetAt(currentIndex + 1) }

        btnJumpPrev.setOnClickListener { jumpToUnchecked(false) }
        btnJumpNext.setOnClickListener { jumpToUnchecked(true) }

        btnScan.setOnClickListener {
            val assets = assets ?: return@setOnClickListener
            if (assets.isEmpty()) return@setOnClickListener
            val target = assets[currentIndex]

            val intent = Intent(this, SamplingScanActivity::class.java)
            intent.putExtra(SamplingScanActivity.EXTRA_TARGET_ID, target.id)
            intent.putExtra(SamplingScanActivity.EXTRA_TARGET_NAME, target.name)
            scanLauncher.launch(intent)
        }

        btnDone.setOnClickListener { finish() }
    }

    // ── 載入 CSV ─────────────────────────────────────────
    private fun loadCsv(uri: Uri) {
        Thread {
            try {
                val result = CsvManager.read(contentResolver, uri)
                runOnUiThread {
                    assets = result
                    AssetRepository.assets = result

                    if (result.isEmpty()) {
                        Toast.makeText(this, "CSV 是空的", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    currentIndex = 0
                    showAssetAt(0)
                    enableActionButtons(true)
                    Toast.makeText(this, "載入成功：${result.size} 筆", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "讀取失敗：${e.message}", Toast.LENGTH_LONG).show()
                }
                Log.e(TAG, "讀取失敗", e)
            }
        }.start()
    }

    // ── 顯示指定位置的資產 ───────────────────────────────
    private fun showAssetAt(index: Int) {
        val assets = assets ?: return
        if (assets.isEmpty()) return
        if (index < 0 || index >= assets.size) return

        currentIndex = index
        val a = assets[index]

        tvId.text = a.id
        tvName.text = a.name
        tvDepartment.text = a.department.ifEmpty { "－" }
        tvLocation.text = a.location.ifEmpty { "－" }

        tvPosition.text = "第 ${index + 1} 筆 / 共 ${assets.size} 筆"

        // 比對結果顯示
        when (a.status) {
            Asset.Status.MATCHED -> {
                tvResult.text = "✅ 相符"
                tvResult.setTextColor(Color.parseColor("#16a34a"))
                tvResult.setBackgroundColor(Color.parseColor("#f0fdf4"))
            }
            Asset.Status.UNMATCHED -> {
                tvResult.text = "⚠️ 不相符"
                tvResult.setTextColor(Color.parseColor("#c2410c"))
                tvResult.setBackgroundColor(Color.parseColor("#fff7ed"))
            }
            Asset.Status.UNCHECKED -> {
                tvResult.text = "待掃描"
                tvResult.setTextColor(Color.parseColor("#8e8e93"))
                tvResult.setBackgroundColor(Color.parseColor("#f2f2f7"))
            }
        }

        updateNavButtons()
        updateProgress()
    }

    // ── 跳到上/下一筆未盤點 ──────────────────────────────
    private fun jumpToUnchecked(forward: Boolean) {
        val assets = assets ?: return
        val total = assets.size
        val step = if (forward) 1 else -1

        for (i in 1..total) {
            val idx = currentIndex + step * i
            if (idx < 0 || idx >= total) break // 不繞圈，到頂/到底就停

            if (assets[idx].status == Asset.Status.UNCHECKED) {
                showAssetAt(idx)
                return
            }
        }

        Toast.makeText(
            this,
            if (forward) "無下筆未盤點的資產" else "無上筆未盤點的資產",
            Toast.LENGTH_SHORT,
        ).show()
    }

    // ── 更新導覽按鈕狀態 ─────────────────────────────────
    private fun updateNavButtons() {
        val assets = assets ?: return
        btnPrev.isEnabled = currentIndex > 0
        btnNext.isEnabled = currentIndex < assets.size - 1

        // 跳轉按鈕：只要還有未盤點的就可用
        val hasUnchecked = assets.any { it.status == Asset.Status.UNCHECKED }
        btnJumpPrev.isEnabled = hasUnchecked
        btnJumpNext.isEnabled = hasUnchecked
    }

    // ── 更新進度顯示 ─────────────────────────────────────
    private fun updateProgress() {
        val assets = assets ?: return
        val checked = assets.count { it.status != Asset.Status.UNCHECKED }
        tvProgress.text = "進度：$checked / ${assets.size} 已盤點"
    }

    // ── 啟用底部操作按鈕 ─────────────────────────────────
    private fun enableActionButtons(enabled: Boolean) {
        btnScan.isEnabled = enabled
    }

    // ── 處理掃描結果 ─────────────────────────────────────
    private fun handleScanResult(raw: String) {
        val assets = assets ?: return
        if (assets.isEmpty()) return

        val target = assets[currentIndex]

        // 拆解 QR Code
        val parts = raw.split(";")
        val scannedId = parts.getOrNull(0)?.trim().orEmpty()
        val scannedDepartment = parts.getOrNull(2)?.trim().orEmpty()
        val scannedLocation = parts.getOrNull(3)?.trim().orEmpty()

        // 雙重保險：理論上 SamplingScanActivity 已經過濾掉非目標的資產
        if (scannedId != target.id) {
            Toast.makeText(this, "掃描結果與目標不符", Toast.LENGTH_SHORT).show()
            return
        }

        // 比對部門和地點
        val isMatched = target.department == scannedDepartment && target.location == scannedLocation

        target.status = if (isMatched) Asset.Status.MATCHED else Asset.Status.UNMATCHED
        target.checkedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        AssetRepository.markDirty() // 只改記憶體，落檔延到 onPause/onStop

        // 重新顯示這一筆，更新 UI
        showAssetAt(currentIndex)

        Toast.makeText(
            this,
            if (isMatched) "✅ 盤點成功（相符）" else "⚠️ 盤點完成（不相符）",
            Toast.LENGTH_SHORT,
        ).show()
    }

    // ── 定點寫檔 ─────────────────────────────────────────
    // 只有記憶體有未落檔變更時才真正寫一次（背景執行緒）。
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

    companion object {
        private const val TAG = "SamplingActivity"
    }
}
