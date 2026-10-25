package jp.tonekeep.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 23, 22), CARD = Color.rgb(27, 37, 34), MINT = Color.rgb(183, 245, 207),
            TEXT = Color.rgb(240, 244, 237), MUTED = Color.rgb(160, 179, 168), BORDER = Color.rgb(47, 65, 56);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String processId = "lookup-" + UUID.randomUUID();
    private DownloadStore downloads;
    private EditText input;
    private Spinner source;
    private LinearLayout body, results;
    private TextView status;
    private TextView selectionStatus;
    private Button searchTab, libraryTab;
    private ProgressBar progress;
    private final List<MediaInfo.Track> items = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean library, busy, searched;
    private int generation, selectedSource, visibleCount = 40, historyLimit = 80;
    private String collectionTitle = "選択した音源";
    private String query = "", librarySnapshot = "";
    private Future<?> request;
    private record LookupState(List<MediaInfo.Track> items, Set<String> selected, String title, boolean searched) { }
    private final Runnable refresh = new Runnable() {
        @Override public void run() { if (library) renderDownloads(false); handler.postDelayed(this, 1000); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        downloads = new DownloadStore(this);
        if (saved != null) {
            query = saved.getString("query", ""); selectedSource = saved.getInt("source");
        }
        if (getLastNonConfigurationInstance() instanceof LookupState state) {
            items.addAll(state.items()); selected.addAll(state.selected()); collectionTitle = state.title(); searched = state.searched();
        }
        build();
        if ((saved != null && saved.getBoolean("library")) || getIntent().getBooleanExtra("library", false)) showLibrary();
        else acceptShare(getIntent());
        if (downloads.active()) {
            try { DownloadService.start(this); } catch (RuntimeException e) { toast("保存した音源の画面から再試行してください。"); }
        }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (intent.getBooleanExtra("library", false)) showLibrary(); else acceptShare(intent);
    }
    private void acceptShare(Intent intent) {
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("https?://[^\\s<>]+", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        if (matcher.find()) { query = matcher.group(); items.clear(); selected.clear(); searched = false; showSearch(); toast("共有されたURLを入力しました。検索またはプレイリスト全曲の読み込みができます。"); }
    }
    private void build() {
        LinearLayout root = column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header = row(); header.setPadding(dp(22), dp(16), dp(22), dp(12));
        header.addView(text("▥  ToneKeep", 23, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(text("POWERED BY yt-dlp", 10, MINT, true)); root.addView(header);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        body = column(); body.setPadding(dp(22), dp(14), dp(22), dp(24)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout nav = row(); nav.setPadding(dp(16), dp(8), dp(16), dp(8));
        searchTab = button("⌕  音楽を探す", false, v -> showSearch());
        libraryTab = button("↓  保存した音源", false, v -> showLibrary());
        nav.addView(searchTab, new LinearLayout.LayoutParams(0, dp(52), 1));
        nav.addView(libraryTab, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(nav); setContentView(root); showSearch();
    }
    private void showSearch() {
        library = false; tabs(); body.removeAllViews();
        add(body, text("いい音を、そのまま。", 30, TEXT, true), 0, 8);
        add(body, text("曲名から探す。URLから保存する。", 14, MUTED, false), 0, 20);
        LinearLayout feature = card();
        add(feature, text("BEST AVAILABLE AUDIO", 11, MINT, true), 0, 8);
        add(feature, text("配信元の最高品質を、手元に。", 18, TEXT, true), 0, 6);
        add(feature, text("Opus・M4A・FLACなど、入手できる音声を優先して保存します。", 12, MUTED, false), 0, 0);
        add(body, feature, 0, 20);
        source = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"曲名検索：YouTube", "曲名検索：SoundCloud"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); source.setAdapter(adapter); source.setSelection(selectedSource);
        source.setContentDescription("曲名の検索先。URL入力ではこの設定は使いません"); add(body, source, 0, 12);
        input = new EditText(this); input.setSingleLine(true); input.setTextSize(16); input.setTextColor(TEXT); input.setHintTextColor(MUTED);
        input.setHint("曲名・アーティスト・音源ページURL"); input.setText(query); input.setPadding(dp(16), dp(8), dp(16), dp(8));
        input.setBackground(round(CARD, 14, BORDER)); input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2048)});
        input.setContentDescription("曲名、アーティスト名または音源ページのURL"); body.addView(input, new LinearLayout.LayoutParams(-1, dp(56)));
        add(body, button("検索・URLを確認  →", true, v -> search()), 10, 8);
        add(body, button("プレイリスト全曲を読み込む", false, v -> loadPlaylist()), 0, 8);
        input.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { search(); return true; } return false; });
        add(body, text("プレイリストURLは「全曲を読み込む」を選択。取得後にまとめて保存できます。Spotify音源の直接保存には非対応です。", 12, MUTED, false), 0, 12);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE); body.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        status = text("", 13, MUTED, false); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(body, status, 12, 10);
        results = column(); body.addView(results); renderResults();
        add(body, button("yt-dlpを更新", false, v -> updateEngine()), 16, 8);
        add(body, button("音質・対応サイトについて ↗", false, v -> about()), 0, 0);
    }
    private void search() {
        if (busy) { toast("現在の処理が終わるまでお待ちください。"); return; }
        String value = input.getText().toString().trim();
        selectedSource = source.getSelectedItemPosition();
        try { MediaInfo.target(value, selectedSource == 1); } catch (IllegalArgumentException e) { toast(e.getMessage()); return; }
        query = value; items.clear(); selected.clear(); visibleCount = 40; collectionTitle = "検索結果：" + value;
        searched = false; int token = ++generation; boolean sc = selectedSource == 1;
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
        setBusy(true); renderResults(); status.setText("検索しています。初回は実行環境の準備に時間がかかります…");
        request = worker.submit(() -> {
            try {
                List<MediaInfo.Track> found = YtDlpEngine.search(this, value, sc, processId);
                runOnUiThread(() -> { if (!alive(token)) return; items.addAll(found); searched = true; setBusy(false); if (!library) renderResults(); });
            } catch (Exception e) { failure(token, e); }
        });
    }
    private void loadPlaylist() {
        if (busy) { toast("現在の処理が終わるまでお待ちください。"); return; }
        String url;
        try { url = MediaInfo.downloadPage(input.getText().toString().trim()); }
        catch (IllegalArgumentException e) { toast(e.getMessage()); return; }
        query = url; searched = false; items.clear(); selected.clear(); visibleCount = 40;
        int token = ++generation; setBusy(true); renderResults();
        status.setText(R.string.playlist_loading);
        request = worker.submit(() -> {
            try {
                MediaInfo.Collection collection = YtDlpEngine.playlist(this, url, processId);
                runOnUiThread(() -> {
                    if (!alive(token)) return;
                    items.addAll(collection.tracks()); collectionTitle = collection.title(); searched = true; setBusy(false);
                    if (!library) { renderResults(); status.setText(getString(R.string.playlist_loaded, items.size(), collection.unavailable(), collection.duplicates())); }
                });
            } catch (Exception e) { failure(token, e); }
        });
    }
    private void renderResults() {
        if (library || results == null) return;
        results.removeAllViews();
        status.setText(busy ? "処理中…" : items.isEmpty() ? (!searched ? "DISCOVER / 音楽を探す" : "音源が見つかりません。別の表記やURLでお試しください。") : getString(R.string.track_count, items.size()));
        if (items.isEmpty() && !searched) {
            add(results, text("聴きたい曲から、はじめよう。", 18, TEXT, true), 8, 8);
            add(results, text("曲名で検索するか、対応サイトのURLを貼り付けてください。音質と容量を確認してから保存できます。", 13, MUTED, false), 0, 8);
        }
        if (busy) add(results, button("読み込みを中止", false, v -> {
            ++generation; if (request != null) request.cancel(true); YtDlpEngine.cancel(processId); setBusy(false); renderResults();
        }), 0, 12);
        if (!items.isEmpty()) {
            selectionStatus = text(getString(R.string.selected_tracks, selected.size(), items.size()), 13, MINT, true);
            add(results, selectionStatus, 0, 10);
            LinearLayout choices = row();
            choices.addView(button("全選択", false, v -> { for (MediaInfo.Track track : items) if (!track.live()) selected.add(track.url()); renderResults(); }), new LinearLayout.LayoutParams(0, -2, 1));
            choices.addView(button("選択を解除", false, v -> { selected.clear(); renderResults(); }), new LinearLayout.LayoutParams(0, -2, 1));
            add(results, choices, 0, 8);
            add(results, button("選択した曲を一括保存  ↓", true, v -> {
                List<MediaInfo.Track> chosen = new ArrayList<>(); for (MediaInfo.Track track : items) if (selected.contains(track.url())) chosen.add(track);
                enqueueBatch(chosen, collectionTitle);
            }), 0, 14);
        }
        for (MediaInfo.Track track : items.subList(0, Math.min(items.size(), visibleCount))) {
            LinearLayout card = card();
            CheckBox check = new CheckBox(this); check.setText(R.string.select_for_batch); check.setTextColor(MUTED);
            check.setMinHeight(dp(48)); check.setChecked(selected.contains(track.url())); check.setEnabled(!track.live());
            check.setOnCheckedChangeListener((v, checked) -> {
                if (checked) selected.add(track.url()); else selected.remove(track.url());
                selectionStatus.setText(getString(R.string.selected_tracks, selected.size(), items.size()));
            });
            add(card, check, 0, 6);
            add(card, text(track.site() + " • " + MediaInfo.duration(track.duration()), 11, MINT, true), 0, 8);
            add(card, text(track.title(), 18, TEXT, true), 0, 6);
            add(card, text(track.artist().isEmpty() ? "投稿者情報なし" : track.artist(), 13, MUTED, false), 0, 12);
            add(card, button("音質・形式を確認  →", true, v -> details(track)), 0, 0);
            add(results, card, 0, 12);
        }
        if (items.size() > visibleCount) add(results, button(getString(R.string.load_more_tracks, items.size() - visibleCount), false,
                v -> { visibleCount += 40; renderResults(); }), 0, 8);
    }
    private void enqueueBatch(List<MediaInfo.Track> tracks, String title) {
        if (busy) { toast("現在の処理が終わるまでお待ちください。"); return; }
        if (tracks.isEmpty()) { toast("保存する曲を選択してください。"); return; }
        int token = ++generation; setBusy(true);
        request = worker.submit(() -> {
            try {
                DownloadStore.Added result = downloads.enqueueBatch(tracks, title);
                runOnUiThread(() -> {
                    boolean started = true;
                    if (result.added() > 0) {
                        try { DownloadService.start(getApplicationContext()); }
                        catch (RuntimeException e) { started = false; downloads.failBatch(result.batchId(), "保存を開始できませんでした。再試行してください。"); }
                    }
                    if (!alive(token)) return;
                    setBusy(false); if (started && result.added() > 0) askNotification();
                    toast(getString(R.string.bulk_result, result.added(), result.skipped())); showLibrary();
                });
            } catch (Exception e) { failure(token, e); }
        });
    }
    private void details(MediaInfo.Track track) {
        if (busy) return;
        int token = ++generation; setBusy(true); status.setText("入手できる音声形式を確認しています…");
        request = worker.submit(() -> {
            try {
                MediaInfo.Track full = YtDlpEngine.details(this, track.url(), processId);
                runOnUiThread(() -> { if (!alive(token)) return; setBusy(false); if (!library) { renderResults(); choose(full); } });
            } catch (Exception e) { failure(token, e); }
        });
    }
    private void choose(MediaInfo.Track track) {
        String[] labels = new String[track.formats().size() + 1]; labels[0] = "最高品質を自動選択（おすすめ）\n音声専用を優先・元の形式で保存";
        for (int i = 0; i < track.formats().size(); i++) labels[i + 1] = track.formats().get(i).label();
        int[] selected = {0};
        new AlertDialog.Builder(this).setTitle(track.title()).setSingleChoiceItems(labels, 0, (d, w) -> selected[0] = w)
                .setPositiveButton("ダウンロード", (d, w) -> {
                    String format = selected[0] == 0 ? "" : track.formats().get(selected[0] - 1).id();
                    String label = selected[0] == 0 ? "最高品質・自動選択" : track.formats().get(selected[0] - 1).label();
                    try { begin(downloads.enqueue(track, format, label)); }
                    catch (RuntimeException e) { toast(DownloadService.error(e)); }
                }).setNeutralButton("配信元 ↗", (d, w) -> browse(track.url())).setNegativeButton("閉じる", null).show();
    }
    private void begin(DownloadStore.Entry entry) {
        try {
            DownloadService.start(this);
            askNotification();
            toast("保存を開始しました。保存した音源で確認できます。");
        } catch (RuntimeException e) {
            downloads.update(entry.id(), "failed", "ダウンロードを開始できませんでした。再試行してください。", 0, null, null);
            toast(DownloadService.error(e));
        }
    }
    private void askNotification() {
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    && !downloads.prefs.getBoolean("notificationAsked", false)) {
                downloads.prefs.edit().putBoolean("notificationAsked", true).apply();
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 10);
            }
    }
    private void showLibrary() {
        if (!library && input != null) { query = input.getText().toString(); selectedSource = source.getSelectedItemPosition(); }
        library = true; tabs(); body.removeAllViews();
        add(body, text("あなたの音源。", 30, TEXT, true), 0, 8);
        add(body, text("保存先：ダウンロード / ToneKeep", 13, MUTED, false), 0, 18);
        CheckBox wifi = new CheckBox(this); wifi.setText(R.string.wifi_only); wifi.setTextSize(13); wifi.setTextColor(MUTED); wifi.setMinHeight(dp(48));
        wifi.setChecked(downloads.prefs.getBoolean("wifi", false));
        wifi.setOnCheckedChangeListener((v, checked) -> downloads.prefs.edit().putBoolean("wifi", checked).apply()); add(body, wifi, 0, 14);
        results = column(); body.addView(results); renderDownloads(true);
    }
    private void renderDownloads(boolean force) {
        List<DownloadStore.Entry> entries = downloads.recent(historyLimit);
        List<DownloadStore.Batch> batches = downloads.batches();
        String snapshot = batches.toString() + entries; if (!force && snapshot.equals(librarySnapshot)) return; librarySnapshot = snapshot;
        results.removeAllViews();
        for (DownloadStore.Batch batch : batches) {
            if (batch.total() < 2) continue;
            LinearLayout summary = card(); add(summary, text(batch.title(), 17, TEXT, true), 0, 8);
            add(summary, text(batch.description(), 12, MINT, false), 0, 8);
            if (batch.queued() + batch.running() > 0) add(summary, button("この一括保存を中止", false,
                    v -> { downloads.cancelBatch(batch.id()); renderDownloads(true); }), 0, 4);
            if (batch.failed() > 0) add(summary, button("失敗した曲だけ再試行", true,
                    v -> enqueueBatch(downloads.failed(batch.id()), batch.title() + "（再試行）")), 0, 0);
            add(results, summary, 0, 12);
        }
        if (entries.isEmpty()) {
            LinearLayout empty = card(); add(empty, text("↓", 46, MINT, true), 4, 8);
            add(empty, text("お気に入りの音を、ここに。", 18, TEXT, true), 0, 12);
            add(empty, button("音楽を探す  →", true, v -> showSearch()), 0, 0); add(results, empty, 4, 0);
        }
        for (DownloadStore.Entry entry : entries) {
            LinearLayout card = card(); add(card, text(entry.title(), 17, TEXT, true), 0, 6);
            add(card, text(entry.quality(), 12, MINT, false), 0, 8); add(card, text(entry.message(), 12, MUTED, false), 0, 8);
            if (entry.active()) {
                ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                p.setIndeterminate(entry.progress() == 0); p.setProgress(entry.progress()); add(card, p, 0, 8);
                add(card, button("ダウンロードを中止", false, v -> { DownloadService.cancel(this, entry.id()); renderDownloads(true); }), 0, 0);
            } else if (entry.status().equals("complete")) {
                add(card, button("音源を開く  ↗", true, v -> openFile(entry)), 0, 0);
                add(card, button("再ダウンロード", false, v -> retry(entry)), 6, 0);
            } else add(card, button("再試行", true, v -> retry(entry)), 0, 0);
            add(card, button("配信元を開く ↗", false, v -> browse(entry.url())), 6, 0); add(results, card, 0, 12);
        }
        int remaining = downloads.count() - entries.size();
        if (remaining > 0) add(results, button(getString(R.string.load_more_history, remaining), false,
                v -> { historyLimit += 80; renderDownloads(true); }), 0, 8);
    }
    private void retry(DownloadStore.Entry entry) {
        try { begin(downloads.retry(entry)); renderDownloads(true); } catch (RuntimeException e) { toast(DownloadService.error(e)); }
    }
    private void openFile(DownloadStore.Entry entry) {
        try {
            Uri uri = Uri.parse(entry.contentUri());
            try (android.os.ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "r")) {
                if (descriptor == null) throw new java.io.FileNotFoundException();
            }
            String mime = getContentResolver().getType(uri);
            Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime != null && mime.startsWith("audio/") ? mime : "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(open, "音源を開く"));
        } catch (ActivityNotFoundException e) { toast("この形式に対応する音楽プレーヤーをインストールしてください。"); }
        catch (Exception e) { toast("ファイルが見つかりません。再ダウンロードできます。"); }
    }
    private void updateEngine() {
        if (busy || downloads.active()) { toast("検索や保存が完了してから更新してください。"); return; }
        int token = ++generation; setBusy(true); status.setText(R.string.engine_updating);
        request = worker.submit(() -> {
            try {
                String version = YtDlpEngine.update(this);
                runOnUiThread(() -> { if (!alive(token)) return; setBusy(false); if (!library) status.setText(getString(R.string.engine_version, version == null ? "最新版" : version)); });
            } catch (Exception e) { failure(token, e); }
        });
    }
    private void about() {
        new AlertDialog.Builder(this).setTitle("広く探して、元の品質で保存")
                .setMessage("曲名検索：YouTube / SoundCloud\nURL入力：yt-dlpが取得できるサイト\n\n最高品質の音声を自動選択します。元の形式を優先し、映像付きファイルではFFmpegで音声を抽出します。MP3化やFLAC化による高音質化は行いません。容量やビットレートは配信元の情報から表示し、不明な値は推定しません。\n\n対応状況はサイトや地域によって変わります。取得に失敗した場合はyt-dlpの更新をお試しください。ログイン必須・DRM保護・配信中ライブには対応していません。保存・利用の権利があるコンテンツに使用してください。\n\nバックグラウンド保存は通知付きで実行します。Androidによる強制終了後は履歴から再試行できます。Wi-Fi限定は新規の保存に適用され、切断時は停止します。")
                .setPositiveButton("閉じる", null).setNeutralButton("対応サイト ↗", (d, w) -> browse("https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md"))
                .setNegativeButton("OSSライセンス", (d, w) -> licenses()).show();
    }
    private void licenses() {
        StringBuilder content = new StringBuilder();
        try {
            for (String name : new String[]{"THIRD_PARTY_NOTICES.md", "LICENSE"}) {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(getAssets().open(name), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line; while ((line = reader.readLine()) != null) content.append(line).append('\n');
                }
                content.append("\n\n");
            }
            new AlertDialog.Builder(this).setTitle("オープンソースライセンス").setMessage(content).setPositiveButton("閉じる", null).show();
        } catch (java.io.IOException e) { toast("ライセンス情報を読み込めませんでした。"); }
    }
    private void failure(int token, Exception e) {
        runOnUiThread(() -> { if (!alive(token)) return; setBusy(false); if (!library) status.setText(DownloadService.error(e)); else toast(DownloadService.error(e)); });
    }
    private void browse(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(MediaInfo.webUrl(url)))); }
        catch (RuntimeException e) { toast("リンクを開けませんでした。"); }
    }
    private void tabs() { searchTab.setTextColor(library ? MUTED : MINT); libraryTab.setTextColor(library ? MINT : MUTED); }
    private void setBusy(boolean value) { busy = value; if (!library && progress != null) progress.setVisibility(value ? View.VISIBLE : View.GONE); }
    private boolean alive(int token) { return !isFinishing() && !isDestroyed() && generation == token; }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout card() { LinearLayout c = column(); c.setPadding(dp(18), dp(18), dp(18), dp(14)); c.setBackground(round(CARD, 18, BORDER)); return c; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setLineSpacing(dp(3), 1);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private Button button(String label, boolean primary, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(primary ? BG : MUTED);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setBackground(round(primary ? MINT : BG, 12, primary ? MINT : BORDER)); b.setOnClickListener(listener); return b;
    }
    private GradientDrawable round(int fill, int radius, int stroke) { GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), stroke); return d; }
    private void add(LinearLayout parent, View child, int top, int bottom) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(top), 0, dp(bottom)); parent.addView(child, p); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putBoolean("library", library); out.putInt("source", source.getSelectedItemPosition());
        out.putString("query", input != null ? input.getText().toString() : query);
    }
    @Override public Object onRetainNonConfigurationInstance() {
        return new LookupState(new ArrayList<>(items), new LinkedHashSet<>(selected), collectionTitle, searched);
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(refresh); }
    @Override protected void onDestroy() {
        ++generation; if (request != null) request.cancel(true); YtDlpEngine.cancel(processId);
        worker.shutdownNow(); handler.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
