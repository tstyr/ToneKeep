package jp.tonekeep.app;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import javax.net.ssl.HttpsURLConnection;

final class VocalEngine {
    static final String MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/UVR-MDX-NET-Voc_FT.onnx";
    static final String MODEL_SHA256 = "e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4";
    private static final ConcurrentHashMap<String, VocalSeparator> running = new ConcurrentHashMap<>();

    static boolean modelAvailable(Context context) { return modelFile(context).isFile(); }
    private static File modelFile(Context context) { return new File(context.getNoBackupFilesDir(), "vocal-model/" + MODEL_SHA256 + ".onnx"); }
    private static void require(BooleanSupplier active) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || !active.getAsBoolean()) throw new InterruptedException("ボーカル抽出を中止しました。");
    }
    private static File model(Context context, BooleanSupplier active, PitchEngine.Progress progress) throws Exception {
        File file = modelFile(context);
        progress.update(1, "分離モデルを確認しています…");
        if (file.isFile() && checksum(file, active).equals(MODEL_SHA256)) return file;
        boolean wifiOnly = new DownloadStore(context).prefs.getBoolean("wifi", false);
        if (wifiOnly && !DownloadService.onWifi(context)) throw new IOException("分離モデルの初回取得にはWi-Fi接続が必要です。");
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) throw new IOException("モデルの保存先を作成できません。");
        File temporary = new File(file.getParentFile(), "download.part");
        HttpsURLConnection connection = (HttpsURLConnection) new java.net.URL(MODEL_URL).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
        try {
            require(active);
            if (connection.getResponseCode() != 200) throw new IOException("分離モデルを取得できません。ネット接続を確認して再試行してください。");
            try (var input = connection.getInputStream(); var output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536]; int count; long downloaded = 0;
                while ((count = input.read(buffer)) != -1) {
                    require(active); downloaded += count;
                    if (wifiOnly && !DownloadService.onWifi(context)) throw new IOException("Wi-Fi接続が切れたためモデルの取得を停止しました。");
                    if (downloaded > 80L * 1024 * 1024) throw new IOException("分離モデルのサイズが一致しません。");
                    output.write(buffer, 0, count);
                    progress.update(2 + (int) Math.min(6, downloaded * 6 / (64L * 1024 * 1024)), "分離モデルを取得しています… " + downloaded / (1024 * 1024) + " MB");
                }
            }
            if (!checksum(temporary, active).equals(MODEL_SHA256)) throw new IOException("分離モデルの検証に失敗しました。再試行してください。");
            require(active); Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return file;
        } finally { connection.disconnect(); Files.deleteIfExists(temporary.toPath()); }
    }
    private static String checksum(File file, BooleanSupplier active) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) { require(active); digest.update(buffer, 0, count); }
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return result.toString();
    }
    static File render(Context context, DownloadStore.Entry entry, File directory, BooleanSupplier active, PitchEngine.Progress progress) throws Exception {
        File model = model(context, active, progress);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("作業フォルダーを作成できません。");
        File source = new File(directory, "source.input"), decoded = new File(directory, "source.f32"), vocals = new File(directory, "vocals.f32");
        progress.update(10, "音声ファイルを読み込んでいます…");
        try (var input = context.getContentResolver().openInputStream(Uri.parse(entry.url())); var output = new FileOutputStream(source)) {
            if (input == null) throw new IOException("元音源を開けません。ファイルを選び直してください。");
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) { require(active); output.write(buffer, 0, count); }
        }
        PitchEngine.execute(context, "libffmpeg.so", List.of("-nostdin", "-v", "error", "-y", "-protocol_whitelist", "file,pipe", "-i", source.getAbsolutePath(),
                "-map", "0:a:0", "-vn", "-ar", "44100", "-ac", "2", "-c:a", "pcm_f32le", "-f", "f32le", decoded.getAbsolutePath()), entry.id(), new File(directory, "decode.log"), active, null);
        require(active); progress.update(15, "ボーカルを分離しています…");
        try (VocalSeparator separator = new VocalSeparator(model)) {
            running.put(entry.id(), separator);
            try { separator.separate(decoded, vocals, VocalQuality.fromId(entry.vocalQuality()), active,
                    percent -> progress.update(15 + percent * 75 / 100, "ボーカルを分離しています（" + VocalQuality.fromId(entry.vocalQuality()).label + "）…")); }
            finally { running.remove(entry.id(), separator); }
        }
        require(active); progress.update(92, "音量のピークを確認しています…");
        File peak = new File(directory, "peak.log");
        PitchEngine.execute(context, "libffmpeg.so", List.of("-nostdin", "-v", "info", "-f", "f32le", "-ar", "44100", "-ac", "2", "-i", vocals.getAbsolutePath(),
                "-af", "astats=metadata=0:reset=0:measure_perchannel=none:measure_overall=Peak_level", "-f", "null", "-"), entry.id(), peak, active, null);
        double gain = PitchSettings.gainForPeak(new String(Files.readAllBytes(peak.toPath()), StandardCharsets.UTF_8));
        File output = new File(directory, "vocals.flac"); progress.update(96, "ボーカルをFLACに保存しています…");
        PitchEngine.execute(context, "libffmpeg.so", List.of("-nostdin", "-v", "error", "-y", "-f", "f32le", "-ar", "44100", "-ac", "2", "-i", vocals.getAbsolutePath(),
                "-af", "volume=" + gain, "-c:a", "flac", "-sample_fmt", "s32", "-bits_per_raw_sample", "24", output.getAbsolutePath()), entry.id(), new File(directory, "export.log"), active, null);
        require(active); return output;
    }
    static void cancel(String id) { VocalSeparator separator = running.get(id); if (separator != null) separator.cancel(); }
}
