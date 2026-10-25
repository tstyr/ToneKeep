package jp.tonekeep.app;

import android.content.Context;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.ffmpeg.FFmpeg;
import org.json.JSONObject;
import java.io.File;
import java.util.List;
import kotlin.Unit;

final class YtDlpEngine {
    interface Progress { void update(float percent, String line); }
    private static boolean ready;

    static synchronized void init(Context context) throws Exception {
        if (ready) return;
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = context.getResources().openRawResource(jp.tonekeep.app.R.raw.ytdlp)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hash = new StringBuilder();
        for (byte b : digest.digest()) hash.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        if (!hash.toString().equals("b7af77f686e95f7845ce8d09d21957718febb8b48b30555adb9dd2292ad7bf14"))
            throw new java.io.IOException("同梱エンジンの検証に失敗しました。APKを再インストールしてください。");
        YoutubeDL.getInstance().init(context.getApplicationContext());
        FFmpeg.getInstance().init(context.getApplicationContext());
        ready = true;
    }
    static YoutubeDLRequest base(String target) {
        return new YoutubeDLRequest(target).addOption("--ignore-config")
                .addOption("--no-playlist").addOption("--socket-timeout", 20)
                .addOption("--retries", 3).addOption("--fragment-retries", 3)
                .addOption("--no-warnings");
    }
    static List<MediaInfo.Track> search(Context context, String text, boolean soundcloud, String process) throws Exception {
        init(context);
        String target = MediaInfo.target(text, soundcloud);
        if (target.startsWith("http")) MediaInfo.downloadPage(target);
        YoutubeDLRequest request = base(target).addOption("--flat-playlist")
                .addOption("--playlist-end", 12).addOption("--dump-single-json").addOption("--skip-download");
        return MediaInfo.parseSearch(YoutubeDL.getInstance().execute(request, process).getOut());
    }
    static YoutubeDLRequest playlistRequest(String url) {
        return new YoutubeDLRequest(MediaInfo.downloadPage(url)).addOption("--ignore-config")
                .addOption("--yes-playlist").addOption("--flat-playlist")
                .addOption("--dump-single-json").addOption("--skip-download")
                .addOption("--ignore-errors").addOption("--socket-timeout", 20)
                .addOption("--retries", 3).addOption("--no-warnings");
    }
    static MediaInfo.Collection playlist(Context context, String url, String process) throws Exception {
        init(context);
        return MediaInfo.parseCollection(YoutubeDL.getInstance().execute(playlistRequest(url), process).getOut());
    }
    static MediaInfo.Track details(Context context, String url, String process) throws Exception {
        init(context);
        YoutubeDLRequest request = base(MediaInfo.webUrl(url)).addOption("--dump-single-json").addOption("--skip-download");
        MediaInfo.Track track = MediaInfo.parseTrack(new JSONObject(YoutubeDL.getInstance().execute(request, process).getOut()));
        if (track.live()) throw new IllegalArgumentException("配信中のライブには対応していません。公開済みの音源を選んでください。");
        return track;
    }
    static File download(Context context, DownloadStore.Entry entry, File directory, Progress progress) throws Exception {
        init(context);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("一時保存先を作成できません。");
        YoutubeDLRequest request = downloadRequest(entry.url(), entry.formatId(), directory);
        YoutubeDL.getInstance().execute(request, entry.id(), (percent, eta, line) -> {
            progress.update(percent, line); return Unit.INSTANCE;
        });
        File[] files = directory.listFiles((dir, name) -> name.startsWith("audio.") && !name.endsWith(".part")
                && !name.endsWith(".ytdl") && !name.endsWith(".json"));
        if (files == null || files.length != 1 || files[0].length() == 0)
            throw new java.io.IOException("音源ファイルの出力を確認できませんでした。");
        return files[0];
    }
    static YoutubeDLRequest downloadRequest(String url, String formatId, File directory) {
        return base(MediaInfo.webUrl(url)).addOption("-f", formatId.isEmpty() ? "bestaudio/best" : formatId)
                .addOption("--extract-audio").addOption("--audio-format", "best")
                .addOption("--no-mtime").addOption("--no-overwrites")
                .addOption("--match-filters", "!is_live")
                .addOption("-o", new File(directory, "audio.%(ext)s").getAbsolutePath());
    }
    static String update(Context context) throws Exception {
        init(context);
        YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE.INSTANCE);
        return YoutubeDL.getInstance().versionName(context);
    }
    static void cancel(String process) { YoutubeDL.getInstance().destroyProcessById(process); }
}
