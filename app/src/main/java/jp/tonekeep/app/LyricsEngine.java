package jp.tonekeep.app;

import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

final class LyricsEngine {
    private static final java.util.concurrent.ConcurrentHashMap<String,Session> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private static final class Session implements AutoCloseable {
        long handle;
        Session(long handle) { this.handle = handle; }
        synchronized void cancel() { if (handle != 0) NativeWhisper.cancel(handle); }
        @Override public synchronized void close() { if (handle != 0) NativeWhisper.close(handle); handle = 0; }
    }
    static void cancel(String id) { PitchEngine.cancel(id); Session session = sessions.get(id); if (session != null) session.cancel(); }
    /** Future local vocal separation can supply a derived file; the original must remain read-only. */
    interface AudioPreprocessor { File prepare(Context context, File source, File work, String id, BooleanSupplier active) throws Exception; }
    static final AudioPreprocessor MIX = (context, source, work, id, active) -> source;
    static Lyrics recognize(Context context, LyricsStore.Task task, File work, BooleanSupplier active, ModelManager.Progress progress, AudioPreprocessor preprocessor) throws Exception {
        ModelManager models = new ModelManager(context);
        progress.update(-1, "モデルを検証しています"); File model = models.verified(task.model(), active);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo(); context.getSystemService(ActivityManager.class).getMemoryInfo(memory);
        if (memory.lowMemory || memory.availMem < ModelManager.spec(task.model()).recommendedMemory()) throw new IOException("メモリの空きが不足しています。他のアプリを閉じるかsmallを使用してください。");
        if (!work.isDirectory() && !work.mkdirs()) throw new IOException("作業フォルダーを作成できません");
        progress.update(-1, "保存済み音源を読み込んでいます"); File source = new File(work, "source.input");
        if (!"content".equals(Uri.parse(task.uri()).getScheme())) throw new IOException("保存済み音源のURIが不正です");
        try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(task.uri())); FileOutputStream out = new FileOutputStream(source)) {
            if (in == null) throw new IOException("音源が見つかりません。削除やアクセス権を確認してください。");
            byte[] buffer = new byte[65536]; int n; long total = 0;
            while ((n = in.read(buffer)) != -1) {
                ModelManager.require(active); total += n;
                if (total > 2_000_000_000L || work.getUsableSpace() < n + 128L * 1024 * 1024) throw new IOException("一時音声用の容量が不足、または音源が2GBを超えています");
                out.write(buffer, 0, n);
            }
        }
        String sourceHash = ModelManager.hash(source, active);
        String probe = PitchEngine.execute(context, "libffprobe.so", List.of("-v", "error", "-protocol_whitelist", "file,pipe", "-show_entries", "format=duration", "-of", "json", source.getAbsolutePath()), task.id(), new File(work,"probe.log"), active, null);
        double seconds = new JSONObject(probe).getJSONObject("format").optDouble("duration", 0);
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 7200) throw new IOException("長さ不明、または2時間を超える音源には対応していません");
        if (work.getUsableSpace() < (long)(seconds * 32000) + 128L * 1024 * 1024) throw new IOException("解析用WAVの空き容量が不足しています");
        File prepared = preprocessor.prepare(context, source, work, task.id(), active), wav = new File(work,"analysis.wav");
        progress.update(-1, "16kHz・モノラル音声を準備しています");
        PitchEngine.execute(context,"libffmpeg.so", List.of("-nostdin", "-v", "error", "-y", "-protocol_whitelist", "file,pipe", "-i", prepared.getAbsolutePath(), "-map", "0:a:0", "-vn", "-af", "asetpts=PTS-STARTPTS", "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", wav.getAbsolutePath()), task.id(), new File(work,"decode.log"), active, null);
        ModelManager.require(active); progress.update(-1, "Whisperモデルを読み込んでいます");
        long handle = NativeWhisper.open(model.getAbsolutePath());
        Session session = new Session(handle); sessions.put(task.id(),session);
        try (PcmReader pcm = new PcmReader(wav)) {
            ModelManager.require(active); List<Lyrics.Line> lines = new ArrayList<>(); long completed = 0;
            while (completed < pcm.samples) {
                ModelManager.require(active); int count = (int)Math.min(30 * 16000, pcm.samples - completed); float[] audio = pcm.read(count);
                progress.update(completed==0?-1:(int)(completed*100/pcm.samples),String.format(Locale.JAPAN,"認識中 %s〜%s · 全体 %s",AudioPlayer.clock((int)(completed/16)),AudioPlayer.clock((int)((completed+count)/16)),AudioPlayer.clock((int)(pcm.samples/16))));
                double power = 0; for (float sample : audio) power += sample * sample;
                if (power / count > 1e-10) {
                    byte[] json = NativeWhisper.transcribe(handle, audio, task.japanese(), Math.min(4, Runtime.getRuntime().availableProcessors()));
                    ModelManager.require(active);
                    LyricsAlignment.append(lines, new JSONArray(new String(json, StandardCharsets.UTF_8)), completed / 16, (completed + count) / 16, LyricsAlignment.TOKEN_ESTIMATE);
                }
                completed += count;
                progress.update((int)(completed * 100 / pcm.samples), String.format(Locale.JAPAN, "解析済み %s / %s", AudioPlayer.clock((int)(completed / 16)), AudioPlayer.clock((int)(pcm.samples / 16))));
            }
            if (lines.isEmpty()) throw new IOException("信頼できる歌詞を検出できませんでした。mediumや言語設定を変更して再試行できます。");
            return new Lyrics(1, sourceHash, task.model(), task.japanese() ? "ja" : "auto", pcm.samples / 16, lines);
        } finally { sessions.remove(task.id(),session); session.close(); }
    }
    /** Reads RIFF chunks rather than assuming a 44-byte header; only 30 seconds enter memory. */
    static final class PcmReader implements AutoCloseable {
        private final RandomAccessFile file;
        final long samples;
        PcmReader(File wav) throws IOException {
            file = new RandomAccessFile(wav, "r"); long length = -1;
            try {
                if (!tag().equals("RIFF")) throw new IOException("RIFF WAVが必要です"); file.skipBytes(4);
                if (!tag().equals("WAVE")) throw new IOException("WAV形式が不正です"); boolean format = false;
                while (file.getFilePointer() + 8 <= file.length()) {
                    String chunk = tag(); long size = Integer.toUnsignedLong(Integer.reverseBytes(file.readInt())), start = file.getFilePointer();
                    if (size > file.length() - start) throw new IOException("WAVが途中で終了しています");
                    if (chunk.equals("fmt ")) {
                        if (size < 16 || Short.reverseBytes(file.readShort()) != 1 || Short.reverseBytes(file.readShort()) != 1 || Integer.reverseBytes(file.readInt()) != 16000) throw new IOException("16kHz mono PCMが必要です");
                        file.skipBytes(6); if (Short.reverseBytes(file.readShort()) != 16) throw new IOException("16bit PCMが必要です"); format = true;
                    } else if (chunk.equals("data")) { if (!format || size == 0 || size % 2 != 0) throw new IOException("PCMデータが不正です"); length = size / 2; break; }
                    file.seek(start + size + (size & 1));
                }
                if (length <= 0) throw new IOException("PCMデータがありません"); samples = length;
            } catch (IOException error) { file.close(); throw error; }
        }
        private String tag() throws IOException { byte[] b = new byte[4]; file.readFully(b); return new String(b, StandardCharsets.US_ASCII); }
        float[] read(int count) throws IOException { byte[] b = new byte[count * 2]; file.readFully(b); float[] pcm = new float[count]; for (int i = 0; i < count; i++) pcm[i] = (short)((b[i*2] & 255) | (b[i*2+1] << 8)) / 32768f; return pcm; }
        @Override public void close() throws IOException { file.close(); }
    }
    static void clean(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) clean(child); file.delete(); }
}
