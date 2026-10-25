package jp.tonekeep.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.text.TextUtils;
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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.PopupMenu;
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
    private LinearLayout body, results, actions;
    private TextView status;
    private TextView selectionStatus;
    private LinearLayout searchTab, libraryTab, pitchTab;
    private boolean pitchMode;
    private String pitchSourceUri = "", pitchSourceName = "", pitchSnapshot = "";
    private int pitchSemitones;
    private boolean pitchFormants = true;
    private String pitchOutput = "flac";
    private AudioPlayer player;
    private PreviewAudio previewAudio;
    private boolean previewWanted;
    private int previewPosition, listenGeneration;
    private final Runnable previewChange = () -> requestPreview(previewPosition);
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
        @Override public void run() { if (pitchMode) renderPitchJobs(false); else if (library) renderDownloads(false); handler.postDelayed(this, 1000); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        downloads = new DownloadStore(this);
        previewAudio = new PreviewAudio(this);
        if (saved != null) {
            query = saved.getString("query", ""); selectedSource = saved.getInt("source");
            pitchSourceUri = saved.getString("pitch_uri", ""); pitchSourceName = saved.getString("pitch_name", "");
            pitchSemitones = saved.getInt("pitch_key"); pitchFormants = saved.getBoolean("pitch_formants", true); pitchOutput = saved.getString("pitch_output", "flac");
        }
        if (getLastNonConfigurationInstance() instanceof LookupState state) {
            items.addAll(state.items()); selected.addAll(state.selected()); collectionTitle = state.title(); searched = state.searched();
        }
        build();
        if (saved != null && saved.getBoolean("pitch_mode")) showPitch();
        else if ((saved != null && saved.getBoolean("library")) || getIntent().getBooleanExtra("library", false)) showLibrary();
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
        LinearLayout header = row(); header.setPadding(dp(20), dp(4), dp(8), dp(4));
        header.addView(text("ToneKeep", 22, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(iconButton(UiIcon.Kind.MORE, "アプリの設定", this::appMenu)); root.addView(header);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        body = column(); body.setPadding(dp(20), dp(14), dp(20), dp(20)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        actions = column(); actions.setPadding(dp(20), dp(6), dp(20), dp(6)); actions.setVisibility(View.GONE); root.addView(actions);
        player = new AudioPlayer(this, this::requestPreview, this::cancelListening, this::pauseListening); root.addView(player.view);
        LinearLayout nav = row(); nav.setPadding(dp(12), dp(6), dp(12), dp(6));
        searchTab = navItem(UiIcon.Kind.SEARCH, "検索", "検索画面", v -> showSearch());
        libraryTab = navItem(UiIcon.Kind.LIBRARY, "音源", "ライブラリ", v -> showLibrary());
        pitchTab = navItem(UiIcon.Kind.KEY, "キー", "キー変更", v -> showPitch());
        nav.addView(searchTab, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(libraryTab, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(pitchTab, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(nav); setContentView(root); showSearch();
    }
    private void showSearch() {
        actions.removeAllViews(); actions.setVisibility(View.GONE);
        library = false; pitchMode = false; tabs(); body.removeAllViews();
        add(body, text("検索", 25, TEXT, true), 0, 12);
        source = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"YouTube", "SoundCloud"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); source.setAdapter(adapter); source.setSelection(selectedSource);
        source.setContentDescription("曲名の検索先。URL入力ではこの設定は使いません"); add(body, source, 0, 12);
        input = new EditText(this); input.setSingleLine(true); input.setTextSize(16); input.setTextColor(TEXT); input.setHintTextColor(MUTED);
        input.setHint("曲名・URL"); input.setText(query); input.setPadding(dp(14), dp(8), dp(10), dp(8));
        input.setBackground(round(CARD, 14, BORDER)); input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2048)});
        input.setContentDescription("曲名、アーティスト名または音源ページのURL");
        LinearLayout searchRow = row(); searchRow.addView(input, new LinearLayout.LayoutParams(0, dp(52), 1));
        searchRow.addView(iconButton(UiIcon.Kind.SEARCH, "検索する", v -> search())); add(body, searchRow, 0, 8);
        add(body, compactButton("プレイリスト", UiIcon.Kind.LIST, v -> loadPlaylist()), 0, 8);
        input.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { search(); return true; } return false; });
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE); body.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        status = text("", 13, MUTED, false); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(body, status, 12, 10);
        results = column(); body.addView(results); renderResults();
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
                runOnUiThread(() -> { if (!alive(token)) return; items.addAll(found); searched = true; setBusy(false); if (!library && !pitchMode) renderResults(); });
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
                    if (!library && !pitchMode) { renderResults(); status.setText(getString(R.string.playlist_loaded, items.size(), collection.unavailable(), collection.duplicates())); }
                });
            } catch (Exception e) { failure(token, e); }
        });
    }
    private void renderResults() {
        if (library || pitchMode || results == null) return;
        results.removeAllViews();
        status.setText(busy ? "読み込み中…" : items.isEmpty() ? (searched ? "見つかりませんでした" : "") : items.size() + "曲");
        if (items.isEmpty() && !searched) {
            ImageView empty = new ImageView(this); empty.setImageDrawable(new UiIcon(UiIcon.Kind.SEARCH, BORDER));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(56), dp(56)); p.gravity = Gravity.CENTER; p.topMargin = dp(48); p.bottomMargin = dp(16); results.addView(empty, p);
            TextView hint = text("曲名・URLで検索", 14, MUTED, false); hint.setGravity(Gravity.CENTER); add(results, hint, 0, 8);
        }
        if (busy) add(results, button("読み込みを中止", false, v -> {
            ++generation; if (request != null) request.cancel(true); YtDlpEngine.cancel(processId); setBusy(false); renderResults();
        }), 0, 12);
        if (!items.isEmpty()) {
            selectionStatus = text(selected.size() + " / " + items.size(), 13, MINT, true);
            LinearLayout choices = row();
            choices.addView(selectionStatus, new LinearLayout.LayoutParams(0, -2, 1));
            choices.addView(button("全選択", false, v -> { for (MediaInfo.Track track : items) if (!track.live()) selected.add(track.url()); renderResults(); }), new LinearLayout.LayoutParams(0, -2, 1));
            choices.addView(iconButton(UiIcon.Kind.CLOSE, "選択を解除", v -> { selected.clear(); renderResults(); }));
            add(results, choices, 0, 8);
            if (!selected.isEmpty()) add(results, button("保存（" + selected.size() + "）", true, v -> {
                List<MediaInfo.Track> chosen = new ArrayList<>(); for (MediaInfo.Track track : items) if (selected.contains(track.url())) chosen.add(track);
                enqueueBatch(chosen, collectionTitle);
            }), 0, 14);
        }
        for (MediaInfo.Track track : items.subList(0, Math.min(items.size(), visibleCount))) {
            LinearLayout card = card();
            LinearLayout trackRow = row();
            CheckBox check = new CheckBox(this); check.setContentDescription(track.title() + "を選択"); check.setTextColor(MUTED);
            check.setMinHeight(dp(48)); check.setChecked(selected.contains(track.url())); check.setEnabled(!track.live());
            check.setOnCheckedChangeListener((v, checked) -> {
                if (checked) selected.add(track.url()); else selected.remove(track.url());
                renderResults();
            });
            trackRow.addView(check, new LinearLayout.LayoutParams(dp(40), dp(48)));
            LinearLayout names = column();
            add(names, clipped(track.title(), 16, TEXT, true, 2), 0, 4);
            if (!track.artist().isEmpty()) add(names, clipped(track.artist(), 12, MUTED, false, 1), 0, 4);
            add(names, text(track.site() + " · " + MediaInfo.duration(track.duration()), 11, MUTED, false), 0, 0);
            names.setOnClickListener(v -> details(track)); trackRow.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            trackRow.addView(iconButton(UiIcon.Kind.DOWNLOAD, "保存形式を選ぶ", v -> details(track)));
            card.addView(trackRow);
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
                runOnUiThread(() -> { if (!alive(token)) return; setBusy(false); if (!library && !pitchMode) { renderResults(); choose(full); } });
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
            toast("処理を開始しました");
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
        actions.removeAllViews(); actions.setVisibility(View.GONE);
        if (!library && !pitchMode && input != null) { query = input.getText().toString(); selectedSource = source.getSelectedItemPosition(); }
        library = true; pitchMode = false; tabs(); body.removeAllViews();
        add(body, text("ライブラリ", 25, TEXT, true), 0, 18);
        results = column(); body.addView(results); renderDownloads(true);
    }
    private void renderDownloads(boolean force) {
        List<DownloadStore.Entry> entries = downloads.recent(historyLimit);
        List<DownloadStore.Batch> batches = downloads.batches();
        String snapshot = batches.toString() + entries; if (!force && snapshot.equals(librarySnapshot)) return; librarySnapshot = snapshot;
        results.removeAllViews();
        for (DownloadStore.Batch batch : batches) {
            if (batch.total() < 2) continue;
            LinearLayout summary = card(), group = row(), names = column();
            add(names, clipped(batch.title(), 15, TEXT, true, 2), 0, 6);
            add(names, text("完了 " + batch.complete() + "/" + batch.total() + (batch.queued() > 0 ? " · 待機 " + batch.queued() : "") + (batch.failed() > 0 ? " · 失敗 " + batch.failed() : ""), 12, MUTED, false), 0, 0);
            group.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            if (batch.queued() + batch.running() > 0) group.addView(iconButton(UiIcon.Kind.CLOSE, "一括保存を中止",
                    v -> { downloads.cancelBatch(batch.id()); renderDownloads(true); }));
            if (batch.failed() > 0) group.addView(iconButton(UiIcon.Kind.RETRY, "失敗した曲を再試行",
                    v -> enqueueBatch(downloads.failed(batch.id()), batch.title() + "（再試行）")));
            summary.addView(group);
            add(results, summary, 0, 12);
        }
        if (entries.isEmpty()) {
            add(results, text("保存した音源はここに表示されます", 14, MUTED, false), 20, 16);
            add(results, compactButton("検索", UiIcon.Kind.SEARCH, v -> showSearch()), 0, 0);
        }
        for (DownloadStore.Entry entry : entries) {
            add(results, entryCard(entry), 0, 10);
        }
        int remaining = downloads.count() - entries.size();
        if (remaining > 0) add(results, button(getString(R.string.load_more_history, remaining), false,
                v -> { historyLimit += 80; renderDownloads(true); }), 0, 8);
    }
    private void retry(DownloadStore.Entry entry) {
        try { begin(downloads.retry(entry)); if (pitchMode) renderPitchJobs(true); else renderDownloads(true); } catch (RuntimeException e) { toast(DownloadService.error(e)); }
    }
    private void selectPitch(String uri, String title) {
        if (!pitchSourceUri.equals(uri) && previewWanted) { cancelListening(); player.stop(); }
        pitchSourceUri = uri; pitchSourceName = title; showPitch();
    }
    private void showPitch() {
        if (!library && !pitchMode && input != null) { query = input.getText().toString(); selectedSource = source.getSelectedItemPosition(); }
        library = false; pitchMode = true; tabs(); body.removeAllViews();
        LinearLayout heading = row(); heading.addView(text("キー変更", 25, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(iconButton(UiIcon.Kind.INFO, "キー変更について", v -> new AlertDialog.Builder(this).setTitle("キー変更")
                .setMessage("ボーカルと伴奏をまとめて変更します。テンポは変えず、原本を残して別ファイルに保存します。\n\n試聴は選んだ位置から20秒間。再生バーで位置を変えられます。キー・声質の変更後は短い準備を挟んで再生します。\n\nFLAC / WAVは24bit。MP3・AAC・Opus・Vorbisは非可逆圧縮、48kHz・ステレオで保存します。元の音質を超える変換ではありません。\n\nキー変更で音の質感が変わる場合があります。声質保持は楽器にも作用します。一時ファイル用の空き容量が必要です。処理は端末内で実行します。")
                .setPositiveButton("閉じる", null).show())); add(body, heading, 0, 8);
        LinearLayout sourceCard = card();
        sourceCard.setPadding(dp(12), dp(10), dp(8), dp(10));
        LinearLayout sourceTitle = row();
        sourceTitle.addView(clipped(pitchSourceUri.isEmpty() ? "音源を選択" : pitchSourceName, 16, TEXT, true, 2), new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton original = iconButton(UiIcon.Kind.PLAY, "元音源を再生", v -> playAudio(pitchSourceUri, pitchSourceName)); original.setEnabled(!pitchSourceUri.isEmpty());
        sourceTitle.addView(original); add(sourceCard, sourceTitle, 0, 6);
        LinearLayout pickers = row();
        pickers.addView(compactButton("ファイル", UiIcon.Kind.FOLDER, v -> {
            try {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(pick, 1067);
            } catch (ActivityNotFoundException e) { toast("ファイルを選ぶアプリが見つかりませんでした。"); }
        }), new LinearLayout.LayoutParams(0, -2, 1));
        pickers.addView(compactButton("ライブラリ", UiIcon.Kind.LIBRARY, v -> showLibrary()), new LinearLayout.LayoutParams(0, -2, 1));
        sourceCard.addView(pickers); add(body, sourceCard, 0, 8);
        TextView key = text(keyLabel(), 42, MINT, true); key.setGravity(Gravity.CENTER);
        SeekBar slider = new SeekBar(this); slider.setMax(24); slider.setProgress(pitchSemitones + 12); slider.setMinHeight(dp(48));
        slider.setContentDescription("キー変更。マイナス12からプラス12半音");
        Button export = button("保存", true, v -> {
            if (pitchSourceUri.isEmpty()) { toast("元の音声ファイルを選択してください。"); return; }
            if (pitchSemitones == 0) { toast("変更するキーを±ボタンやスライダーで選んでください。"); return; }
            try { begin(downloads.enqueuePitch(pitchSourceUri, pitchSourceName, new PitchSettings(pitchSemitones, pitchFormants, pitchOutput))); renderPitchJobs(true); }
            catch (RuntimeException e) { toast(DownloadService.error(e)); }
        });
        export.setContentDescription("テンポを保って保存");
        actions.removeAllViews(); add(actions, export, 0, 0); actions.setVisibility(View.VISIBLE);
        export.setEnabled(!pitchSourceUri.isEmpty() && pitchSemitones != 0);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                pitchSemitones = value - 12; key.setText(keyLabel());
                export.setEnabled(!pitchSourceUri.isEmpty() && pitchSemitones != 0);
                pitchEdited();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        LinearLayout steps = row();
        steps.addView(iconButton(UiIcon.Kind.MINUS, "半音下げる", v -> slider.setProgress(Math.max(0, slider.getProgress() - 1)))) ;
        steps.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
        steps.addView(iconButton(UiIcon.Kind.PLUS, "半音上げる", v -> slider.setProgress(Math.min(24, slider.getProgress() + 1))));
        add(body, steps, 0, 0);
        TextView unit = text("半音", 12, MUTED, false); unit.setGravity(Gravity.CENTER); add(body, unit, 0, 0);
        add(body, slider, 0, 6);
        CheckBox formants = new CheckBox(this); formants.setText(R.string.pitch_formants); formants.setTextColor(MUTED); formants.setMinHeight(dp(48));
        formants.setChecked(pitchFormants); formants.setOnCheckedChangeListener((v, checked) -> { pitchFormants = checked; pitchEdited(); }); add(body, formants, 0, 4);
        Button preview = compactButton("試聴 · 20秒", UiIcon.Kind.PLAY, v -> requestPreview(0));
        preview.setContentDescription("キー変更をプレビュー再生"); preview.setEnabled(!pitchSourceUri.isEmpty()); add(body, preview, 0, 6);
        Spinner output = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, PitchSettings.FORMAT_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); output.setAdapter(adapter); output.setSelection(Math.max(0, PitchSettings.FORMATS.indexOf(pitchOutput)));
        output.setContentDescription("キー変更後の保存形式");
        output.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { pitchOutput = PitchSettings.FORMATS.get(position); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        add(body, output, 0, 18);
        add(body, text("履歴", 17, TEXT, true), 0, 12);
        results = column(); body.addView(results); renderPitchJobs(true);
    }
    private void renderPitchJobs(boolean force) {
        if (!pitchMode || results == null) return;
        List<DownloadStore.Entry> jobs = downloads.pitchRecent(); String snapshot = jobs.toString();
        if (!force && snapshot.equals(pitchSnapshot)) return; pitchSnapshot = snapshot; results.removeAllViews();
        if (jobs.isEmpty()) add(results, text("まだ履歴はありません", 13, MUTED, false), 0, 8);
        for (DownloadStore.Entry entry : jobs) {
            add(results, entryCard(entry), 0, 10);
        }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 1067 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (!"content".equalsIgnoreCase(uri.getScheme())) { toast("端末の音声ファイルを選択してください。"); return; }
        if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { }
        }
        String name = "読み込んだ音声";
        try (android.database.Cursor cursor = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
        } catch (RuntimeException ignored) { }
        selectPitch(uri.toString(), name);
    }
    private void openFile(DownloadStore.Entry entry) {
        playAudio(entry.contentUri(), entry.title());
    }
    private void playAudio(String uri, String name) {
        cancelListening(); player.stop(); int token = listenGeneration;
        player.load(Uri.parse(uri), name, false, 0, 0, () -> {
            if (token != listenGeneration) return;
            player.busy(name);
            previewAudio.decode(uri, new PreviewAudio.Callback() {
                @Override public void ready(PreviewAudio.Result result) {
                    if (token == listenGeneration) player.load(Uri.fromFile(result.file()), name, false, 0, result.durationMs(), () -> listenFailed(token, "この音源を再生できません。"));
                }
                @Override public void failed(Exception error) { listenFailed(token, DownloadService.error(error)); }
            });
        });
    }
    private void cancelListening() { ++listenGeneration; previewWanted = false; handler.removeCallbacks(previewChange); previewAudio.cancel(); }
    private void requestPreview(int startMs) {
        if (pitchSourceUri.isEmpty()) return;
        cancelListening(); previewWanted = true; previewPosition = Math.max(0, startMs); int token = listenGeneration;
        String name = String.format(java.util.Locale.ROOT, "試聴 · %+d · %s", pitchSemitones, pitchSourceName);
        player.busy(name);
        previewAudio.preview(pitchSourceUri, new PitchSettings(pitchSemitones, pitchFormants, "wav"), previewPosition, new PreviewAudio.Callback() {
            @Override public void ready(PreviewAudio.Result result) {
                if (token != listenGeneration) return;
                previewPosition = result.offsetMs();
                player.load(Uri.fromFile(result.file()), name, true, result.offsetMs(), result.durationMs(), () -> listenFailed(token, "試聴を再生できません。"));
            }
            @Override public void failed(Exception error) { listenFailed(token, DownloadService.error(error)); }
        });
    }
    private void pitchEdited() {
        if (!previewWanted) return;
        boolean resume = player.isPlaying() || player.isPreparing();
        if (!player.isPreparing()) previewPosition = player.position();
        cancelListening(); player.stop();
        if (resume) { previewWanted = true; player.busy("試聴"); handler.postDelayed(previewChange, 500); }
    }
    private void listenFailed(int token, String message) { if (token == listenGeneration) { cancelListening(); player.stop(); toast(message); } }
    private void pauseListening() {
        if (player == null) return;
        player.pause();
        if (player.isPreparing() || handler.hasCallbacks(previewChange)) { cancelListening(); player.stop(); }
    }
    private void updateEngine() {
        if (busy || downloads.active()) { toast("検索や保存が完了してから更新してください。"); return; }
        int token = ++generation; setBusy(true); status.setText(R.string.engine_updating);
        request = worker.submit(() -> {
            try {
                String version = YtDlpEngine.update(this);
                runOnUiThread(() -> { if (!alive(token)) return; setBusy(false); if (!library && !pitchMode) status.setText(getString(R.string.engine_version, version == null ? "最新版" : version)); });
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
        runOnUiThread(() -> { if (!alive(token)) return; setBusy(false); if (!library && !pitchMode) status.setText(DownloadService.error(e)); else toast(DownloadService.error(e)); });
    }
    private void browse(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(MediaInfo.webUrl(url)))); }
        catch (RuntimeException e) { toast("リンクを開けませんでした。"); }
    }
    private String keyLabel() { return pitchSemitones > 0 ? "+" + pitchSemitones : Integer.toString(pitchSemitones); }
    private void appMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Wi-Fiでのみ取得").setCheckable(true).setChecked(downloads.prefs.getBoolean("wifi", false)).setOnMenuItemClickListener(item -> {
            downloads.prefs.edit().putBoolean("wifi", !item.isChecked()).apply(); return true;
        });
        menu.getMenu().add("yt-dlpを更新").setOnMenuItemClickListener(item -> { updateEngine(); return true; });
        menu.getMenu().add("ヘルプ").setOnMenuItemClickListener(item -> { about(); return true; });
        menu.getMenu().add("ライセンス").setOnMenuItemClickListener(item -> { licenses(); return true; }); menu.show();
    }
    private void entryMenu(View anchor, DownloadStore.Entry entry) {
        PopupMenu menu = new PopupMenu(this, anchor);
        if (entry.status().equals("complete")) {
            menu.getMenu().add("キー変更").setOnMenuItemClickListener(item -> { selectPitch(entry.contentUri(), entry.title()); return true; });
            menu.getMenu().add(entry.pitchJob() ? "同じ設定で保存" : "もう一度ダウンロード").setOnMenuItemClickListener(item -> { retry(entry); return true; });
        }
        if (!entry.pitchJob()) menu.getMenu().add("配信元を開く").setOnMenuItemClickListener(item -> { browse(entry.url()); return true; });
        menu.getMenu().add("詳細").setOnMenuItemClickListener(item -> { entryDetails(entry); return true; }); menu.show();
    }
    private void entryDetails(DownloadStore.Entry entry) {
        new AlertDialog.Builder(this).setTitle("処理の詳細").setMessage(entry.title() + "\n\n" + entry.quality() + "\n\n" + entry.message())
                .setPositiveButton("閉じる", null).show();
    }
    private LinearLayout entryCard(DownloadStore.Entry entry) {
        LinearLayout card = card(), line = row(), names = column();
        String title = entry.pitchJob() ? entry.title().replaceFirst(" \\[キー [^\\]]+\\]$", "") : entry.title();
        add(names, clipped(title, 15, TEXT, true, 2), 0, 5);
        String quality = entry.pitchJob() ? String.format(java.util.Locale.ROOT, "%+d · %s", entry.semitones(), new PitchSettings(entry.semitones(), entry.preserveFormants(), entry.outputFormat()).formatLabel())
                : entry.formatId().isEmpty() ? "最高音質" : entry.quality();
        add(names, clipped(quality, 12, MUTED, false, 1), 0, 5);
        String state = switch (entry.status()) { case "complete" -> "保存済み"; case "queued" -> "待機中"; case "running", "publishing" -> "処理中 " + entry.progress() + "%"; case "cancelled" -> "中止"; default -> "失敗"; };
        add(names, text(state, 12, entry.status().equals("failed") ? Color.rgb(249, 178, 143) : MINT, false), 0, 0);
        line.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        if (entry.active()) line.addView(iconButton(UiIcon.Kind.CLOSE, "処理を中止", v -> {
            DownloadService.cancel(this, entry.id()); if (pitchMode) renderPitchJobs(true); else renderDownloads(true);
        }));
        else if (entry.status().equals("complete")) line.addView(iconButton(UiIcon.Kind.PLAY, "アプリ内で再生", v -> openFile(entry)));
        else line.addView(iconButton(UiIcon.Kind.RETRY, "再試行", v -> retry(entry)));
        if (entry.status().equals("failed")) line.addView(iconButton(UiIcon.Kind.INFO, "エラーの詳細", v -> entryDetails(entry)));
        else line.addView(iconButton(UiIcon.Kind.MORE, "音源の操作", v -> entryMenu(v, entry)));
        card.addView(line);
        if (entry.active()) {
            ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setIndeterminate(entry.progress() == 0); progress.setProgress(entry.progress()); add(card, progress, 8, 0);
        }
        return card;
    }
    private void tabs() { tintNav(searchTab, !library && !pitchMode); tintNav(libraryTab, library); tintNav(pitchTab, pitchMode); }
    private void tintNav(LinearLayout item, boolean active) {
        int color = active ? MINT : MUTED;
        ((ImageView) item.getChildAt(0)).getDrawable().setTint(color);
        ((TextView) item.getChildAt(1)).setTextColor(color); item.setBackground(round(active ? CARD : BG, 16, active ? CARD : BG)); item.setSelected(active);
    }
    private LinearLayout navItem(UiIcon.Kind kind, String label, String description, View.OnClickListener click) {
        LinearLayout nav = column(); nav.setGravity(Gravity.CENTER); nav.setPadding(dp(6), dp(8), dp(6), dp(8)); nav.setMinimumHeight(dp(64));
        ImageView icon = new ImageView(this); icon.setImageDrawable(new UiIcon(kind, MUTED)); nav.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView text = text(label, 11, MUTED, false); text.setGravity(Gravity.CENTER); nav.addView(text);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        nav.setContentDescription(description); nav.setOnClickListener(click); nav.setFocusable(true); return nav;
    }
    private ImageButton iconButton(UiIcon.Kind kind, String description, View.OnClickListener click) {
        ImageButton button = new ImageButton(this); button.setImageDrawable(new UiIcon(kind, MUTED)); button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER); button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        button.setBackground(round(Color.TRANSPARENT, 12, Color.TRANSPARENT)); button.setContentDescription(description); button.setTooltipText(description); button.setOnClickListener(click); return button;
    }
    private Button compactButton(String label, UiIcon.Kind kind, View.OnClickListener click) {
        Button button = button(label, false, click); UiIcon icon = new UiIcon(kind, MUTED); icon.setBounds(0, 0, dp(18), dp(18));
        button.setCompoundDrawables(icon, null, null, null); button.setCompoundDrawablePadding(dp(6)); button.setMinWidth(0); button.setMinimumWidth(0); return button;
    }
    private TextView clipped(String value, int size, int color, boolean bold, int lines) {
        TextView view = text(value, size, color, bold); view.setMaxLines(lines); view.setEllipsize(TextUtils.TruncateAt.END); return view;
    }
    private void setBusy(boolean value) { busy = value; if (!library && !pitchMode && progress != null) progress.setVisibility(value ? View.VISIBLE : View.GONE); }
    private boolean alive(int token) { return !isFinishing() && !isDestroyed() && generation == token; }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout card() { LinearLayout c = column(); c.setPadding(dp(12), dp(14), dp(8), dp(14)); c.setBackground(round(CARD, 16, CARD)); return c; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setLineSpacing(dp(3), 1);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private Button button(String label, boolean primary, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(primary ? BG : MUTED);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setPadding(dp(12), dp(8), dp(12), dp(8));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{-android.R.attr.state_enabled}, round(BORDER, 12, BORDER));
        background.addState(new int[]{android.R.attr.state_pressed}, round(primary ? Color.rgb(151, 217, 177) : CARD, 12, BORDER));
        background.addState(new int[]{}, round(primary ? MINT : BG, 12, primary ? MINT : BORDER));
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{MUTED, primary ? BG : MUTED}));
        b.setBackground(background); b.setOnClickListener(listener); return b;
    }
    private GradientDrawable round(int fill, int radius, int stroke) { GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), stroke); return d; }
    private void add(LinearLayout parent, View child, int top, int bottom) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(top), 0, dp(bottom)); parent.addView(child, p); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putBoolean("library", library); out.putInt("source", source.getSelectedItemPosition());
        out.putString("query", input != null ? input.getText().toString() : query);
        out.putBoolean("pitch_mode", pitchMode); out.putString("pitch_uri", pitchSourceUri); out.putString("pitch_name", pitchSourceName);
        out.putInt("pitch_key", pitchSemitones); out.putBoolean("pitch_formants", pitchFormants); out.putString("pitch_output", pitchOutput);
    }
    @Override public Object onRetainNonConfigurationInstance() {
        return new LookupState(new ArrayList<>(items), new LinkedHashSet<>(selected), collectionTitle, searched);
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() {
        super.onPause(); handler.removeCallbacks(refresh);
        pauseListening();
    }
    @Override protected void onDestroy() {
        ++generation; if (request != null) request.cancel(true); YtDlpEngine.cancel(processId);
        worker.shutdownNow(); handler.removeCallbacksAndMessages(null);
        if (player != null) player.close(); if (previewAudio != null) previewAudio.close(); super.onDestroy();
    }
}
