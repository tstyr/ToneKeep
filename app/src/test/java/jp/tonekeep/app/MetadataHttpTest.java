package jp.tonekeep.app;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLHandshakeException;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public class MetadataHttpTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private static final String LYRICS="https://lrclib.net/api/search?track_name=own";
    @Before public void reset() throws Exception {context.getSharedPreferences("metadata-http",0).edit().clear().commit();var field=MetadataHttp.class.getDeclaredField("gates");field.setAccessible(true);((Map<?,?>)field.get(null)).clear();}
    @Test public void shortOverloadWaitsThenRecoversWithIdentifiedHttpsGet() throws Exception {
        AtomicInteger calls=new AtomicInteger();List<Fake> connections=new ArrayList<>();
        byte[] result=MetadataHttp.get(context,LYRICS,100,"application/json",url->{Fake c=new Fake(url,calls.incrementAndGet()==1?503:200);c.headers.put("Retry-After","1");connections.add(c);return c;});
        assertEquals("[]",new String(result,StandardCharsets.UTF_8));assertEquals(2,calls.get());
        for(Fake c:connections){assertEquals("GET",c.getRequestMethod());assertTrue(c.getRequestProperty("User-Agent").contains("ToneKeep"));assertFalse(c.getInstanceFollowRedirects());assertTrue(c.disconnected);assertTrue(c.getConnectTimeout()>0&&c.getConnectTimeout()<=6000);}
    }
    @Test public void sustainedOverloadStopsAfterThreeTriesAndNamesTheProvider(){
        AtomicInteger calls=new AtomicInteger();MetadataHttp.HttpFailure error=assertThrows(MetadataHttp.HttpFailure.class,()->MetadataHttp.get(context,LYRICS,100,"application/json",url->{calls.incrementAndGet();Fake c=new Fake(url,503);c.headers.put("Retry-After","1");return c;}));
        assertEquals(3,calls.get());assertEquals(503,error.status);assertTrue(error.getMessage().contains("LRCLIB"));assertTrue(error.getMessage().contains("HTTP 503"));
    }
    @Test public void longRateLimitIsKeptAcrossCallsWithoutContactingTheServer(){
        AtomicInteger calls=new AtomicInteger();MetadataHttp.ConnectionFactory factory=url->{calls.incrementAndGet();Fake c=new Fake(url,429);c.headers.put("Retry-After","120");return c;};
        assertThrows(MetadataHttp.HttpFailure.class,()->MetadataHttp.get(context,LYRICS,100,"application/json",factory));
        MetadataHttp.HttpFailure error=assertThrows(MetadataHttp.HttpFailure.class,()->MetadataHttp.get(context,LYRICS,100,"application/json",factory));
        assertEquals(1,calls.get());assertTrue(error.retry>100);assertTrue(error.getMessage().contains("429"));
    }
    @Test public void slowArtworkDoesNotBlockAnUnrelatedLyricsProvider() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch imageStarted=new CountDownLatch(1),releaseImage=new CountDownLatch(1);
        try {
            Future<byte[]> image=pool.submit(()->MetadataHttp.get(context,"https://coverartarchive.org/release/own/front-1200",100,"image/*",url->new Fake(url,200){@Override public int getResponseCode() throws IOException {imageStarted.countDown();try{if(!releaseImage.await(8,TimeUnit.SECONDS))throw new IOException("test image timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}return 200;}}));
            assertTrue(imageStarted.await(3,TimeUnit.SECONDS));
            Future<byte[]> lyrics=pool.submit(()->MetadataHttp.get(context,LYRICS,100,"application/json",url->new Fake(url,200)));
            assertEquals("[]",new String(lyrics.get(3,TimeUnit.SECONDS),StandardCharsets.UTF_8));assertFalse(image.isDone());releaseImage.countDown();assertNotNull(image.get(3,TimeUnit.SECONDS));
        } finally {releaseImage.countDown();pool.shutdownNow();}
    }
    @Test public void unsafeRedirectAndOversizedBodiesAreRejectedAndClosed(){
        List<Fake> opened=new ArrayList<>();
        assertThrows(IOException.class,()->MetadataHttp.get(context,LYRICS,100,"application/json",url->{Fake c=new Fake(url,302);c.headers.put("Location","http://outside.invalid/audio");opened.add(c);return c;}));
        assertEquals(1,opened.size());assertTrue(opened.get(0).disconnected);
        assertThrows(MetadataHttp.ResponseTooLarge.class,()->MetadataHttp.get(context,LYRICS,1,"application/json",url->new Fake(url,200)));
    }
    @Test public void htmlAndTlsFailuresHaveActionableErrorsWithoutRetryingTls(){
        IOException html=assertThrows(IOException.class,()->MetadataHttp.get(context,LYRICS,100,"application/json",url->{Fake c=new Fake(url,200);c.headers.put("Content-Type","text/html");return c;}));assertTrue(html.getMessage().contains("JSON"));
        AtomicInteger calls=new AtomicInteger();IOException tls=assertThrows(IOException.class,()->MetadataHttp.get(context,LYRICS,100,"application/json",url->{calls.incrementAndGet();throw new SSLHandshakeException("test certificate failure");}));assertEquals(1,calls.get());assertTrue(tls.getMessage().contains("安全な通信"));
    }
    @Test public void expiredOverallBudgetClosesTheConnectionAndReportsTimeout(){
        List<Fake> opened=new ArrayList<>();IOException error=assertThrows(IOException.class,()->MetadataHttp.get(context,LYRICS,100,"application/json",url->{Fake c=new Fake(url,200){@Override public int getResponseCode(){ShadowSystemClock.advanceBy(Duration.ofSeconds(21));return 200;}};opened.add(c);return c;}));assertTrue(error.getMessage().contains("時間切れ"));assertTrue(opened.get(0).disconnected);
    }
    private static class Fake extends HttpURLConnection {
        final Map<String,String> headers=new HashMap<>();final int code;boolean disconnected;
        Fake(URL url,int code){super(url);this.code=code;headers.put("Content-Type","application/json");}
        @Override public int getResponseCode() throws IOException{return code;}
        @Override public String getHeaderField(String key){return headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(key)).map(Map.Entry::getValue).findFirst().orElse(null);}
        @Override public long getContentLengthLong(){return 2;}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream("[]".getBytes(StandardCharsets.UTF_8));}
        @Override public void disconnect(){disconnected=true;}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){connected=true;}
    }
}
