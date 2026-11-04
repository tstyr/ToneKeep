package jp.tonekeep.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

final class AppUpdate {
    static final String RELEASES = "https://github.com/tstyr/ToneKeep/releases";
    static File pending(Context context) { return new File(context.getCacheDir(), "updates/pending.apk"); }
    static String installedVersion(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException ignored) { return "不明"; }
    }
    static File prepare(Context context, Uri uri) throws Exception {
        if (!"content".equalsIgnoreCase(uri.getScheme())) throw new IOException("ファイル選択から更新APKを選んでください。");
        File file = pending(context), partial = new File(file.getParentFile(), "pending.part.apk");
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) throw new IOException("更新ファイルの保存先を作成できません。");
        try {
            try (var input = context.getContentResolver().openInputStream(uri); var output = new FileOutputStream(partial)) {
                if (input == null) throw new IOException("更新ファイルを開けません。");
                byte[] buffer = new byte[65536]; int count; long size = 0;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("更新を中止しました。");
                    size += count; if (size > 512L * 1024 * 1024) throw new IOException("更新APKが大きすぎます。");
                    output.write(buffer, 0, count);
                }
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("更新を中止しました。");
            validate(context, partial);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("更新を中止しました。");
            Files.move(partial.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return file;
        } finally { Files.deleteIfExists(partial.toPath()); }
    }
    static String validate(Context context, File file) throws Exception {
        return validateInfo(context, file).versionName;
    }
    private static PackageInfo validateInfo(Context context, File file) throws Exception {
        PackageManager manager = context.getPackageManager();
        PackageInfo candidate = manager.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null) throw new IOException("有効なAPKではありません。ダウンロードが完了したAPKを選択してください。");
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        UpdatePolicy.validate(installed.packageName, installed.getLongVersionCode(), signers(installed),
                candidate.packageName, candidate.getLongVersionCode(), signers(candidate));
        return candidate;
    }
    static File download(Context context, ReleaseUpdate update, java.util.function.BooleanSupplier active, java.util.function.IntConsumer progress) throws Exception {
        File file = pending(context), partial = new File(file.getParentFile(), "pending.part.apk");
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) throw new IOException("更新ファイルの保存先を作成できません。");
        boolean wifiOnly = new DownloadStore(context).prefs.getBoolean("wifi", false);
        javax.net.ssl.HttpsURLConnection connection = (javax.net.ssl.HttpsURLConnection) new java.net.URL(ReleaseUpdate.trustedUrl(update.url())).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
        try {
            if (wifiOnly && !DownloadService.onWifi(context)) throw new IOException("Wi-Fiに接続して更新してください。");
            if (connection.getResponseCode() != 200) throw new IOException("更新APKを取得できませんでした。");
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long size = 0; int lastPercent = -1;
            try (var input = connection.getInputStream(); var output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted() || !active.getAsBoolean()) throw new InterruptedException("更新を中止しました。");
                    if (wifiOnly && !DownloadService.onWifi(context)) throw new IOException("Wi-Fi接続が切れたため更新の取得を中止しました。");
                    size += count; if (size > update.size()) throw new IOException("更新APKのサイズが一致しません。");
                    digest.update(buffer, 0, count); output.write(buffer, 0, count);
                    int percent = (int) (size * 100 / update.size()); if (percent != lastPercent) { progress.accept(percent); lastPercent = percent; }
                }
            }
            StringBuilder hex = new StringBuilder(); for (byte b : digest.digest()) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            if (size != update.size() || !hex.toString().equals(update.sha256())) throw new IOException("更新APKのチェックサムが一致しません。");
            PackageInfo candidate = validateInfo(context, partial);
            if (candidate.getLongVersionCode() != update.versionCode()) throw new IOException("更新APKと更新情報のバージョンが一致しません。");
            if (Thread.currentThread().isInterrupted() || !active.getAsBoolean()) throw new InterruptedException("更新を中止しました。");
            Files.move(partial.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); return file;
        } finally { connection.disconnect(); Files.deleteIfExists(partial.toPath()); }
    }
    private static Set<String> signers(PackageInfo info) throws Exception {
        Set<String> result = new HashSet<>();
        if (info.signingInfo == null) return result;
        for (var signature : info.signingInfo.getApkContentsSigners()) {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            result.add(hex.toString());
        }
        return result;
    }
    static Intent installer(Context context) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".updates", pending(context));
        return new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
}
