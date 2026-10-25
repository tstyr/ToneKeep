package jp.tonekeep.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;

public final class DownloadService extends Service {
    private static final String CHANNEL = "tonekeep-downloads";
    private static final int NOTIFICATION = 42;
    private final ExecutorService queue = Executors.newSingleThreadExecutor();
    private final ExecutorService pitchQueue = Executors.newSingleThreadExecutor();
    private final CountDownLatch recovered = new CountDownLatch(1);
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();
    private final Set<String> processing = ConcurrentHashMap.newKeySet();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DownloadStore store;
    private int lastStartId;
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            for (String id : processing) {
                DownloadStore.Entry e = store.find(id);
                if (e == null) continue;
                if (e.active() && e.wifiOnly() && !onWifi(DownloadService.this))
                    store.update(id, "failed", "Wi-Fi接続が切れたため停止しました。再接続後に再試行してください。", e.progress(), null, null);
                e = store.find(id);
                if (e != null && !e.active()) { YtDlpEngine.cancel(id); PitchEngine.cancel(id); }
            }
            handler.postDelayed(this, 500);
        }
    };
    static void start(Context context) { context.startForegroundService(new Intent(context, DownloadService.class)); }
    static void cancel(Context context, String id) {
        new DownloadStore(context).update(id, "cancelled", "中止しました", 0, null, null);
        context.startService(new Intent(context, DownloadService.class).setAction("cancel").putExtra("id", id));
    }
    static boolean onWifi(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
    @Override public void onCreate() {
        super.onCreate();
        store = new DownloadStore(this);
        for (DownloadStore.Entry e : store.activeEntries()) if (e.status().equals("publishing") && !e.contentUri().isEmpty()) {
            try { getContentResolver().delete(Uri.parse(e.contentUri()), null, null); } catch (RuntimeException ignored) { }
        }
        store.recoverInterrupted();
        queue.submit(() -> { try { clean(new File(getFilesDir(), "staging")); } finally { recovered.countDown(); } });
        NotificationChannel channel = new NotificationChannel(CHANNEL, "音源の保存・キー変更", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        handler.post(watch);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        if (intent != null && "cancel".equals(intent.getAction())) {
            String id = intent.getStringExtra("id");
            if (id != null) {
                store.update(id, "cancelled", "中止しました", 0, null, null);
                YtDlpEngine.cancel(id);
                PitchEngine.cancel(id);
            }
        }
        if (!store.active() && scheduled.isEmpty()) { stopSelf(startId); return START_NOT_STICKY; }
        startForeground(NOTIFICATION, notification("音源処理の準備中", 0, firstProcessing()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        for (DownloadStore.Entry entry : store.queued()) {
            if (scheduled.add(entry.id())) (entry.pitchJob() ? pitchQueue : queue).submit(() -> runDownload(entry));
        }
        return START_NOT_STICKY;
    }
    private void runDownload(DownloadStore.Entry entry) {
        File directory = new File(getFilesDir(), "staging/" + entry.id());
        processing.add(entry.id());
        try {
            recovered.await();
            if (!store.update(entry.id(), "running", entry.pitchJob() ? "音声ファイルを準備しています…" : "音源を取得しています…", 0, null, null)) return;
            if (entry.wifiOnly() && !onWifi(this)) throw new IOException("Wi-Fiに接続してから再試行してください。");
            if (!entry.pitchJob()) YtDlpEngine.init(this);
            requireActive(entry.id());
            long[] lastUpdate = {0};
            YtDlpEngine.Progress callback = (percent, line) -> {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastUpdate[0] < 800) return;
                lastUpdate[0] = now;
                int p = Math.max(0, Math.min(99, (int) percent));
                store.update(entry.id(), "running", entry.pitchJob() ? line : (p >= 99 ? "音声を抽出しています…" : "音源を取得しています…"), p, null, null);
                if (android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                    getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(entry.title(), p, entry.id()));
            };
            File audio = entry.pitchJob() ? PitchEngine.render(this, entry, directory, () -> {
                DownloadStore.Entry current = store.find(entry.id()); return current != null && current.active();
            }, (percent, message) -> callback.update(percent, message))
                    : YtDlpEngine.download(this, entry, directory, callback);
            requireActive(entry.id());
            store.update(entry.id(), "publishing", "ダウンロードフォルダーに保存しています…", 99, null, null);
            publish(entry, audio);
        } catch (Exception e) {
            store.update(entry.id(), "failed", error(e), 0, null, null);
        } finally {
            clean(directory);
            processing.remove(entry.id());
            scheduled.remove(entry.id());
            handler.post(() -> {
                if (scheduled.isEmpty() && !store.active()) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf(lastStartId);
                }
            });
        }
    }
    private String firstProcessing() { for (String id : processing) return id; return null; }
    private void requireActive(String id) throws IOException {
        DownloadStore.Entry entry = store.find(id);
        if (entry == null || !entry.active()) throw new IOException("処理を停止しました。");
    }
    private void publish(DownloadStore.Entry entry, File audio) throws Exception {
        String ext = audio.getName().substring(audio.getName().lastIndexOf('.') + 1);
        String name = MediaInfo.filename(entry.title(), entry.id(), ext);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/ToneKeep");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri destination = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (destination == null) throw new IOException("保存先を作成できません。空き容量を確認してください。");
        boolean complete = false;
        try {
            if (!store.update(entry.id(), "publishing", "ダウンロードフォルダーに保存しています…", 99, destination.toString(), name)) return;
            try (FileInputStream input = new FileInputStream(audio); OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out == null) throw new IOException("保存先を開けません。");
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) { requireActive(entry.id()); out.write(buffer, 0, count); }
            }
            requireActive(entry.id());
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(destination, values, null, null);
            complete = store.update(entry.id(), "complete", "保存完了 • " + ext.toUpperCase(java.util.Locale.ROOT) + " • " + AudioFile.sizeLabel(audio.length()), 100, destination.toString(), name);
        } finally { if (!complete) getContentResolver().delete(destination, null, null); }
    }
    private Notification notification(String title, int percent, String id) {
        Intent open = new Intent(this, MainActivity.class).putExtra("library", true);
        PendingIntent content = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("ToneKeep").setContentText(getString(R.string.queue_notification, title, store.pending())).setContentIntent(content)
                .setOnlyAlertOnce(true).setOngoing(true).setProgress(100, percent, percent <= 0);
        if (id != null) {
            PendingIntent stop = PendingIntent.getService(this, id.hashCode(),
                    new Intent(this, DownloadService.class).setAction("cancel").putExtra("id", id),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "中止", stop).build());
        }
        return builder.build();
    }
    static String error(Exception e) {
        if (e instanceof PitchEngine.Failure) return e.getMessage();
        String text = e.getMessage();
        if (text == null || text.isEmpty()) return "取得に失敗しました。通信状態とURLを確認してください。";
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("sign in") || lower.contains("login") || lower.contains("private") || lower.contains("drm"))
            return "ログインや保護により取得できない音源です。公開されている別の音源を選んでください。";
        if (lower.contains("unsupported url")) return "このURLには未対応です。音源ページのURLを確認するか、yt-dlpを更新してください。";
        if (lower.contains("403") || lower.contains("429") || lower.contains("challenge"))
            return "配信元から取得できませんでした。時間を置くか、yt-dlpを更新して再試行してください。";
        if (lower.contains("no space")) return "端末の空き容量が不足しています。";
        String[] lines = text.split("\\R");
        String last = lines[lines.length - 1].replaceAll("https?://\\S+", "[URL]");
        return last.length() > 280 ? last.substring(0, 280) : last;
    }
    private void clean(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) clean(child);
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }
    @Override public void onTimeout(int startId, int fgsType) {
        for (DownloadStore.Entry entry : store.activeEntries()) {
            store.update(entry.id(), "failed", "Androidのバックグラウンド処理の時間上限に達しました。再試行してください。", 0, null, null);
            YtDlpEngine.cancel(entry.id());
            PitchEngine.cancel(entry.id());
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        for (String id : scheduled) {
            store.update(id, "failed", "処理が終了しました。再試行してください。", 0, null, null);
            YtDlpEngine.cancel(id);
            PitchEngine.cancel(id);
        }
        queue.shutdownNow();
        pitchQueue.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
