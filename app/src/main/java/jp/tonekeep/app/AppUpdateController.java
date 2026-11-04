package jp.tonekeep.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class AppUpdateController implements AutoCloseable {
    static final int PICK_APK = 1068;
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> pending;
    private AlertDialog progressDialog, currentDialog;
    private volatile int generation;
    private volatile boolean closed;
    AppUpdateController(Activity activity) { this.activity = activity; }
    void show() {
        cancel(); int token = generation;
        AlertDialog progress = new AlertDialog.Builder(activity).setTitle("更新を確認しています…")
                .setMessage("現在: " + AppUpdate.installedVersion(activity)).setNegativeButton("中止", (d, w) -> cancel()).create();
        progressDialog = progress; progress.setOnCancelListener(d -> cancel()); progress.show();
        pending = worker.submit(() -> {
            try {
                long installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).getLongVersionCode();
                ReleaseUpdate update = ReleaseUpdate.find(WebJson.get(ReleaseUpdate.API), activity.getPackageName(), installed);
                handler.post(() -> {
                    if (closed || token != generation) return;
                    progress.dismiss();
                    if (update == null) {
                        currentDialog = new AlertDialog.Builder(activity).setTitle("アプリの更新")
                                .setMessage("アプリ内更新に対応した新しい版は見つかりませんでした。現在: " + AppUpdate.installedVersion(activity))
                                .setPositiveButton("閉じる", null).setNeutralButton("配布ページ", (d, w) -> releases())
                                .setNegativeButton("APKを選んで更新", (d, w) -> pick()).show();
                    } else currentDialog = new AlertDialog.Builder(activity).setTitle("新しいバージョンがあります")
                            .setMessage(AppUpdate.installedVersion(activity) + " → " + update.versionName() + "\n\n取得サイズ: " + AudioFile.sizeLabel(update.size()) + "\nログインは不要です。取得後にファイルと署名を検証します。")
                            .setPositiveButton("ダウンロードして更新", (d, w) -> download(update)).setNegativeButton("あとで", null).show();
                });
            } catch (Exception error) { handler.post(() -> { if (!closed && token == generation) { progress.dismiss(); error(error); } }); }
        });
    }
    private void releases() {
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdate.RELEASES))); } catch (RuntimeException error) { error(error); }
    }
    private void pick() {
        try { activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), PICK_APK); }
        catch (RuntimeException error) { error(error); }
    }
    private void download(ReleaseUpdate update) {
        cancel(); int token = generation;
        AlertDialog progress = new AlertDialog.Builder(activity).setTitle("更新APKを取得しています…")
                .setMessage("0%").setNegativeButton("中止", (d, w) -> cancel()).create();
        progressDialog = progress; progress.setOnCancelListener(d -> cancel()); progress.show();
        pending = worker.submit(() -> {
            try {
                AppUpdate.download(activity, update, () -> !closed && token == generation,
                        percent -> handler.post(() -> { if (!closed && token == generation && progress.isShowing()) progress.setMessage(percent + "%"); }));
                handler.post(() -> { if (!closed && token == generation) { progress.dismiss(); install(); } });
            } catch (Exception error) { handler.post(() -> { if (!closed && token == generation) { progress.dismiss(); error(error); } }); }
        });
    }
    void picked(Uri uri) { prepare(uri, false); }
    private void prepare(Uri uri, boolean install) {
        cancel(); int token = generation;
        AlertDialog progress = new AlertDialog.Builder(activity).setTitle("更新APKを確認しています…")
                .setMessage("ファイルのコピーと署名・バージョンの確認を行っています。")
                .setNegativeButton("中止", (d, w) -> cancel()).create();
        progressDialog = progress; progress.setOnCancelListener(d -> cancel()); progress.show();
        pending = worker.submit(() -> {
            try {
                java.io.File file = uri == null ? AppUpdate.pending(activity) : AppUpdate.prepare(activity, uri);
                String version = AppUpdate.validate(activity, file);
                handler.post(() -> {
                    if (closed || token != generation) return;
                    progress.dismiss();
                    if (install) install();
                    else currentDialog = new AlertDialog.Builder(activity).setTitle("更新をインストール")
                            .setMessage(AppUpdate.installedVersion(activity) + " → " + version + "\n\nAndroidの確認画面でインストールしてください。")
                            .setPositiveButton("インストール", (d, w) -> install()).setNegativeButton("キャンセル", (d, w) -> file.delete()).show();
                });
            } catch (Exception error) {
                handler.post(() -> { if (!closed && token == generation) { progress.dismiss(); error(error); } });
            }
        });
    }
    private void install() {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            currentDialog = new AlertDialog.Builder(activity).setTitle("Androidの更新許可")
                    .setMessage("設定画面でToneKeepからのアプリのインストールを許可してください。戻るとインストール画面を開きます。")
                    .setPositiveButton("設定を開く", (d, w) -> {
                        activity.getSharedPreferences("app-update", Activity.MODE_PRIVATE).edit().putBoolean("resume", true).apply();
                        try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))); }
                        catch (RuntimeException error) { error(error); }
                    }).setNegativeButton("キャンセル", null).show();
            return;
        }
        try { activity.startActivity(AppUpdate.installer(activity)); }
        catch (RuntimeException error) { error(error); }
    }
    void resumed() {
        var preferences = activity.getSharedPreferences("app-update", Activity.MODE_PRIVATE);
        if (preferences.getBoolean("resume", false)) {
            preferences.edit().remove("resume").apply();
            if (activity.getPackageManager().canRequestPackageInstalls() && AppUpdate.pending(activity).isFile()) prepare(null, true);
        }
    }
    private void error(Exception error) { currentDialog = new AlertDialog.Builder(activity).setTitle("更新できませんでした").setMessage(DownloadService.error(error)).setPositiveButton("閉じる", null).show(); }
    private void cancel() { generation++; if (pending != null) pending.cancel(true); pending = null; }
    @Override public void close() {
        closed = true; cancel(); handler.removeCallbacksAndMessages(null); worker.shutdownNow();
        if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
        if (currentDialog != null && currentDialog.isShowing()) currentDialog.dismiss();
    }
}
