package com.eitc.assetscan.data;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Environment;

import com.eitc.assetscan.model.Asset;
import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;

import org.apache.commons.io.input.BOMInputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class CsvManager {

    // 讀寫全面統一 UTF-8（上下游已協調一致）；寫檔不加 BOM，供其他程式直接讀取。
    private static final Charset UTF8 = StandardCharsets.UTF_8;

    // 讀取：透過 Uri（系統檔案選擇器）
    public static List<Asset> read(ContentResolver resolver, Uri uri) throws Exception {
        List<Asset> list = new ArrayList<>();

        byte[] raw;
        try (InputStream is = resolver.openInputStream(uri);
             BOMInputStream bomIs = BOMInputStream.builder().setInputStream(is).get()) {
            raw = readAllBytes(bomIs);
        }

        // 先把整份內容嚴格解成 UTF-8：遇到不合法的位元組直接失敗並給出明確訊息，
        // 不讓非 UTF-8 檔案被硬解成亂碼後還被當成正常資料處理掉。
        String content;
        try {
            CharsetDecoder decoder = UTF8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            content = decoder.decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("檔案編碼不是 UTF-8，請確認匯出設定為 UTF-8 後再試一次", e);
        }

        try (CSVReader reader = new CSVReader(new StringReader(content))) {
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

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    public static void write(ContentResolver resolver, Uri uri, List<Asset> assets) throws Exception {
        // 序列化先於落檔：先在記憶體用 UTF-8 把整份 CSV 產生完成，確認無誤後才單次寫入目的檔，
        // 避免序列化中途出錯留下半份損毀的既有檔案。
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (CSVWriter writer = new CSVWriter(new OutputStreamWriter(buffer, UTF8))) {
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
        byte[] payload = buffer.toByteArray();

        try (OutputStream os = resolver.openOutputStream(uri, "wt")) {
            os.write(payload);
            os.flush();
        }
    }
}
