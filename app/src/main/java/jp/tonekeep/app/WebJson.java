package jp.tonekeep.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

final class WebJson {
    static String get(String url) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "ToneKeep/1.6 (+https://github.com/tstyr/ToneKeep)");
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status == 429) throw new IOException("サービスが混雑しています。時間を置いて再試行してください。");
            if (status != 200) throw new IOException("サービスに接続できませんでした（HTTP " + status + "）。");
            try (var input = connection.getInputStream(); var output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("中止しました。");
                    if (output.size() + count > 2 * 1024 * 1024) throw new IOException("歌詞サービスの応答が大きすぎます。");
                    output.write(buffer, 0, count);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
}
