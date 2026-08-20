package com.example.myapplication;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.example.myapplication.data.CsvManager;
import com.example.myapplication.model.Asset;
import com.example.myapplication.data.AssetRepository;
import com.example.myapplication.ui.AssetAdapter;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private List<Asset> assets;
    private AssetAdapter adapter;
    private TextView tvProgress;
    private Button btnScan;
    private RecyclerView recyclerView;
    private enum Filter { ALL, UNCHECKED, MATCHED, UNMATCHED }
    private Filter currentFilter = Filter.ALL;
    private TextView tabAll, tabUnchecked, tabMatched, tabUnmatched;
    private View tabIndicator;
    private TextView tvEmpty;
    private List<Asset> filteredAssets = new ArrayList<>();

    // filePicker 回調時存起來
    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) {
                            // 取得持久性權限，App 重開後還能讀寫同一個檔案
                            getContentResolver().takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            );
                            loadCsv(uri);
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 處理瀏海／狀態列高度
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.root_layout),
                (view, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    view.setPadding(0, bars.top, 0, bars.bottom);
                    return WindowInsetsCompat.CONSUMED;
                }
        );

        tvProgress  = findViewById(R.id.tv_progress);
        btnScan     = findViewById(R.id.btn_scan);

        recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.btn_load).setOnClickListener(v ->
                filePicker.launch(new String[]{ "text/*", "application/csv" })
        );

        btnScan.setOnClickListener(v -> {
            startActivity(new Intent(this, ScanActivity.class));
        });

        tabAll        = findViewById(R.id.tab_all);
        tabUnchecked  = findViewById(R.id.tab_unchecked);
        tabMatched    = findViewById(R.id.tab_matched);
        tabUnmatched  = findViewById(R.id.tab_unmatched);
        tabIndicator  = findViewById(R.id.tab_indicator);
        tvEmpty       = findViewById(R.id.tv_empty);

        tabAll.setOnClickListener(v -> selectFilter(Filter.ALL, tabAll));
        tabUnchecked.setOnClickListener(v -> selectFilter(Filter.UNCHECKED, tabUnchecked));
        tabMatched.setOnClickListener(v -> selectFilter(Filter.MATCHED, tabMatched));
        tabUnmatched.setOnClickListener(v -> selectFilter(Filter.UNMATCHED, tabUnmatched));

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            refreshList();
            updateProgress();
        }
    }

    private void loadCsv(Uri uri) {
        new Thread(() -> {
            try {
                List<Asset> result = CsvManager.read(getContentResolver(), uri);
                runOnUiThread(() -> {
                    assets  = result;
                    AssetRepository.getInstance().setAssets(assets);
                    AssetRepository.getInstance().setCsvUri(uri);
                    // Adapter 綁定 filteredAssets
                    adapter = new AssetAdapter(this, filteredAssets);
                    recyclerView.setAdapter(adapter);

                    refreshList();      // 根據當前篩選刷新
                    updateProgress();
                    btnScan.setEnabled(true);

                    // 預設選中「全部」
                    selectFilter(Filter.ALL, tabAll);
                    Toast.makeText(this,
                            "載入成功：" + assets.size() + " 筆",
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this,
                                "讀取失敗：" + e.getMessage(),
                                Toast.LENGTH_LONG).show()
                );
                Log.e(TAG, "讀取失敗", e);
            }
        }).start();
    }

    private void updateProgress() {
        if (assets == null) return;

        int total     = assets.size();
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