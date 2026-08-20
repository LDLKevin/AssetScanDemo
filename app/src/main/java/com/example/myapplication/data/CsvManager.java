package com.example.myapplication.data;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Environment;

import com.example.myapplication.model.Asset;
import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;

import org.apache.commons.io.input.BOMInputStream;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

public class CsvManager {
    // 讀取：透過 Uri（系統檔案選擇器）
    public static List<Asset> read(ContentResolver resolver, Uri uri) throws Exception {
        List<Asset> list = new ArrayList<>();

        try (InputStream is = resolver.openInputStream(uri);
             BOMInputStream bomIs = BOMInputStream.builder().setInputStream(is).get();
             CSVReader reader = new CSVReader(new InputStreamReader(bomIs))) {

            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length < 4) continue;

                String id         = row[0].trim();
                String name       = row[1].trim();
                String department = row[2].trim();
                String location   = row[3].trim();

                // 第 5 欄：盤點狀態
                String s = row.length > 4 ? row[4].trim() : "";
                Asset.Status status;
                switch (s) {
                    case "V": status = Asset.Status.MATCHED;   break;
                    case "X": status = Asset.Status.UNMATCHED; break;
                    default:  status = Asset.Status.UNCHECKED; break;
                }

                String checkedAt = row.length > 5 ? row[5].trim() : "";

                list.add(new Asset(id, name, department, location, status, checkedAt));
            }
        }
        return list;
    }

    public static void write(ContentResolver resolver, Uri uri, List<Asset> assets) throws Exception {
        try (OutputStream os = resolver.openOutputStream(uri, "wt");
             CSVWriter writer = new CSVWriter(new OutputStreamWriter(os))) {
            for (Asset a : assets) {
                String s;
                switch (a.status) {
                    case MATCHED:   s = "V"; break;
                    case UNMATCHED: s = "X"; break;
                    default:        s = "";  break;
                }
                writer.writeNext(new String[]{
                        a.id,
                        a.name,
                        a.department,
                        a.location,
                        s,
                        a.checkedAt != null ? a.checkedAt : ""
                });
            }
        }
    }
}
