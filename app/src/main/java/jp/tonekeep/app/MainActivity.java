package jp.tonekeep.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 23, 22), CARD = Color.rgb(27, 37, 34), MINT = Color.rgb(183, 245, 207),
            TEXT = Color.rgb(240, 244, 237), MUTED = Color.rgb(160, 179, 168), BORDER = Color.rgb(47, 65, 56);
    private final ArchiveClient client = new ArchiveClient();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DownloadStore downloads;
    private EditText input;
    private LinearLayout body, results;
    private TextView status;
    private Button searchTab, libraryTab, searchButton;
    private ProgressBar progress;
    private ScrollView scroll;
    private final List<ArchiveClient.Item> items = new ArrayList<>();
    private boolean library = false, losslessOnly = false, busy = false;
    private int generation = 0, page = 0, total = 0;
    private String currentQuery = "";
    private Future<?> request;
    private final Runnable refresh = new Runnable() {
        public void run() {
            if (library) renderDownloads();
            handler.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        downloads = new DownloadStore(this);
        if (saved != null) {
            library = saved.getBoolean("library");
            losslessOnly = saved.getBoolean("lossless");
            currentQuery = saved.getString("query", "");
        }
        boolean restoreLibrary = library;
        build();
        if (restoreLibrary) showLibrary();
        else if (!currentQuery.isEmpty()) search(false);
    }

    private void build() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout header = row();
        header.setPadding(dp(22), dp(16), dp(22), dp(12));
        TextView brand = text("▥  ToneKeep", 23, TEXT, true);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView mark = text("ORIGINAL AUDIO", 10, MINT, true);
        mark.setLetterSpacing(.1f);
        header.addView(mark);
        root.addView(header);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = column();
        body.setPadding(dp(22), dp(14), dp(22), dp(24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout nav = row();
        nav.setPadding(dp(16), dp(8), dp(16), dp(8));
        searchTab = button("⌕  音楽を探す", false, v -> showSearch());
        libraryTab = button("↓  保存した音源", false, v -> showLibrary());
        nav.addView(searchTab, new LinearLayout.LayoutParams(0, dp(52), 1));
        nav.addView(libraryTab, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(nav);
        setContentView(root);
        showSearch();
    }

    private void showSearch() {
        library = false;
        tabs();
        body.removeAllViews();
        add(body, text("いい音を、そのまま。", 30, TEXT, true), 0, 8);
        add(body, text("好きな一曲を探して、手元に残そう。", 14, MUTED, false), 0, 20);
        LinearLayout feature = column();
        feature.setBackground(round(CARD, 20, BORDER));
        feature.setPadding(dp(18), dp(18), dp(18), dp(18));
        add(feature, text("LOSSLESS FIRST", 11, MINT, true), 0, 8);
        add(feature, text("配信元にある最高品質を選択", 18, TEXT, true), 0, 6);
        add(feature, text("FLACなどを優先。変換せず元のファイルを保存します。", 12, MUTED, false), 0, 0);
        add(body, feature, 0, 22);

        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setHint("曲名・アーティスト名");
        input.setText(currentQuery);
        input.setPadding(dp(16), dp(8), dp(16), dp(8));
        input.setBackground(round(CARD, 14, BORDER));
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(160)});
        input.setContentDescription("曲名またはアーティスト名を入力");
        body.addView(input, new LinearLayout.LayoutParams(-1, dp(56)));
        searchButton = button("音源を検索  →", true, v -> search(false));
        add(body, searchButton, 10, 10);
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) { search(false); return true; }
            return false;
        });
        LinearLayout chips = row();
        for (String label : new String[]{"Moonlight", "Jazz", "Piano"}) {
            Button chip = button(label, false, v -> { input.setText(label); search(false); });
            chips.addView(chip, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        add(body, chips, 0, 6);
        Switch filter = toggle("音源選択でロスレスのみ表示", losslessOnly);
        filter.setOnCheckedChangeListener((v, checked) -> { losslessOnly = checked; renderResults(); });
        add(body, filter, 0, 12);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        body.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        status = text("", 13, MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(body, status, 16, 8);
        results = column();
        body.addView(results);
        add(body, text("Internet ArchiveのCCライセンス・パブリックドメイン音源を検索。市販曲が見つからない場合があります。", 12, MUTED, false), 18, 6);
        Button about = button("音質とライセンスについて ↗", false, v -> about());
        add(body, about, 0, 0);
        renderResults();
    }

    private void search(boolean more) {
        String query = more ? currentQuery : input.getText().toString().trim();
        try { ArchiveClient.query(query); } catch (IllegalArgumentException e) { toast(e.getMessage()); return; }
        if (request != null) request.cancel(true);
        int token = ++generation;
        int nextPage = more ? page + 1 : 1;
        if (!more) { items.clear(); total = 0; page = 0; }
        currentQuery = query;
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
        setBusy(true);
        renderResults();
        status.setText(getString(R.string.searching, query));
        request = worker.submit(() -> {
            try {
                ArchiveClient.Page response = client.search(query, nextPage);
                runOnUiThread(() -> {
                    if (!alive(token)) return;
                    items.addAll(response.items()); total = response.total(); page = nextPage;
                    setBusy(false);
                    if (!library) renderResults();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!alive(token)) return;
                    setBusy(false);
                    if (!library) {
                        renderResults();
                        status.setText(message(e));
                        add(results, button("再試行", false, v -> search(more)), 4, 0);
                    }
                });
            }
        });
    }

    private void renderResults() {
        if (library || results == null) return;
        results.removeAllViews();
        if (items.isEmpty()) {
            status.setText(busy ? "検索中…" : page == 0 ? "DISCOVER  /  音楽を探す" : "該当する音源がありません。別の曲名や英語表記でお試しください。");
            if (!busy && page == 0) {
                add(results, text("気になる音楽から、はじめよう。", 18, TEXT, true), 14, 8);
                add(results, text("曲名やアーティストを入力すると、保存できる音源を探せます。形式と容量を確認してからダウンロードできます。", 13, MUTED, false), 0, 14);
            }
        } else status.setText(getString(R.string.search_count, items.size(), total));
        for (ArchiveClient.Item item : items) {
            LinearLayout card = card();
            add(card, text(AudioFile.licenseLabel(item.license()) + "  •  INTERNET ARCHIVE", 10, MINT, true), 0, 10);
            add(card, text(item.title().isEmpty() ? item.id() : item.title(), 18, TEXT, true), 0, 6);
            add(card, text(item.creator().isEmpty() ? "アーティスト情報なし" : item.creator(), 13, MUTED, false), 0, 12);
            add(card, button("音源・最高品質を確認  →", true, v -> loadTracks(item)), 0, 0);
            add(results, card, 0, 12);
        }
        if (!busy && page * ArchiveClient.PAGE_SIZE < total) add(results, button("次の20件を表示", false, v -> search(true)), 4, 8);
    }

    private void loadTracks(ArchiveClient.Item item) {
        if (busy) return;
        int token = ++generation;
        boolean filter = losslessOnly;
        setBusy(true);
        status.setText("音源の形式とライセンスを確認しています…");
        request = worker.submit(() -> {
            try {
                ArchiveClient.Details details = client.details(item, filter);
                runOnUiThread(() -> {
                    if (!alive(token)) return;
                    setBusy(false);
                    if (library) return;
                    renderResults();
                    chooseTrack(item, details);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (alive(token)) { setBusy(false); if (!library) status.setText(message(e)); } });
            }
        });
    }

    private void chooseTrack(ArchiveClient.Item item, ArchiveClient.Details details) {
        if (details.tracks().isEmpty()) {
            new AlertDialog.Builder(this).setTitle("該当する音源がありません")
                    .setMessage(losslessOnly ? "この項目にはロスレス音源がありません。フィルターを解除すると他の形式を確認できます。" : "ダウンロード可能な音源ファイルがありません。")
                    .setPositiveButton("閉じる", null).show();
            return;
        }
        String[] titles = new String[details.tracks().size()];
        for (int i = 0; i < titles.length; i++) {
            AudioFile best = details.tracks().get(i).get(0);
            titles[i] = best.title + "\n" + best.quality() + " • " + AudioFile.sizeLabel(best.size);
        }
        new AlertDialog.Builder(this).setTitle("保存する曲を選択（" + titles.length + "曲）")
                .setItems(titles, (dialog, which) -> chooseFormat(item, details.tracks().get(which), details.license()))
                .setNeutralButton("配信元 ↗", (d, w) -> browse("https://archive.org/details/" + item.id()))
                .setNegativeButton("閉じる", null).show();
    }

    private void chooseFormat(ArchiveClient.Item item, List<AudioFile> variants, String license) {
        String[] labels = new String[variants.size()];
        for (int i = 0; i < labels.length; i++) {
            AudioFile file = variants.get(i);
            labels[i] = (i == 0 ? "おすすめ • " : "") + file.quality() + "\n" + AudioFile.sizeLabel(file.size);
        }
        int[] choice = {0};
        new AlertDialog.Builder(this).setTitle(variants.get(0).title)
                .setSingleChoiceItems(labels, 0, (d, w) -> choice[0] = w)
                .setPositiveButton("ダウンロード", (d, w) -> {
                    try {
                        downloads.enqueue(item, variants.get(choice[0]), license);
                        toast("ダウンロードを開始しました。保存した音源で確認できます。");
                    } catch (RuntimeException e) { toast(message(e)); }
                })
                .setNeutralButton("ライセンス ↗", (d, w) -> browse(license))
                .setNegativeButton("キャンセル", null).show();
    }

    private void showLibrary() {
        library = true;
        tabs();
        body.removeAllViews();
        add(body, text("あなたの音源。", 30, TEXT, true), 0, 8);
        add(body, text("保存先：ダウンロード / ToneKeep", 13, MUTED, false), 0, 18);
        Switch wifi = toggle("今後の保存はWi-Fi接続時のみ", downloads.prefs.getBoolean("wifi", false));
        wifi.setOnCheckedChangeListener((b, checked) -> downloads.prefs.edit().putBoolean("wifi", checked).apply());
        add(body, wifi, 0, 16);
        results = column();
        body.addView(results);
        renderDownloads();
    }

    private void renderDownloads() {
        if (!library || results == null) return;
        results.removeAllViews();
        List<DownloadStore.Entry> entries = downloads.all();
        if (entries.isEmpty()) {
            LinearLayout empty = card();
            add(empty, text("↓", 46, MINT, true), 4, 8);
            add(empty, text("お気に入りの音を、ここに。", 18, TEXT, true), 0, 8);
            add(empty, text("音源をダウンロードすると、進捗や保存済みファイルがここに表示されます。", 13, MUTED, false), 0, 16);
            add(empty, button("音楽を探す  →", true, v -> showSearch()), 0, 0);
            add(results, empty, 6, 0);
        }
        for (DownloadStore.Entry entry : entries) {
            DownloadStore.State state = downloads.state(entry.id());
            LinearLayout card = card();
            add(card, text(entry.title(), 17, TEXT, true), 0, 6);
            add(card, text(entry.quality(), 12, MINT, false), 0, 10);
            String stateText;
            switch (state.status()) {
                case DownloadManager.STATUS_SUCCESSFUL: stateText = "保存完了 • " + AudioFile.sizeLabel(state.total()); break;
                case DownloadManager.STATUS_RUNNING: stateText = "ダウンロード中 • " + AudioFile.sizeLabel(state.received()) + " / " + AudioFile.sizeLabel(state.total()); break;
                case DownloadManager.STATUS_PAUSED: stateText = state.reason() == DownloadManager.PAUSED_QUEUED_FOR_WIFI ? "Wi-Fi接続を待っています" : "接続待ち・再開を待っています"; break;
                case DownloadManager.STATUS_FAILED: stateText = "保存に失敗しました（" + state.reason() + "）。再試行できます。"; break;
                case DownloadManager.STATUS_PENDING: stateText = "ダウンロードの開始待ち"; break;
                default: stateText = "ファイルが削除されたか、履歴が見つかりません。";
            }
            add(card, text(stateText, 12, MUTED, false), 0, 8);
            if (state.status() == DownloadManager.STATUS_RUNNING) {
                ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                bar.setIndeterminate(state.total() <= 0);
                if (state.total() > 0) bar.setProgress((int) (100.0 * state.received() / state.total()));
                add(card, bar, 0, 8);
            }
            if (state.status() == DownloadManager.STATUS_SUCCESSFUL) {
                add(card, button("音源を開く  ↗", true, v -> openFile(entry)), 0, 0);
            } else if (state.status() == DownloadManager.STATUS_FAILED || state.status() == -1) {
                add(card, button("再ダウンロード", true, v -> {
                    try { downloads.retry(entry); renderDownloads(); } catch (RuntimeException e) { toast(message(e)); }
                }), 0, 0);
            } else add(card, button("ダウンロードを中止", false, v -> {
                new AlertDialog.Builder(this).setTitle("このダウンロードを中止しますか？")
                        .setMessage("保存途中のファイルを削除します。")
                        .setPositiveButton("中止する", (d, w) -> { downloads.cancel(entry); renderDownloads(); })
                        .setNegativeButton("続ける", null).show();
            }), 0, 0);
            add(card, button("配信元・ライセンス ↗", false, v -> {
                new AlertDialog.Builder(this).setTitle(AudioFile.licenseLabel(entry.license()))
                        .setItems(new String[]{"配信元を開く", "ライセンスを開く"}, (d, w) -> browse(w == 0 ? entry.source() : entry.license()))
                        .setNegativeButton("閉じる", null).show();
            }), 4, 0);
            add(results, card, 0, 12);
        }
    }

    private void openFile(DownloadStore.Entry entry) {
        try {
            Uri uri = downloads.manager.getUriForDownloadedFile(entry.id());
            if (uri == null) { toast("ファイルが見つかりません。端末のダウンロードフォルダーを確認してください。"); return; }
            String mime = downloads.manager.getMimeTypeForDownloadedFile(entry.id());
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime != null && mime.startsWith("audio/") ? mime : "audio/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "音源を開く"));
        } catch (ActivityNotFoundException e) { toast("この形式に対応する音楽プレーヤーをインストールしてください。"); }
        catch (RuntimeException e) { toast("ファイルを開けませんでした。端末のダウンロードフォルダーを確認してください。"); }
    }

    private void about() {
        new AlertDialog.Builder(this).setTitle("元の音を、そのまま保存")
                .setMessage("同じ録音の公開ファイルを比較し、ロスレス → ビット深度・サンプルレート（情報がある場合）→ FLACの順で優先します。非可逆圧縮では既知のビットレートを優先します。\n\n元音源の品質が上限です。ロスレス表記は元録音の品質を保証しません。数値が不明な項目は推定しません。\n\n配信元のCC・パブリックドメイン表記を確認しています。表示は投稿者の情報に基づきます。帰属表示・非営利など各音源の条件に従って利用してください。\n\nWi-Fi設定は新しく開始する保存に適用されます。保存した音源はアプリ削除後もダウンロードフォルダーに残ります。")
                .setPositiveButton("わかりました", null).show();
    }
    private void browse(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException e) { toast("リンクを開くブラウザーがありません。"); }
    }
    private void tabs() {
        searchTab.setTextColor(library ? MUTED : MINT);
        libraryTab.setTextColor(library ? MINT : MUTED);
        searchTab.setSelected(!library); libraryTab.setSelected(library);
    }
    private void setBusy(boolean value) {
        busy = value;
        if (!library && progress != null) progress.setVisibility(value ? View.VISIBLE : View.GONE);
    }
    private boolean alive(int token) { return !isFinishing() && !isDestroyed() && generation == token; }
    private String message(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) return "接続がタイムアウトしました。もう一度お試しください。";
        if (e instanceof java.net.UnknownHostException) return "ネット接続を確認して、もう一度お試しください。";
        return e.getMessage() == null ? "処理に失敗しました。もう一度お試しください。" : e.getMessage();
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout card() { LinearLayout c = column(); c.setPadding(dp(18), dp(18), dp(18), dp(14)); c.setBackground(round(CARD, 18, BORDER)); return c; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setLineSpacing(dp(3), 1); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private Button button(String label, boolean primary, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(primary ? BG : MUTED); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setBackground(round(primary ? MINT : BG, 12, primary ? MINT : BORDER)); b.setOnClickListener(listener); return b;
    }
    private Switch toggle(String label, boolean checked) {
        Switch s = new Switch(this); s.setText(label); s.setTextColor(MUTED); s.setTextSize(13);
        s.setPadding(0, dp(8), 0, dp(8)); s.setMinHeight(dp(48)); s.setChecked(checked); return s;
    }
    private GradientDrawable round(int fill, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), stroke); return d;
    }
    private void add(LinearLayout parent, View child, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(top), 0, dp(bottom)); parent.addView(child, p);
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putBoolean("library", library); out.putBoolean("lossless", losslessOnly);
        out.putString("query", input != null ? input.getText().toString() : currentQuery);
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(refresh); }
    @Override protected void onDestroy() {
        ++generation; if (request != null) request.cancel(true); worker.shutdownNow(); handler.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
