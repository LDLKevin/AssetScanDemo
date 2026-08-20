package com.example.myapplication.data;

import com.example.myapplication.model.Asset;

import java.util.List;

// 單例模式，存放當前載入的財產清單
public class AssetRepository {
    private static AssetRepository instance;
    private List<Asset> assets;
    private android.net.Uri csvUri;

    public static AssetRepository getInstance() {
        if (instance == null) instance = new AssetRepository();
        return instance;
    }

    public List<Asset> getAssets() { return assets; }
    public void setAssets(List<Asset> assets) { this.assets = assets; }

    public android.net.Uri getCsvUri() { return csvUri; }
    public void setCsvUri(android.net.Uri uri) { this.csvUri = uri; }
}