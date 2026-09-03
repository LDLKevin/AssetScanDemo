package com.example.myapplication

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.data.AssetRepository
import com.example.myapplication.data.CsvManager
import com.example.myapplication.model.Asset
import com.example.myapplication.ui.AssetAdapter

/**
 * 全盤主頁：載入 CSV、清單、分頁篩選、進入掃描。
 *
 * Kotlin 筆記：
 * - 在 onCreate 才賦值的 View 用 `lateinit var`：宣告非空，但延後初始化（存取前沒賦值會丟明確例外）。
 * - `registerForActivityResult` 用屬性初始化 + 尾隨 lambda，取代 Java 的匿名回呼。
 * - `assets.count { it.status == ... }` 取代 Java Stream 的 filter().count()。
 * - 字串模板 `"進度：$checked / $total"` 取代字串相加。
 */
class MainActivity : AppCompatActivity() {

    private var assets: List<Asset>? = null
    private var adapter: AssetAdapter? = null
    private lateinit var tvProgress: TextView
    private lateinit var btnScan: Button
    private lateinit var recyclerView: RecyclerView

    private enum class Filter { ALL, UNCHECKED, MATCHED, UNMATCHED }
    private var currentFilter = Filter.ALL

    private lateinit var tabAll: TextView
    private lateinit var tabUnchecked: TextView
    private lateinit var tabMatched: TextView
    private lateinit var tabUnmatched: TextView
    private lateinit var tabIndicator: View
    private lateinit var tvEmpty: TextView

    private val filteredAssets = ArrayList<Asset>()

    // filePicker 回調時存起來
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                // 取得持久性權限，App 重開後還能讀寫同一個檔案
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                loadCsv(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 處理瀏海／狀態列高度
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_layout)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        tvProgress = findViewById(R.id.tv_progress)
        btnScan = findViewById(R.id.btn_scan)

        recyclerView = findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)

        findViewById<View>(R.id.btn_load).setOnClickListener {
            filePicker.launch(arrayOf("text/*", "application/csv"))
        }

        btnScan.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }

        tabAll = findViewById(R.id.tab_all)
        tabUnchecked = findViewById(R.id.tab_unchecked)
        tabMatched = findViewById(R.id.tab_matched)
        tabUnmatched = findViewById(R.id.tab_unmatched)
        tabIndicator = findViewById(R.id.tab_indicator)
        tvEmpty = findViewById(R.id.tv_empty)

        tabAll.setOnClickListener { selectFilter(Filter.ALL, tabAll) }
        tabUnchecked.setOnClickListener { selectFilter(Filter.UNCHECKED, tabUnchecked) }
        tabMatched.setOnClickListener { selectFilter(Filter.MATCHED, tabMatched) }
        tabUnmatched.setOnClickListener { selectFilter(Filter.UNMATCHED, tabUnmatched) }
    }

    override fun onResume() {
        super.onResume()
        if (adapter != null) {
            refreshList()
            updateProgress()
        }
    }

    private fun loadCsv(uri: Uri) {
        Thread {
            try {
                val result = CsvManager.read(contentResolver, uri)
                runOnUiThread {
                    assets = result
                    AssetRepository.assets = result
                    AssetRepository.csvUri = uri
                    // Adapter 綁定 filteredAssets
                    adapter = AssetAdapter(this, filteredAssets)
                    recyclerView.adapter = adapter

                    refreshList()      // 根據當前篩選刷新
                    updateProgress()
                    btnScan.isEnabled = true

                    // 預設選中「全部」
                    selectFilter(Filter.ALL, tabAll)
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

    private fun updateProgress() {
        val assets = assets ?: return

        val total = assets.size
        val unchecked = assets.count { it.status == Asset.Status.UNCHECKED }
        val matched = assets.count { it.status == Asset.Status.MATCHED }
        val unmatched = assets.count { it.status == Asset.Status.UNMATCHED }
        val checked = matched + unmatched

        tvProgress.text = "進度：$checked / $total"

        tabAll.text = "全部 $total"
        tabUnchecked.text = "未盤點 $unchecked"
        tabMatched.text = "已盤點 $matched"
        tabUnmatched.text = "不相符 $unmatched"
    }

    private fun selectFilter(filter: Filter, tab: TextView) {
        currentFilter = filter
        moveIndicatorTo(tab)
        refreshList()
    }

    private fun moveIndicatorTo(tab: TextView) {
        // 更新所有 Tab 的文字顏色
        val activeColor = ContextCompat.getColor(this, R.color.evergreen_primary)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary)

        // === 用參照相等（===）比對是不是同一個 View 物件，等同 Java 的 ==
        tabAll.setTextColor(if (tab === tabAll) activeColor else inactiveColor)
        tabUnchecked.setTextColor(if (tab === tabUnchecked) activeColor else inactiveColor)
        tabMatched.setTextColor(if (tab === tabMatched) activeColor else inactiveColor)
        tabUnmatched.setTextColor(if (tab === tabUnmatched) activeColor else inactiveColor)

        // 移動底線（用 layout params 改寬度和位置）
        tab.post {
            val lp = tabIndicator.layoutParams
            lp.width = tab.width
            tabIndicator.layoutParams = lp
            tabIndicator.x = tab.x
        }
    }

    private fun refreshList() {
        val assets = assets ?: return

        filteredAssets.clear()
        for (a in assets) {
            val include = when (currentFilter) {
                Filter.UNCHECKED -> a.status == Asset.Status.UNCHECKED
                Filter.MATCHED -> a.status == Asset.Status.MATCHED
                Filter.UNMATCHED -> a.status == Asset.Status.UNMATCHED
                Filter.ALL -> true
            }
            if (include) filteredAssets.add(a)
        }

        adapter?.notifyDataSetChanged()

        // 空狀態提示
        if (filteredAssets.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = getEmptyText()
        } else {
            tvEmpty.visibility = View.GONE
        }
    }

    private fun getEmptyText(): String = when (currentFilter) {
        Filter.UNCHECKED -> "沒有未盤點的資產"
        Filter.MATCHED -> "沒有已盤點的資產"
        Filter.UNMATCHED -> "沒有不相符的資產"
        else -> "尚未載入資料"
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
