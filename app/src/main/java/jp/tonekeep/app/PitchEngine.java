package jp.tonekeep.app;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

final class PitchEngine {
    interface Progress { void update(int percent, String message); }
    private static final ConcurrentHashMap<String, Process> processes = new ConcurrentHashMap<>();
    private static ProcessBuilder process(Context context, String executable, List<String> args, File log) throws Exception {
        // FFmpeg also links to shared libraries supplied by the Python package
        // (including libc++_shared and libcrypto). Unpacking FFmpeg alone is insufficient.
        YtDlpEngine.init(context.getApplicationContext());
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        List<String> command = new ArrayList<>(); command.add(new File(nativeDir, executable).getAbsolutePath()); command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(log);
        File packages = new File(context.getNoBackupFilesDir(), "youtubedl-android/packages");
        builder.environment().put("LD_LIBRARY_PATH", new File(packages, "python/usr/lib").getAbsolutePath()
                + ":" + new File(packages, "ffmpeg/usr/lib").getAbsolutePath() + ":" + nativeDir.getAbsolutePath());
        builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
        return builder;
    }
    static File render(Context context, DownloadStore.Entry entry, File directory, BooleanSupplier active, Progress progress) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("作業フォルダーを作成できません。");
        PitchSettings settings = new PitchSettings(entry.semitones(), entry.preserveFormants(), entry.outputFormat());
        File input = new File(directory, "source.input"), intermediate = new File(directory, "pitched.wav");
        File output = new File(directory, "audio." + settings.format());
        progress.update(1, "音声ファイルを読み込んでいます…");
        try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(entry.url())); FileOutputStream out = new FileOutputStream(input)) {
            if (in == null) throw new java.io.IOException("元音源を開けません。ファイルを選び直してください。");
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) { requireActive(active); out.write(buffer, 0, n); }
        }
        requireActive(active);
        File log = new File(directory, "probe.log");
        String probe = execute(context, "libffprobe.so", List.of("-v", "error", "-protocol_whitelist", "file,pipe", "-select_streams", "a:0",
                "-show_entries", "stream=sample_rate,channels,duration:format=duration", "-of", "json", input.getAbsolutePath()),
                entry.id(), log, active, null);
        JSONObject metadata = new JSONObject(probe);
        JSONArray streams = metadata.optJSONArray("streams");
        if (streams == null || streams.length() == 0) throw new java.io.IOException("対応する音声トラックがありません。");
        JSONObject stream = streams.getJSONObject(0);
        int channels = stream.optInt("channels"), rate = stream.optInt("sample_rate");
        if (channels < 1 || channels > 32 || rate < 8000 || rate > 384000) throw new java.io.IOException("この音声形式・サンプルレートには対応していません。");
        double duration = stream.optDouble("duration", 0);
        if (duration <= 0 && metadata.optJSONObject("format") != null) duration = metadata.getJSONObject("format").optDouble("duration", 0);
        final double seconds = duration;
        progress.update(5, "曲全体のキーを変更しています…");
        execute(context, "libffmpeg.so", settings.shiftArgs(input, intermediate), entry.id(), new File(directory, "shift.log"), active,
                time -> progress.update(percent(time, seconds, 5, 78), "曲全体のキーを変更しています…"));
        requireActive(active); progress.update(80, "音量のピークを確認しています…");
        File peakLog = new File(directory, "peak.log");
        execute(context, "libffmpeg.so", settings.peakArgs(intermediate), entry.id(), peakLog, active,
                time -> progress.update(percent(time, seconds, 80, 87), "音量のピークを確認しています…"));
        double gain = PitchSettings.gainForPeak(read(peakLog, 512 * 1024));
        progress.update(88, "音声ファイルに書き出しています…");
        execute(context, "libffmpeg.so", settings.exportArgs(input, intermediate, output, gain), entry.id(), new File(directory, "export.log"), active,
                time -> progress.update(percent(time, seconds, 88, 98), "音声ファイルに書き出しています…"));
        requireActive(active);
        if (!output.isFile() || output.length() == 0) throw new java.io.IOException("書き出した音声を確認できませんでした。");
        return output;
    }
    interface TimeProgress { void update(double seconds); }
    static String execute(Context context, String executable, List<String> args, String id, File log, BooleanSupplier active, TimeProgress progress) throws Exception {
        requireActive(active);
        Process child = process(context, executable, args, log).start(); processes.put(id, child);
        StringBuilder output = new StringBuilder(); long last = 0;
        try {
            requireActive(active);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    requireActive(active);
                    if (output.length() < 65536) output.append(line).append('\n');
                    if (progress != null && line.startsWith("out_time_us=")) {
                        long now = SystemClock.elapsedRealtime();
                        if (now - last >= 600) {
                            try { progress.update(Double.parseDouble(line.substring(12)) / 1_000_000.0); last = now; } catch (NumberFormatException ignored) { }
                        }
                    }
                }
            }
            int code = child.waitFor(); requireActive(active);
            if (code != 0) {
                String detail = read(log, 32768);
                if (detail.contains("No such filter")) throw new java.io.IOException("音声処理機能を利用できません。APKを更新してください。");
                if (detail.contains("No space left")) throw new java.io.IOException("端末の空き容量が不足しています。");
                String stage = log.getName().replace(".log", "");
                throw new Failure(stage, code, detail);
            }
            return output.toString();
        } finally { processes.remove(id, child); if (child.isAlive()) child.destroyForcibly(); }
    }
    private static int percent(double time, double duration, int start, int end) {
        if (duration <= 0) return start;
        return start + (int) (Math.max(0, Math.min(1, time / duration)) * (end - start));
    }
    private static void requireActive(BooleanSupplier active) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || !active.getAsBoolean()) throw new InterruptedException("音声処理を中止しました。");
    }
    private static String read(File file, int maximum) throws Exception {
        try (InputStream in = new java.io.FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1 && out.size() < maximum) out.write(buffer, 0, Math.min(n, maximum - out.size()));
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    static void cancel(String id) { Process process = processes.get(id); if (process != null && process.isAlive()) process.destroyForcibly(); }
    static final class Failure extends java.io.IOException {
        Failure(String stage, int code, String detail) {
            super("音声処理に失敗しました（" + stage + "）\n" + safeDetail(code, detail));
        }
        private static String safeDetail(int code, String detail) {
            String clean = detail.replaceAll("https?://\\S+", "[URL]")
                    .replaceAll("/(?:data|storage|sdcard)/[^\\s\"']+", "[端末内ファイル]").trim();
            if (clean.length() > 1600) clean = clean.substring(clean.length() - 1600);
            return "FFmpeg exit " + code + "\n" + clean;
        }
    }
}
