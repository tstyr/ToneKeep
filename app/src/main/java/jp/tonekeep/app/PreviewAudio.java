package jp.tonekeep.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** One cancellable audio task at a time. Temporary audio never enters the saved library. */
final class PreviewAudio implements AutoCloseable {
    record Result(File file, int offsetMs, int durationMs) { }
    interface Callback { void ready(Result result); void failed(Exception error); }
    private final Context context;
    private final File root;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private String id;
    private Future<?> pending;
    private String cachedUri = "";
    private int duration;
    private boolean closed;
    PreviewAudio(Context context) {
        this.context = context.getApplicationContext();
        root = new File(context.getCacheDir(), "listen-" + UUID.randomUUID());
    }
    void preview(String uri, PitchSettings settings, int startMs, Callback callback) { submit(uri, settings, startMs, callback); }
    void decode(String uri, Callback callback) { submit(uri, null, 0, callback); }
    private void submit(String uri, PitchSettings settings, int startMs, Callback callback) {
        cancel(); if (closed) return;
        int token = generation.get(); String taskId = "listen-" + UUID.randomUUID(); id = taskId;
        pending = executor.submit(() -> {
            BooleanSupplier active = () -> generation.get() == token && !Thread.currentThread().isInterrupted();
            try {
                if (!root.isDirectory() && !root.mkdirs()) throw new IOException("一時フォルダーを作成できません。");
                // The executor is serial: no task removes a file still used by an older native process.
                for (File file : root.listFiles() == null ? new File[0] : root.listFiles())
                    if (!file.getName().equals("source.input")) Files.deleteIfExists(file.toPath());
                File source = new File(root, "source.input");
                if (!cachedUri.equals(uri) || !source.isFile()) {
                    cachedUri = "";
                    try (var input = context.getContentResolver().openInputStream(Uri.parse(uri)); var output = new FileOutputStream(source)) {
                        if (input == null) throw new IOException("音源を開けません。ファイルを選び直してください。");
                        byte[] buffer = new byte[65536]; int count;
                        while ((count = input.read(buffer)) != -1) { require(active); output.write(buffer, 0, count); }
                    }
                    require(active);
                    String probe = PitchEngine.execute(context, "libffprobe.so", List.of("-v", "error", "-protocol_whitelist", "file,pipe", "-show_entries", "format=duration", "-of", "json", source.getAbsolutePath()), taskId, new File(root, "probe.log"), active, null);
                    double seconds = new JSONObject(probe).getJSONObject("format").optDouble("duration", 0);
                    if (!Double.isFinite(seconds) || seconds <= 0 || seconds > Integer.MAX_VALUE / 1000.0) throw new IOException("音源の長さを取得できません。");
                    duration = (int) (seconds * 1000); cachedUri = uri;
                }
                int start = Math.max(0, Math.min(startMs, Math.max(0, duration - 1000)));
                File output = new File(root, "listen.wav");
                if (settings != null) {
                    File intermediate = new File(root, "float.wav"), peak = new File(root, "peak.log");
                    PitchEngine.execute(context, "libffmpeg.so", settings.previewArgs(source, intermediate, start / 1000.0), taskId, new File(root, "preview.log"), active, null);
                    PitchEngine.execute(context, "libffmpeg.so", settings.peakArgs(intermediate), taskId, peak, active, null);
                    double gain = PitchSettings.gainForPeak(new String(Files.readAllBytes(peak.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                    PitchEngine.execute(context, "libffmpeg.so", List.of("-nostdin", "-v", "error", "-y", "-protocol_whitelist", "file,pipe", "-i", intermediate.getAbsolutePath(), "-vn", "-af", "volume=" + Double.toString(gain), "-c:a", "pcm_s16le", "-ar", "48000", "-ac", "2", output.getAbsolutePath()), taskId, new File(root, "encode.log"), active, null);
                } else {
                    PitchEngine.execute(context, "libffmpeg.so", List.of("-nostdin", "-v", "error", "-y", "-protocol_whitelist", "file,pipe", "-i", source.getAbsolutePath(), "-map", "0:a:0", "-vn", "-c:a", "pcm_s16le", "-ar", "48000", "-ac", "2", "-rf64", "auto", output.getAbsolutePath()), taskId, new File(root, "decode.log"), active, null);
                }
                require(active);
                handler.post(() -> { if (!closed && generation.get() == token) callback.ready(new Result(output, settings == null ? 0 : start, duration)); });
            } catch (Exception error) {
                handler.post(() -> { if (!closed && generation.get() == token) callback.failed(error); });
            }
        });
    }
    void cancel() { generation.incrementAndGet(); if (id != null) PitchEngine.cancel(id); if (pending != null) pending.cancel(true); }
    private static void require(BooleanSupplier active) throws InterruptedException { if (!active.getAsBoolean()) throw new InterruptedException("中止しました"); }
    @Override public void close() {
        closed = true; cancel(); handler.removeCallbacksAndMessages(null);
        executor.execute(() -> { File[] files = root.listFiles(); if (files != null) for (File file : files) file.delete(); root.delete(); });
        executor.shutdown();
    }
}
