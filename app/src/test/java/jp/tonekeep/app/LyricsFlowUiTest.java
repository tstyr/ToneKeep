package jp.tonekeep.app;

import android.app.Dialog;
import android.content.Context;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import java.lang.reflect.Field;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=29) @SQLiteMode(SQLiteMode.Mode.LEGACY)
public class LyricsFlowUiTest {
    private ActivityController<MainActivity> controller;private MainActivity activity;private LyricsUi ui;private LyricsStore store;private DownloadStore.Entry entry;
    @Before public void setup() throws Exception {reset();controller=Robolectric.buildActivity(MainActivity.class).setup();activity=controller.get();ui=(LyricsUi)field(activity,"lyricsUi");DownloadStore downloads=new DownloadStore(activity);entry=downloads.enqueue(new MediaInfo.Track("own","自作曲","","https://example.invalid/own","self",4,false,List.of()),"","test");downloads.update(entry.id(),"complete","saved",100,"content://own/song","song.wav");entry=downloads.find(entry.id());store=new LyricsStore(activity);}
    @After public void close() throws Exception {controller.close();Field f=DownloadStore.class.getDeclaredField("instance");f.setAccessible(true);SQLiteOpenHelper db=(SQLiteOpenHelper)f.get(null);if(db!=null)db.close();f.set(null,null);}
    private void reset() throws Exception {Field f=DownloadStore.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,null);RuntimeEnvironment.getApplication().deleteDatabase("downloads.db");}
    @Test public void songMenuHasOneLyricsEntryInsteadOfSeparateAiActions(){PopupMenu menu=new PopupMenu(activity,new View(activity));ui.actions(menu,entry);assertEquals(2,menu.getMenu().size());assertEquals("歌詞",menu.getMenu().getItem(0).getTitle());assertEquals("曲情報・ジャケット",menu.getMenu().getItem(1).getTitle());}
    @Test public void emptyLyricsScreenStartsWithSearchAndPasteNotModelChoices() throws Exception {ui.open(entry);await("歌詞を探す");assertNotNull(button(root(),"歌詞を貼り付ける"));assertNull(button(root(),"AI歌詞生成"));assertNull(button(root(),"歌詞を再生成"));assertNull(button(root(),"small を取得・再開"));}
    @Test public void existingLyricsExposeDisplayAndTimingWithoutStartingInference() throws Exception {store.saveExternal(entry.id(),entry.contentUri(),LyricsTest.sample());ui.open(entry);await("歌詞を表示");assertNotNull(button(root(),"タイミングを調整する"));assertFalse(store.forAudio(entry.id(),entry.contentUri()).active());}
    @Test public void firstDownloadProgressIsVisibleAndCancelStopsThePendingRecognition() throws Exception {var prepared=store.prepare(entry.id(),entry.contentUri(),"small",true,"lyrics",null,true);store.update(prepared.download().id(),"running","取得 10MB / 488MB",2);ui.open(entry);await("中止");assertTrue(contains(root(),"認識データを取得しています"));button(root(),"中止").performClick();assertEquals("cancelled",store.find(prepared.work().id()).status());assertEquals("cancelled",store.find(prepared.download().id()).status());}
    @Test public void failedWorkShowsPersistentReasonAndRetryInsteadOfOnlyToast() throws Exception {var prepared=store.prepare(entry.id(),entry.contentUri(),"small",true,"lyrics",null,false);store.update(prepared.work().id(),"failed","通信が途切れました",-1);ui.open(entry);await("再試行");assertTrue(contains(root(),"通信が途切れました"));assertNotNull(button(root(),"歌詞を探す"));}
    @Test public void downloadNotificationOpensTheAssociatedSongsProgress() throws Exception {var prepared=store.prepare(entry.id(),entry.contentUri(),"small",true,"lyrics",null,true);activity.onNewIntent(new android.content.Intent(activity,MainActivity.class).putExtra("library",true).putExtra("lyrics_task",prepared.download().id()));await("中止");assertTrue(contains(root(),"自作曲"));assertTrue(contains(root(),"認識データを取得しています"));}
    @Test public void recreationRestoresTheProgressScreenWithoutDuplicatingWork() throws Exception {var prepared=store.prepare(entry.id(),entry.contentUri(),"small",true,"lyrics",null,true);ui.open(entry);await("中止");controller.recreate();activity=controller.get();ui=(LyricsUi)field(activity,"lyricsUi");await("中止");assertEquals(2,store.queued().size());assertEquals(prepared.work().id(),store.forAudio(entry.id(),entry.contentUri()).id());}
    @Test @Config(sdk=29,qualifiers="w360dp-h780dp-xxhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) public void renderTheActualAndroidLyricsScreensOnThePc() throws Exception {
        ui.open(entry);await("歌詞を探す");capture("01-find-lyrics");
        var prepared=store.prepare(entry.id(),entry.contentUri(),"small",true,"lyrics",null,true);store.update(prepared.download().id(),"running","取得済み 10MB / 488MB",2);ui.open(entry);await("中止");capture("02-download-progress");
        store.update(prepared.download().id(),"failed","通信が途切れました",-1);store.failModelDependents("small","failed","取得が中断しました。再試行で続きから取得できます。");ui.open(entry);await("再試行");capture("03-retry");
    }
    private void capture(String name) throws Exception {
        View view=root();int width=1080,height=2340;view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));java.io.File dir=new java.io.File("build/outputs/lyrics-flow-preview");assertTrue(dir.isDirectory()||dir.mkdirs());try(var out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();
    }
    private View root() throws Exception {Dialog panel=(Dialog)field(ui,"panel");assertNotNull(panel);return panel.getWindow().getDecorView();}
    private void await(String label) throws Exception {long end=System.nanoTime()+5_000_000_000L;while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();if(button(root(),label)!=null)return;Thread.sleep(15);}fail("Missing lyrics action: "+label);}
    private static boolean contains(View view,String text){if(view instanceof TextView t&&t.getText().toString().contains(text))return true;if(view instanceof ViewGroup group)for(int n=0;n<group.getChildCount();n++)if(contains(group.getChildAt(n),text))return true;return false;}
    private static Button button(View view,String text){if(view instanceof Button b&&b.getText().toString().equals(text))return b;if(view instanceof ViewGroup group)for(int n=0;n<group.getChildCount();n++){Button b=button(group.getChildAt(n),text);if(b!=null)return b;}return null;}
    private static Object field(Object target,String name) throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
}
