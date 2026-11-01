package jp.tonekeep.app;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.File;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Separate process isolates native OOM; SQLite state survives process termination. */
public final class LyricsService extends Service {
    private static final String CHANNEL = "tonekeep-lyrics";
    private static final int NOTIFICATION = 43;
    private static final Semaphore HEAVY = new Semaphore(1,true);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LyricsStore store;
    private volatile boolean destroyed;
    private volatile String current;
    private int lastStart;
    private PowerManager.WakeLock wake;
    private final Runnable watch = new Runnable() { @Override public void run() {
        if(!scheduled.isEmpty())store.pulse();
        String id = current;
        if (id != null) {
            LyricsStore.Task task = store.find(id);
            PowerManager power = getSystemService(PowerManager.class);
            if (task != null && task.active() && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_CRITICAL)
                store.update(id,"failed","端末の温度が高いため中断しました。冷ましてから再試行してください。",-1);
            task = store.find(id);
            if (task == null || !task.active()) abort(id);
        }
        if (!destroyed) handler.postDelayed(this,1000);
    } };
    static void start(Context context) { context.startForegroundService(new Intent(context, LyricsService.class)); }
    static void cancel(Context context, String id) {
        LyricsStore store=new LyricsStore(context);LyricsStore.Task task=store.find(id);
        store.update(id,"cancelled","中止しました。再試行できます。",-1);
        if(task!=null&&task.kind().equals("model"))store.failModelDependents(task.model(),"cancelled","認識データの取得を中止しました。再試行で続きから取得します。");
        context.startForegroundService(new Intent(context, LyricsService.class).setAction("cancel").putExtra("id",id));
    }
    @Override public void onCreate() {
        super.onCreate(); store = new LyricsStore(this); store.recover();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"AI歌詞・モデル取得",NotificationManager.IMPORTANCE_LOW));
        worker.submit(() -> LyricsEngine.clean(new File(getCacheDir(),"lyrics-work")));
        handler.post(watch);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStart = startId;
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        if (Build.VERSION.SDK_INT >= 35) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING;
        startForeground(NOTIFICATION, notification("AI歌詞の準備中",-1,null),type);
        if (intent != null && "cancel".equals(intent.getAction())) {
            String id = intent.getStringExtra("id");
            if (id != null) store.update(id,"cancelled","中止しました。再試行できます。",-1);
            if (id != null && id.equals(current)) abort(id);
        }
        for (LyricsStore.Task task : store.queued()) if (scheduled.add(task.id())) worker.submit(() -> run(task));
        finishIfIdle(); return START_NOT_STICKY;
    }
    private void run(LyricsStore.Task task) {
        File work = new File(getCacheDir(),"lyrics-work/" + task.id()); current = task.id();
        BooleanSupplier active = () -> { LyricsStore.Task t = store.find(task.id()); return !destroyed && t != null && t.active(); };
        boolean locked = false;
        try {
            HEAVY.acquire(); locked = true;
            ModelManager.require(active);
            if (!store.update(task.id(),"running","準備中",-1)) return;
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ToneKeep:Lyrics"); wake.acquire(6L * 60 * 60 * 1000);
            ModelManager.Progress progress = (percent,message) -> {
                if (store.update(task.id(),"running",message,percent)) getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(message,percent,task.id()));
            };
            if (task.kind().equals("model")) { new ModelManager(this).download(task.model(),active,progress); ModelManager.require(active); store.update(task.id(),"complete","モデルの準備ができました",100); }
            else if(task.kind().equals("align")) {
                Lyrics reference=store.input(task.id());if(reference==null)throw new java.io.IOException("同期する歌詞が見つかりません");
                DownloadStore.Entry audio=new DownloadStore(this).find(task.audioId());if(audio==null||!audio.contentUri().equals(task.uri()))throw new java.io.IOException("対象音源が見つかりません");
                progress.update(-1,"音源と歌詞を確認しています");SongDetails song=SongDetails.read(this,audio);ModelManager.require(active);
                if(!song.hash().equals(reference.sourceHash()))throw new java.io.IOException("元音源の内容が変わっています。歌詞を読み込み直してください。");
                Lyrics recognized=store.recognition(task.audioId(),task.uri(),task.model(),song.hash());
                if(recognized==null)recognized=LyricsEngine.recognize(this,task,work,active,progress,LyricsEngine.MIX);
                ModelManager.require(active);store.cacheRecognition(task,recognized);
                progress.update(-1,"歌詞本文と音声解析結果を照合しています");Lyrics result=ReferenceLyricsAlignment.align(reference,recognized,active);ModelManager.require(active);store.save(task,result);
            }
            else { Lyrics result = LyricsEngine.recognize(this,task,work,active,progress,LyricsEngine.MIX); ModelManager.require(active); store.save(task,result); }
        } catch (OutOfMemoryError error) { store.update(task.id(),"failed","メモリが不足しました。他のアプリを閉じ、smallで再試行してください。",-1); }
        catch (Exception | LinkageError error) { store.update(task.id(),"failed",error.getMessage() == null ? "解析に失敗しました。再試行してください。" : error.getMessage(),-1); }
        finally {
            if(task.kind().equals("model")){LyricsStore.Task result=store.find(task.id());if(result!=null&&!result.status().equals("complete"))store.failModelDependents(task.model(),result.status().equals("cancelled")?"cancelled":"failed","認識データの取得が終わっていません。再試行で取得を再開します。\n"+result.message());}
            if (wake != null && wake.isHeld()) wake.release(); wake = null; LyricsEngine.clean(work); if (locked) HEAVY.release(); current = null; scheduled.remove(task.id()); handler.post(this::finishIfIdle);
        }
    }
    private void finishIfIdle() { if (scheduled.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(lastStart); } }
    private Notification notification(String text, int percent, String id) {
        PendingIntent open = PendingIntent.getActivity(this,43,new Intent(this,MainActivity.class).putExtra("library",true).putExtra("lyrics_task",id),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_launcher).setContentTitle("ToneKeep · 歌詞処理").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(100,Math.max(0,percent),percent < 0);
        if (id != null) {
            Intent cancel = new Intent(this,LyricsService.class).setAction("cancel").putExtra("id",id);
            PendingIntent action = PendingIntent.getForegroundService(this,44,cancel,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null,"中止",action).build());
        }
        return builder.build();
    }
    @Override public void onTimeout(int startId, int fgsType) { stopWork(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    private void stopWork() {
        destroyed = true; String id = current;
        if (id != null) { store.update(id,"failed","処理が中断されました。再試行できます。",-1); abort(id); }
        worker.shutdownNow();
    }
    private void abort(String id) { LyricsEngine.cancel(id); }
    @Override public void onDestroy() { stopWork(); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
