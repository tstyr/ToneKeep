package jp.tonekeep.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class LyricsDialog implements AutoCloseable {
    private final Activity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Future<?> pending;
    private int generation;
    private boolean closed;
    private String process;
    private AlertDialog formDialog, progressDialog, resultDialog;
    LyricsDialog(Activity activity) { this.activity = activity; }
    void show(MediaInfo.Track track) {
        if (!track.lyrics().isBlank()) { show(track.title(), track.artist(), track.duration(), track.lyrics()); return; }
        cancel(); int token = generation; String id = "lyrics-" + java.util.UUID.randomUUID(); process = id;
        AlertDialog progress = new AlertDialog.Builder(activity).setTitle("曲名の情報を確認しています…")
                .setNegativeButton("中止", (d, w) -> cancel()).create();
        progressDialog = progress; progress.setOnCancelListener(d -> cancel()); progress.show();
        pending = worker.submit(() -> {
            MediaInfo.Track enriched;
            try { enriched = YtDlpEngine.details(activity, track.url(), id); }
            catch (Exception ignored) { enriched = track; }
            MediaInfo.Track result = enriched;
            handler.post(() -> {
                if (closed || token != generation) return;
                process = null; progress.dismiss(); show(result.title(), result.artist(), result.duration(), result.lyrics());
            });
        });
    }
    void show(String title, String artist, long duration, String embedded) {
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density); content.setPadding(padding, padding / 2, padding, padding / 2);
        EditText song = field(title, "曲名", "歌詞検索の曲名"), performer = field(artist, "アーティスト", "歌詞検索のアーティスト");
        TextView status = new TextView(activity); status.setText("曲名・アーティスト名で検索します。歌詞提供: LRCLIB");
        content.addView(song); content.addView(performer); content.addView(status);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("曲名・歌詞").setView(content)
                .setPositiveButton("歌詞を検索", null).setNegativeButton("閉じる", null)
                .setNeutralButton(embedded.isBlank() ? "" : "配信元の歌詞", (d, w) -> display(title, artist, embedded, false, "配信元のメタデータ")).create();
        formDialog = dialog; dialog.setOnDismissListener(d -> cancel()); dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(embedded.isBlank() ? android.view.View.GONE : android.view.View.VISIBLE);
        Runnable search = () -> {
            String name = song.getText().toString().trim(), musician = performer.getText().toString().trim();
            if (name.isEmpty()) { song.setError("曲名を入力してください。"); return; }
            cancel(); int token = generation; status.setText("歌詞を探しています…"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            pending = worker.submit(() -> {
                try {
                    var matches = LyricsClient.search(name, musician, duration);
                    handler.post(() -> {
                        if (closed || token != generation || !dialog.isShowing()) return;
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        if (matches.isEmpty()) { status.setText("一致する歌詞が見つかりません。曲名・アーティスト名を修正して再検索できます。"); return; }
                        status.setText("見つかりました。別の曲の場合は名前を修正してください。");
                        if (matches.size() == 1 && !musician.isEmpty()) show(matches.get(0));
                        else {
                            String[] labels = matches.stream().map(item -> item.title() + " — " + item.artist() + " · " + MediaInfo.duration((long) item.duration())).toArray(String[]::new);
                            resultDialog = new AlertDialog.Builder(activity).setTitle("一致する曲を選択").setItems(labels, (d, which) -> show(matches.get(which))).setNegativeButton("戻る", null).show();
                        }
                    });
                } catch (Exception error) {
                    handler.post(() -> {
                        if (closed || token != generation || !dialog.isShowing()) return;
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        status.setText("歌詞を取得できませんでした。通信状態を確認し、再検索してください。\n" + DownloadService.error(error));
                    });
                }
            });
        };
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> search.run());
        if (!title.isBlank() && !artist.isBlank() && embedded.isBlank()) search.run();
    }
    private EditText field(String value, String hint, String description) {
        EditText field = new EditText(activity); field.setSingleLine(true); field.setText(value); field.setHint(hint); field.setContentDescription(description);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)}); return field;
    }
    private void show(LyricsClient.Result result) { display(result.title(), result.artist(), result.lyrics(), result.instrumental(), "LRCLIB"); }
    private void display(String title, String artist, String lyrics, boolean instrumental, String source) {
        TextView text = new TextView(activity); text.setPadding(32, 16, 32, 16); text.setTextIsSelectable(true);
        text.setText("提供: " + source + "\n\n" + (instrumental ? "この曲は歌詞のない音源として登録されています。" : lyrics));
        ScrollView scroll = new ScrollView(activity); scroll.addView(text);
        resultDialog = new AlertDialog.Builder(activity).setTitle(title + (artist.isBlank() ? "" : " — " + artist)).setView(scroll)
                .setPositiveButton("閉じる", null).setNeutralButton("LRCLIB", (d, w) -> {
                    try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://lrclib.net"))); } catch (RuntimeException ignored) { }
                }).show();
    }
    private void cancel() { generation++; if (pending != null) pending.cancel(true); pending = null; if (process != null) { YtDlpEngine.cancel(process); process = null; } }
    @Override public void close() {
        closed = true; cancel(); handler.removeCallbacksAndMessages(null); worker.shutdownNow();
        for (AlertDialog dialog : new AlertDialog[]{formDialog, progressDialog, resultDialog}) if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }
}
