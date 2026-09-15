package com.eitc.assetscan;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eitc.assetscan.data.AssetRepository;
import com.eitc.assetscan.data.CsvFolder;
import com.eitc.assetscan.data.CsvManager;
import com.eitc.assetscan.data.FolderAccess;
import com.eitc.assetscan.logic.ScanClassifier;
import com.eitc.assetscan.logic.ScannedTag;
import com.eitc.assetscan.model.Asset;
import com.eitc.assetscan.ui.AssetAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 抽盤主畫面：與全盤一致的財產列表（可篩選）。點任一列 → 以該筆為目標進入抽盤掃描，
 * 掃描相符／確認不符後回寫並即時更新列表。
 */
public class SamplingActivity extends AppCompatActivity {

    private static final String TAG = "SamplingActivity";
    // 抽盤 CSV 檔名約定前綴（與全盤 ALL 區分，兩模式各自獨立作業）
    private static final String CSV_PREFIX = "RAN";

    private List<Asset> assets;
    private final List<Asset> filteredAssets = new ArrayList<>();
    private AssetAdapter adapter;

    private enum Filter { ALL, UNCHECKED, MATCHED, UNMATCHED }
    private Filter currentFilter = Filter.ALL;

    private TextView tvFilename, tvProgressCount, tvProgressDetail, tvProgressPct, tvEmpty;
    private ProgressBar pbProgress;
    private TextView tabAll, tabUnchecked, tabMatched, tabUnmatched;
    private View tabIndicator;
    private ProgressBar progressLoading;
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

    // ── 資料夾選擇器 ────────────────────────────────────
    private final ActivityResultLauncher<Uri> folderPicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocumentTree(),
                    treeUri -> {
                        if (treeUri != null) onFolderPicked(treeUri);
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

        tvFilename       = findViewById(R.id.tv_filename);
        tvProgressCount  = findViewById(R.id.tv_progress_count);
        tvProgressDetail = findViewById(R.id.tv_progress_detail);
        tvProgressPct    = findViewById(R.id.tv_progress_pct);
        pbProgress       = findViewById(R.id.pb_progress);
        tvEmpty      = findViewById(R.id.tv_empty);
        progressLoading = findViewById(R.id.progress_loading);
        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        tabAll       = findViewById(R.id.tab_all);
        tabUnchecked = findViewById(R.id.tab_unchecked);
        tabMatched   = findViewById(R.id.tab_matched);
        tabUnmatched = findViewById(R.id.tab_unmatched);
        tabIndicator = findViewById(R.id.tab_indicator);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_load).setOnClickListener(v -> onLoadClicked());

        tabAll.setOnClickListener(v -> selectFilter(Filter.ALL, tabAll));
        tabUnchecked.setOnClickListener(v -> selectFilter(Filter.UNCHECKED, tabUnchecked));
        tabMatched.setOnClickListener(v -> selectFilter(Filter.MATCHED, tabMatched));
        tabUnmatched.setOnClickListener(v -> selectFilter(Filter.UNMATCHED, tabUnmatched));

        // 還原上次記住的資料夾（Plan A）：有的話載入時就不必再選資料夾／再授權
        if (AssetRepository.getTreeUri() == null) {
            Uri saved = FolderAccess.restore(this);
            if (saved != null) AssetRepository.setTreeUri(saved);
        }

        // 若記憶體已有抽盤清單（例如從其他頁返回），直接顯示
        List<Asset> existing = AssetRepository.sampling().getAssets();
        if (existing != null && !existing.isEmpty()) {
            assets = existing;
            bindAdapter();
            setFilename(AssetRepository.sampling().getCsvName());
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

    // 按「載入」：已授權過資料夾就直接列 CSV（不再跳權限）；第一次才選資料夾
    private void onLoadClicked() {
        Uri tree = AssetRepository.getTreeUri();
        if (tree != null) {
            listAndChoose(tree);
        } else {
            folderPicker.launch(null);
        }
    }

    // 第一次選資料夾：取得持久權限、記住資料夾，之後就不再詢問
    private void onFolderPicked(Uri treeUri) {
        getContentResolver().takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        );
        AssetRepository.setTreeUri(treeUri);
        FolderAccess.remember(this, treeUri);
        listAndChoose(treeUri);
    }

    // 列出資料夾內的抽盤 CSV（僅 RAN 開頭），多個則讓使用者選
    private void listAndChoose(Uri treeUri) {
        List<DocumentFile> csvs = CsvFolder.findCsvFiles(this, treeUri, CSV_PREFIX);
        if (csvs.isEmpty()) {
            Toast.makeText(this, "此資料夾內找不到抽盤（" + CSV_PREFIX + " 開頭）CSV 檔",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (csvs.size() == 1) {
            chooseCsv(csvs.get(0));
            return;
        }
        String[] names = new String[csvs.size()];
        for (int i = 0; i < csvs.size(); i++) names[i] = csvs.get(i).getName();
        new AlertDialog.Builder(this)
                .setTitle("選擇抽盤 CSV")
                .setItems(names, (d, which) -> chooseCsv(csvs.get(which)))
                .show();
    }

    // 選定某個 CSV：記住檔名（header 顯示）後進入載入流程
    private void chooseCsv(DocumentFile f) {
        AssetRepository.sampling().setCsvName(f.getName());
        confirmThenUse(f.getUri());
    }

    // 有進行中的盤點進度時，載入新清單前先警示（避免無聲覆蓋未匯出的進度）
    private boolean hasProgress() {
        if (assets == null) return false;
        for (Asset a : assets) {
            if (a.status != Asset.Status.UNCHECKED) return true;
        }
        return false;
    }

    private void confirmThenUse(Uri csvUri) {
        if (!hasProgress()) {
            useCsv(csvUri);
            return;
        }
        long total   = assets.size();
        long checked = assets.stream().filter(a -> a.status != Asset.Status.UNCHECKED).count();
        new AlertDialog.Builder(this)
                .setTitle("載入新清單")
                .setMessage("目前已有進行中的盤點（已盤 " + checked + " / " + total
                        + " 筆）。載入新清單會取代目前資料，確定載入？")
                .setPositiveButton("載入新清單", (d, w) -> useCsv(csvUri))
                .setNegativeButton("繼續原作業", null)
                .show();
    }

    private void useCsv(Uri csvUri) {
        AssetRepository.sampling().setCsvUri(csvUri);
        loadCsv(csvUri);
    }

    // ── 載入 CSV ─────────────────────────────────────────
    private void loadCsv(Uri uri) {
        progressLoading.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                List<Asset> result = CsvManager.read(getContentResolver(), uri);
                runOnUiThread(() -> {
                    progressLoading.setVisibility(View.GONE);
                    if (result.isEmpty()) {
                        showError("CSV 沒有可用資料，請確認格式（需含 財產編號／名稱／部門／地點 欄位）");
                        return;
                    }
                    assets = result;
                    AssetRepository.sampling().setAssets(assets);
                    bindAdapter();
                    setFilename(AssetRepository.sampling().getCsvName());
                    selectFilter(Filter.ALL, tabAll);
                    updateProgress();
                    Snackbar.make(findViewById(R.id.sampling_root),
                            "已載入 " + assets.size() + " 筆財產",
                            Snackbar.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "讀取失敗", e);
                runOnUiThread(() -> {
                    progressLoading.setVisibility(View.GONE);
                    showError("無法讀取 CSV，請確認檔案未損毀且為 Big5 編碼");
                });
            }
        }).start();
    }

    private void showError(String msg) {
        Snackbar.make(findViewById(R.id.sampling_root), msg, Snackbar.LENGTH_LONG).show();
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
        AssetRepository.sampling().recordCheck(target,
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
        int pct = total > 0 ? (int) Math.round(checked * 100.0 / total) : 0;

        tvProgressCount.setText(checked + " / " + total + " 筆");
        pbProgress.setProgress(pct);
        tvProgressDetail.setText("已盤點 " + matched + "、不相符 " + unmatched);
        tvProgressPct.setText(pct + "%" + (pct == 100 ? "  ✓ 完成" : ""));

        tabAll.setText("全部 " + total);
        tabUnchecked.setText("未盤點 " + unchecked);
        tabMatched.setText("已盤點 " + matched);
        tabUnmatched.setText("不相符 " + unmatched);
    }

    private void setFilename(String name) {
        tvFilename.setText(name == null || name.isEmpty() ? "尚未載入 CSV" : name);
    }

    // ── 定點寫檔 ─────────────────────────────────────────
    private void flushCsvAsync() {
        new Thread(() -> {
            try {
                AssetRepository.sampling().flush(getContentResolver());
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
