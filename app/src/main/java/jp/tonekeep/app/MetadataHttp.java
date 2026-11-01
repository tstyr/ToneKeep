package jp.tonekeep.app;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded HTTPS GETs only. Never accepts or uploads audio or file paths. */
final class MetadataHttp {
    private static final Map<String,Long> next = new HashMap<>();
    static synchronized byte[] get(Context context, String address, int maximum, String accept) throws Exception {
        URL url = new URL(address);
        if (!url.getProtocol().equals("https")) throw new IOException("HTTPSのみ対応しています");
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (!allowed(host)) throw new IOException("対応していない情報取得先です");
        var prefs = context.getSharedPreferences("metadata-http",Context.MODE_PRIVATE);
        long now = System.currentTimeMillis(), blocked = prefs.getLong(host,0);
        if (blocked > now) throw new IOException("取得元の待機時間中です。" + ((blocked-now+999)/1000) + "秒後に再試行してください。");
        long wait = next.getOrDefault(host,0L)-now; if (wait>0) Thread.sleep(wait);
        next.put(host,System.currentTimeMillis()+(host.equals("musicbrainz.org") ? 1100 : 400));
        HttpURLConnection connection = null;
        try {
            for (int redirects=0; redirects<6; redirects++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("中止しました");
                connection=(HttpURLConnection)url.openConnection(); connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(12000); connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent","ToneKeep/1.5.0 (https://github.com/tstyr/ToneKeep)"); connection.setRequestProperty("Accept",accept); connection.setRequestProperty("Accept-Encoding","identity");
                int code=connection.getResponseCode();
                if (code==429 || code==503) { long seconds=retrySeconds(connection.getHeaderField("Retry-After")); prefs.edit().putLong(host,System.currentTimeMillis()+seconds*1000).apply(); throw new IOException("取得元が混雑しています。"+seconds+"秒後に再試行してください。"); }
                if (code>=300 && code<400) {
                    String location=connection.getHeaderField("Location"); if (location==null) throw new IOException("転送先がありません"); URL target=new URL(url,location);
                    if (!target.getProtocol().equals("https") || !allowed(target.getHost().toLowerCase(Locale.ROOT))) throw new IOException("対応していない転送先です");
                    connection.disconnect(); connection=null; url=target; continue;
                }
                if (code!=200) throw new IOException("情報を取得できませんでした（HTTP "+code+"）");
                if (connection.getContentLengthLong()>maximum) throw new IOException("取得データが大きすぎます");
                try (InputStream in=connection.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                    byte[] b=new byte[16384]; int n;
                    while ((n=in.read(b))!=-1) { if (Thread.currentThread().isInterrupted()) throw new InterruptedException("中止しました"); if (out.size()+n>maximum) throw new IOException("取得データが大きすぎます"); out.write(b,0,n); }
                    return out.toByteArray();
                }
            }
            throw new IOException("転送回数が多すぎます");
        } finally { if (connection!=null) connection.disconnect(); }
    }
    static String json(Context context,String url) throws Exception { return new String(get(context,url,3*1024*1024,"application/json"),StandardCharsets.UTF_8); }
    static String encode(String text) { try { return URLEncoder.encode(text,StandardCharsets.UTF_8.name()); } catch (UnsupportedEncodingException e) { throw new AssertionError(e); } }
    static long retrySeconds(String value) {
        if (value!=null) { try { return Math.max(1,Math.min(86400,Long.parseLong(value.trim()))); } catch (NumberFormatException ignored) { }
            try { return Math.max(1,Math.min(86400,(java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-System.currentTimeMillis()+999)/1000)); } catch (java.time.DateTimeException ignored) { } }
        return 60;
    }
    private static boolean allowed(String host) { return host.equals("lrclib.net") || host.equals("musicbrainz.org") || host.equals("coverartarchive.org") || host.equals("archive.org") || host.endsWith(".archive.org") || host.endsWith(".ytimg.com") || host.endsWith(".googleusercontent.com") || host.endsWith(".sndcdn.com"); }
}
