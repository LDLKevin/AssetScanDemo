package com.eitc.assetscan;

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

import com.eitc.assetscan.data.CsvFolder;
import com.eitc.assetscan.data.FolderAccess;

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

import com.eitc.assetscan.data.CsvManager;
import com.eitc.assetscan.model.Asset;
import com.eitc.assetscan.data.AssetRepository;
import com.eitc.assetscan.ui.AssetAdapter;

import java.util.ArrayList;
import java.util.List;

public class FullActivity extends AppCompatActivity {

    private static final String TAG = "FullActivity";
    // 全盤 CSV 檔名約定前綴（與抽盤 RAN 區分，兩模式各自獨立作業）
    private static final String CSV_PREFIX = "ALL";

    private List<Asset> assets;
    private AssetAdapter adapter;
    private TextView tvFilename, tvProgressCount, tvProgressDetail, tvProgressPct;
    private ProgressBar pbProgress;
    private View btnScan;
    private RecyclerView recyclerView;
    private enum Filter { ALL, UNCHECKED, MATCHED, UNMATCHED }
    private Filter currentFilter = Filter.ALL;
    private TextView tabAll, tabUnchecked, tabMatched, tabUnmatched;
    private View tabIndicator;
    private TextView tvEmpty;
    private ProgressBar progressLoading;
    private List<Asset> filteredAssets = new ArrayList<>();

    // 改選「資料夾」：App 持有整個資料夾的讀寫權限，才能在 CSV 旁建立照片等檔案
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
        setContentView(R.layout.activity_full);

        // 處理瀏海／狀態列高度
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.root_layout),
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
        btnScan          = findViewById(R.id.btn_scan);

        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_load).setOnClickListener(v -> onLoadClicked());

        btnScan.setOnClickListener(v -> startActivity(new Intent(this, FullScanActivity.class)));
        setScanEnabled(false);

        tabAll        = findViewById(R.id.tab_all);
        tabUnchecked  = findViewById(R.id.tab_unchecked);
        tabMatched    = findViewById(R.id.tab_matched);
        tabUnmatched  = findViewById(R.id.tab_unmatched);
        tabIndicator  = findViewById(R.id.tab_indicator);
        tvEmpty       = findViewById(R.id.tv_empty);
        progressLoading = findViewById(R.id.progress_loading);

        tabAll.setOnClickListener(v -> selectFilter(Filter.ALL, tabAll));
        tabUnchecked.setOnClickListener(v -> selectFilter(Filter.UNCHECKED, tabUnchecked));
        tabMatched.setOnClickListener(v -> selectFilter(Filter.MATCHED, tabMatched));
        tabUnmatched.setOnClickListener(v -> selectFilter(Filter.UNMATCHED, tabUnmatched));

        // 還原上次記住的資料夾（Plan A）：有的話載入時就不必再選資料夾／再授權
        if (AssetRepository.getTreeUri() == null) {
            Uri saved = FolderAccess.restore(this);
            if (saved != null) AssetRepository.setTreeUri(saved);
        }

        // 若記憶體已有全盤清單（例如已載入過或從其他頁返回），直接顯示
        List<Asset> existing = AssetRepository.full().getAssets();
        if (existing != null && !existing.isEmpty()) {
            assets = existing;
            adapter = new AssetAdapter(this, filteredAssets);
            recyclerView.setAdapter(adapter);
            setScanEnabled(true);
            setFilename(AssetRepository.full().getCsvName());
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

    // 列出資料夾內的全盤 CSV（僅 ALL 開頭），多個則讓使用者選
    private void listAndChoose(Uri treeUri) {
        List<DocumentFile> csvs = CsvFolder.findCsvFiles(this, treeUri, CSV_PREFIX);
        if (csvs.isEmpty()) {
            Toast.makeText(this, "此資料夾內找不到全盤（" + CSV_PREFIX + " 開頭）CSV 檔",
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
                .setTitle("選擇全盤 CSV")
                .setItems(names, (d, which) -> chooseCsv(csvs.get(which)))
                .show();
    }

    // 選定某個 CSV：記住檔名（header 顯示）後進入載入流程
    private void chooseCsv(DocumentFile f) {
        AssetRepository.full().setCsvName(f.getName());
        confirmThenLoad(f.getUri());
    }

    // 有進行中的盤點進度時，載入新清單前先警示（避免無聲覆蓋未匯出的進度）
    private boolean hasProgress() {
        if (assets == null) return false;
        for (Asset a : assets) {
            if (a.status != Asset.Status.UNCHECKED) return true;
        }
        return false;
    }

    private void confirmThenLoad(Uri uri) {
        if (!hasProgress()) {
            loadCsv(uri);
            return;
        }
        long total   = assets.size();
        long checked = assets.stream().filter(a -> a.status != Asset.Status.UNCHECKED).count();
        new AlertDialog.Builder(this)
                .setTitle("載入新清單")
                .setMessage("目前已有進行中的盤點（已盤 " + checked + " / " + total
                        + " 筆）。載入新清單會取代目前資料，確定載入？")
                .setPositiveButton("載入新清單", (d, w) -> loadCsv(uri))
                .setNegativeButton("繼續原作業", null)
                .show();
    }

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
                    assets  = result;
                    AssetRepository.full().setAssets(assets);
                    AssetRepository.full().setCsvUri(uri);
                    // Adapter 綁定 filteredAssets
                    adapter = new AssetAdapter(this, filteredAssets);
                    recyclerView.setAdapter(adapter);

                    refreshList();      // 根據當前篩選刷新
                    updateProgress();
                    setScanEnabled(true);
                    setFilename(AssetRepository.full().getCsvName());

                    // 預設選中「全部」
                    selectFilter(Filter.ALL, tabAll);
                    Snackbar.make(findViewById(R.id.root_layout),
                            "已載入 " + assets.size() + " 筆財產",
                            Snackbar.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "讀取失敗", e);
                runOnUiThread(() -> {
                    progressLoading.setVisibility(View.GONE);
                    showError("無法讀取 CSV，請確認檔案未損毀且為 UTF-8 編碼");
                });
            }
        }).start();
    }

    private void showError(String msg) {
        Snackbar.make(findViewById(R.id.root_layout), msg, Snackbar.LENGTH_LONG).show();
    }

    private void updateProgress() {
        if (assets == null) return;

        int total     = assets.size();
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

    private void setScanEnabled(boolean enabled) {
        btnScan.setEnabled(enabled);
        btnScan.setAlpha(enabled ? 1f : 0.45f);
    }

    private void setFilename(String name) {
        tvFilename.setText(name == null || name.isEmpty() ? "尚未載入 CSV" : name);
    }

    private void selectFilter(Filter filter, TextView tab) {
        currentFilter = filter;
        moveIndicatorTo(tab);
        refreshList();
    }

    private void moveIndicatorTo(TextView tab) {
        // 更新所有 Tab 的文字顏色
        int activeColor   = ContextCompat.getColor(this, R.color.evergreen_primary);
        int inactiveColor = ContextCompat.getColor(this, R.color.text_secondary);

        tabAll.setTextColor(tab == tabAll ? activeColor : inactiveColor);
        tabUnchecked.setTextColor(tab == tabUnchecked ? activeColor : inactiveColor);
        tabMatched.setTextColor(tab == tabMatched ? activeColor : inactiveColor);
        tabUnmatched.setTextColor(tab == tabUnmatched ? activeColor : inactiveColor);

        // 移動底線（用 layout params 改寬度和位置）
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

        // 空狀態提示
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
}