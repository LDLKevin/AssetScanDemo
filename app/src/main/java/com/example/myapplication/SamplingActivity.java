package com.example.myapplication;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.data.AssetRepository;
import com.example.myapplication.data.CsvManager;
import com.example.myapplication.logic.ScanClassifier;
import com.example.myapplication.logic.ScannedTag;
import com.example.myapplication.model.Asset;
import com.example.myapplication.ui.AssetAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 抽盤主畫面：與全盤一致的財產列表（可篩選）。點任一列 → 以該筆為目標進入抽盤掃描，
 * 掃描相符／確認不符後回寫並即時更新列表。
 */
public class SamplingActivity extends AppCompatActivity {

    private static final String TAG = "SamplingActivity";

    private List<Asset> assets;
    private final List<Asset> filteredAssets = new ArrayList<>();
    private AssetAdapter adapter;

    private enum Filter { ALL, UNCHECKED, MATCHED, UNMATCHED }
    private Filter currentFilter = Filter.ALL;

    private TextView tvProgress, tvEmpty;
    private TextView tabAll, tabUnchecked, tabMatched, tabUnmatched;
    private View tabIndicator;
    private RecyclerView recyclerView;

    // ── 掃描啟動器 ──────────────────────────────────────
    private final ActivityResultLauncher<Intent> scanLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null) {
                            String raw = result.getData()
                                    .getStringExtra(SamplingScanActivity.RESULT_RAW);
                            if (raw != null) handleScanResult(raw);
                        }
                    }
            );

    // ── 檔案選擇器 ──────────────────────────────────────
    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) {
                            getContentResolver().takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            );
                            AssetRepository.getInstance().setCsvUri(uri);
                            loadCsv(uri);
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sampling);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.sampling_root),
                (view, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    view.setPadding(0, bars.top, 0, bars.bottom);
                    return WindowInsetsCompat.CONSUMED;
                }
        );

        tvProgress   = findViewById(R.id.tv_progress);
        tvEmpty      = findViewById(R.id.tv_empty);
        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        tabAll       = findViewById(R.id.tab_all);
        tabUnchecked = findViewById(R.id.tab_unchecked);
        tabMatched   = findViewById(R.id.tab_matched);
        tabUnmatched = findViewById(R.id.tab_unmatched);
        tabIndicator = findViewById(R.id.tab_indicator);

        findViewById(R.id.btn_load).setOnClickListener(v ->
                filePicker.launch(new String[]{ "*/*" }));
        findViewById(R.id.btn_done).setOnClickListener(v -> finish());

        tabAll.setOnClickListener(v -> selectFilter(Filter.ALL, tabAll));
        tabUnchecked.setOnClickListener(v -> selectFilter(Filter.UNCHECKED, tabUnchecked));
        tabMatched.setOnClickListener(v -> selectFilter(Filter.MATCHED, tabMatched));
        tabUnmatched.setOnClickListener(v -> selectFilter(Filter.UNMATCHED, tabUnmatched));

        // 若記憶體已有清單（例如從其他頁返回），直接顯示
        List<Asset> existing = AssetRepository.getInstance().getAssets();
        if (existing != null && !existing.isEmpty()) {
            assets = existing;
            bindAdapter();
            selectFilter(Filter.ALL, tabAll);
            updateProgress();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            refreshList();
            updateProgress();
        }
    }

    // ── 載入 CSV ─────────────────────────────────────────
    private void loadCsv(Uri uri) {
        new Thread(() -> {
            try {
                List<Asset> result = CsvManager.read(getContentResolver(), uri);
                runOnUiThread(() -> {
                    assets = result;
                    AssetRepository.getInstance().setAssets(assets);

                    if (assets.isEmpty()) {
                        Toast.makeText(this, "CSV 是空的", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    bindAdapter();
                    selectFilter(Filter.ALL, tabAll);
                    updateProgress();
                    Toast.makeText(this,
                            "載入成功：" + assets.size() + " 筆",
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "讀取失敗：" + e.getMessage(),
                                Toast.LENGTH_LONG).show());
                Log.e(TAG, "讀取失敗", e);
            }
        }).start();
    }

    private void bindAdapter() {
        adapter = new AssetAdapter(this, filteredAssets);
        adapter.setOnAssetClickListener(this::launchScan);
        recyclerView.setAdapter(adapter);
    }

    // ── 點列 → 以該筆為目標進入抽盤掃描 ──────────────────
    private void launchScan(Asset target) {
        Intent intent = new Intent(this, SamplingScanActivity.class);
        intent.putExtra(SamplingScanActivity.EXTRA_TARGET_ID, target.id);
        intent.putExtra(SamplingScanActivity.EXTRA_TARGET_NAME, target.name);
        scanLauncher.launch(intent);
    }

    // ── 處理掃描結果（回寫 + 更新列表）───────────────────
    private void handleScanResult(String raw) {
        if (assets == null) return;
        ScannedTag tag = ScannedTag.parse(raw);

        Asset target = null;
        for (Asset a : assets) {
            if (a.id.equals(tag.id)) { target = a; break; }
        }
        if (target == null) return;

        boolean isMatched = ScanClassifier.matches(target, tag);
        AssetRepository.getInstance().recordCheck(target,
                isMatched ? Asset.Status.MATCHED : Asset.Status.UNMATCHED);

        refreshList();
        updateProgress();
        Toast.makeText(this,
                isMatched ? "✅ 盤點成功（相符）" : "⚠️ 盤點完成（不相符）",
                Toast.LENGTH_SHORT).show();
    }

    // ── 篩選 / 清單 ──────────────────────────────────────
    private void selectFilter(Filter filter, TextView tab) {
        currentFilter = filter;
        moveIndicatorTo(tab);
        refreshList();
    }

    private void moveIndicatorTo(TextView tab) {
        int active   = ContextCompat.getColor(this, R.color.evergreen_primary);
        int inactive = ContextCompat.getColor(this, R.color.text_secondary);
        tabAll.setTextColor(tab == tabAll ? active : inactive);
        tabUnchecked.setTextColor(tab == tabUnchecked ? active : inactive);
        tabMatched.setTextColor(tab == tabMatched ? active : inactive);
        tabUnmatched.setTextColor(tab == tabUnmatched ? active : inactive);

        tab.post(() -> {
            ViewGroup.LayoutParams lp = tabIndicator.getLayoutParams();
            lp.width = tab.getWidth();
            tabIndicator.setLayoutParams(lp);
            tabIndicator.setX(tab.getX());
        });
    }

    private void refreshList() {
        if (assets == null) return;
        filteredAssets.clear();
        for (Asset a : assets) {
            boolean include;
            switch (currentFilter) {
                case UNCHECKED: include = a.status == Asset.Status.UNCHECKED; break;
                case MATCHED:   include = a.status == Asset.Status.MATCHED;   break;
                case UNMATCHED: include = a.status == Asset.Status.UNMATCHED; break;
                case ALL:
                default:        include = true;
            }
            if (include) filteredAssets.add(a);
        }
        if (adapter != null) adapter.notifyDataSetChanged();

        if (filteredAssets.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(getEmptyText());
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    private String getEmptyText() {
        switch (currentFilter) {
            case UNCHECKED: return "沒有未盤點的資產";
            case MATCHED:   return "沒有已盤點的資產";
            case UNMATCHED: return "沒有不相符的資產";
            default:        return "尚未載入資料";
        }
    }

    private void updateProgress() {
        if (assets == null) return;
        int total = assets.size();
        long unchecked = assets.stream().filter(a -> a.status == Asset.Status.UNCHECKED).count();
        long matched   = assets.stream().filter(a -> a.status == Asset.Status.MATCHED).count();
        long unmatched = assets.stream().filter(a -> a.status == Asset.Status.UNMATCHED).count();
        long checked   = matched + unmatched;

        tvProgress.setText("進度：" + checked + " / " + total);
        tabAll.setText("全部 " + total);
        tabUnchecked.setText("未盤點 " + unchecked);
        tabMatched.setText("已盤點 " + matched);
        tabUnmatched.setText("不相符 " + unmatched);
    }

    // ── 定點寫檔 ─────────────────────────────────────────
    private void flushCsvAsync() {
        new Thread(() -> {
            try {
                AssetRepository.getInstance().flush(getContentResolver());
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "CSV 寫入失敗：" + e.getMessage(),
                                Toast.LENGTH_LONG).show());
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
}
