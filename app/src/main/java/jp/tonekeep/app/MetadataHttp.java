package jp.tonekeep.app;

import android.content.Context;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLException;

/** Bounded HTTPS GETs. Each provider is serial; other providers remain independent. */
final class MetadataHttp {
    private static final long BUDGET_MS=20000;
    private static final Map<String,Gate> gates=new ConcurrentHashMap<>();
    private static final class Gate {final Semaphore lock=new Semaphore(1,true);long next;}
    interface ConnectionFactory {HttpURLConnection open(URL url) throws IOException;}
    static final class ResponseTooLarge extends IOException {ResponseTooLarge(){super("取得データが大きすぎます");}}
    static final class HttpFailure extends IOException {
        final int status;final long retry;
        HttpFailure(String host,int status,long retry){super(message(host,status,retry));this.status=status;this.retry=retry;}
    }
    static byte[] get(Context context,String address,int maximum,String accept) throws Exception {
        return get(context,address,maximum,accept,url->(HttpURLConnection)url.openConnection());
    }
    static byte[] get(Context context,String address,int maximum,String accept,ConnectionFactory factory) throws Exception {
        URL original=new URL(address);validate(original);String host=original.getHost().toLowerCase(Locale.ROOT);
        long deadline=SystemClock.elapsedRealtime()+BUDGET_MS;
        try {
            for(int attempt=0;;attempt++) {
                check(deadline);
                try {return request(context,original,maximum,accept,factory,deadline);}
                catch(HttpFailure failure) {
                    if(attempt>=2 || (failure.status!=429&&failure.status!=503) || failure.retry>3
                            || remaining(deadline)<failure.retry*1000+1500)throw failure;
                    // Honor Retry-After and avoid a synchronized retry wave.
                    Thread.sleep(failure.retry*1000+ThreadLocalRandom.current().nextInt(50,200));
                }
            }
        } catch(SocketTimeoutException e){throw new IOException(provider(host)+"への接続が時間切れになりました。接続状態を確認して再試行してください。",e);}
        catch(UnknownHostException e){throw new IOException(provider(host)+"の接続先を見つけられません。ネット接続や端末のDNS設定を確認してください。",e);}
        catch(SSLException e){throw new IOException(provider(host)+"との安全な通信を確立できません。端末の日時やネット接続を確認してください。",e);}
        catch(ConnectException e){throw new IOException(provider(host)+"へ接続できません。接続状態を確認して再試行してください。",e);}
    }
    private static byte[] request(Context context,URL original,int maximum,String accept,ConnectionFactory factory,long deadline) throws Exception {
        String host=original.getHost().toLowerCase(Locale.ROOT);Gate gate=gates.computeIfAbsent(host,h->new Gate());
        if(!gate.lock.tryAcquire(remaining(deadline),TimeUnit.MILLISECONDS))throw new SocketTimeoutException();
        try {
            check(deadline);long pause=gate.next-SystemClock.elapsedRealtime();if(pause>0)sleep(pause,deadline);
            var prefs=context.getSharedPreferences("metadata-http",Context.MODE_PRIVATE);
            long blocked=prefs.getLong(host,0)-System.currentTimeMillis();
            if(blocked>0){
                long seconds=(blocked+999)/1000;
                if(blocked>3000 || remaining(deadline)<blocked+1500)throw new HttpFailure(host,prefs.getInt(host+".status",429),seconds);
                sleep(blocked,deadline);
            }
            URL url=original;
            for(int redirects=0;redirects<5;redirects++) {
                check(deadline);HttpURLConnection connection=factory.open(url);
                try {
                    connection.setInstanceFollowRedirects(false);connection.setConnectTimeout((int)Math.min(6000,remaining(deadline)));connection.setReadTimeout((int)Math.min(7000,remaining(deadline)));
                    connection.setRequestProperty("User-Agent","ToneKeep/1.5.0 (https://github.com/tstyr/ToneKeep)");connection.setRequestProperty("Accept",accept);connection.setRequestProperty("Accept-Encoding","identity");
                    int code=connection.getResponseCode();
                    if(code==429||code==503){
                        long seconds=retrySeconds(connection.getHeaderField("Retry-After"));
                        prefs.edit().putLong(host,System.currentTimeMillis()+seconds*1000).putInt(host+".status",code).apply();
                        throw new HttpFailure(host,code,seconds);
                    }
                    if(code>=300&&code<400){String location=connection.getHeaderField("Location");if(location==null)throw new IOException("転送先がありません");URL target=new URL(url,location);validate(target);url=target;continue;}
                    if(code!=200)throw new HttpFailure(host,code,0);
                    String type=connection.getContentType();
                    if(accept.equals("application/json")&&type!=null&&!type.toLowerCase(Locale.ROOT).contains("json"))throw new IOException(provider(host)+"がJSON以外の応答を返しました。接続先とネット接続を確認してください。");
                    if(connection.getContentLengthLong()>maximum)throw new ResponseTooLarge();
                    try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                        byte[] buffer=new byte[16384];int n;
                        while(true){check(deadline);connection.setReadTimeout((int)Math.min(7000,remaining(deadline)));n=in.read(buffer);if(n==-1)break;if(out.size()+n>maximum)throw new ResponseTooLarge();out.write(buffer,0,n);}
                        return out.toByteArray();
                    }
                } finally {connection.disconnect();}
            }
            throw new IOException("転送回数が多すぎます");
        } finally {gate.next=SystemClock.elapsedRealtime()+(host.equals("musicbrainz.org")?1100:400);gate.lock.release();}
    }
    private static void sleep(long ms,long deadline) throws Exception {if(ms>=remaining(deadline))throw new SocketTimeoutException();Thread.sleep(ms);check(deadline);}
    private static long remaining(long deadline) throws SocketTimeoutException {long value=deadline-SystemClock.elapsedRealtime();if(value<=0)throw new SocketTimeoutException();return value;}
    private static void check(long deadline) throws Exception {if(Thread.currentThread().isInterrupted())throw new InterruptedException("中止しました");remaining(deadline);}
    private static void validate(URL url) throws IOException {if(!url.getProtocol().equals("https")||!allowed(url.getHost().toLowerCase(Locale.ROOT)))throw new IOException("対応していない情報取得先または転送先です（HTTPSのみ対応）");}
    private static String message(String host,int code,long retry){
        String name=provider(host),wait=retry>0?retry+"秒以上待ってから再試行してください。":"しばらく待ってから再試行してください。";
        if(code==503)return name+"が一時的に混雑・停止しています（HTTP 503）。"+wait;
        if(code==429)return name+"のアクセス制限中です（HTTP 429）。"+wait;
        if(code==404)return name+"に対応する情報がありません（HTTP 404）。";
        if(code==403)return name+"への接続が拒否されました（HTTP 403）。接続状態を確認してください。";
        return name+"から情報を取得できませんでした（HTTP "+code+"）。";
    }
    private static String provider(String host){if(host.equals("lrclib.net"))return "歌詞サービス LRCLIB";if(host.equals("musicbrainz.org"))return "曲情報サービス MusicBrainz";if(host.equals("coverartarchive.org")||host.equals("archive.org")||host.endsWith(".archive.org"))return "ジャケット配信元";return "画像配信元";}
    static String json(Context context,String url) throws Exception {return new String(get(context,url,3*1024*1024,"application/json"),StandardCharsets.UTF_8);}
    static String encode(String text){try{return URLEncoder.encode(text,StandardCharsets.UTF_8.name());}catch(UnsupportedEncodingException e){throw new AssertionError(e);}}
    static long retrySeconds(String value){
        if(value!=null){try{return Math.max(1,Math.min(86400,Long.parseLong(value.trim())));}catch(NumberFormatException ignored){}
            try{return Math.max(1,Math.min(86400,(java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-System.currentTimeMillis()+999)/1000));}catch(java.time.DateTimeException ignored){}}
        return 60;
    }
    private static boolean allowed(String host){return host.equals("lrclib.net")||host.equals("musicbrainz.org")||host.equals("coverartarchive.org")||host.equals("archive.org")||host.endsWith(".archive.org")||host.endsWith(".ytimg.com")||host.endsWith(".googleusercontent.com")||host.endsWith(".sndcdn.com");}
}
