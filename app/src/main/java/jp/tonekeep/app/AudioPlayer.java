package jp.tonekeep.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;
import java.util.function.IntConsumer;

/** Activity-scoped player, shared across the three tabs. All methods run on the main thread. */
final class AudioPlayer implements AutoCloseable {
    private static final int MINT = Color.rgb(183, 245, 207), MUTED = Color.rgb(160, 179, 168);
    final LinearLayout view;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private final TextView title, time;
    private final ImageButton play;
    private final SeekBar seek;
    private final IntConsumer previewSeek;
    private final Runnable dismissed;
    private final Runnable interrupted;
    private MediaPlayer media;
    private boolean ready, preparing, wantsPlay, preview, dragging, completed;
    private int offset, total;
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { pause(); interrupted.run(); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() { update(); handler.postDelayed(this, 250); }
    };
    AudioPlayer(Context context, IntConsumer previewSeek, Runnable dismissed, Runnable interrupted) {
        this.context = context; this.previewSeek = previewSeek; this.dismissed = dismissed; this.interrupted = interrupted;
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> { if (change < 0) { pause(); interrupted.run(); } }, handler).build();
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); view.setPadding(dp(12), 0, dp(12), 0); view.setBackgroundColor(Color.rgb(27, 37, 34)); view.setVisibility(View.GONE);
        LinearLayout top = new LinearLayout(context); top.setGravity(Gravity.CENTER_VERTICAL);
        play = icon(UiIcon.Kind.PLAY, "再生・一時停止", v -> toggle()); top.addView(play);
        title = new TextView(context); title.setTextSize(13); title.setTextColor(MINT); title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(icon(UiIcon.Kind.CLOSE, "プレーヤーを閉じる", v -> { stop(); dismissed.run(); })); view.addView(top);
        LinearLayout bottom = new LinearLayout(context); bottom.setGravity(Gravity.CENTER_VERTICAL);
        seek = new SeekBar(context); seek.setContentDescription("再生位置"); seek.setMinimumHeight(dp(40)); bottom.addView(seek, new LinearLayout.LayoutParams(0, dp(40), 1));
        time = new TextView(context); time.setTextSize(11); time.setTextColor(MUTED); time.setPadding(dp(4), 0, dp(4), 0); bottom.addView(time); view.addView(bottom);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user) time.setText(clock(value) + " / " + clock(total)); }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false; if (!ready) return;
                seekTo(bar.getProgress());
            }
        });
        handler.post(tick);
    }
    void busy(String name) {
        release(); preparing = true; title.setText(name + " · 準備中"); view.setVisibility(View.VISIBLE);
        play.setEnabled(false); seek.setEnabled(false); time.setText("");
    }
    void load(Uri uri, String name, boolean isPreview, int offsetMs, int durationMs, Runnable failed) {
        release(); preview = isPreview; offset = offsetMs; total = durationMs; preparing = true; wantsPlay = true;
        title.setText(name); view.setVisibility(View.VISIBLE); play.setEnabled(false); seek.setEnabled(false);
        MediaPlayer next = new MediaPlayer(); media = next;
        next.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        next.setOnPreparedListener(player -> {
            if (media != player) return;
            preparing = false; ready = true; completed = false;
            if (!preview) total = player.getDuration();
            seek.setMax(Math.max(1, total)); seek.setEnabled(true); play.setEnabled(true);
            if (wantsPlay) start(); update();
        });
        next.setOnCompletionListener(player -> { if (media == player) { completed = true; wantsPlay = false; audio.abandonAudioFocusRequest(focus); update(); } });
        next.setOnErrorListener((player, what, extra) -> { if (media == player) { release(); failed.run(); } return true; });
        try { next.setDataSource(context, uri); next.prepareAsync(); }
        catch (Exception error) { if (media == next) { release(); failed.run(); } }
    }
    private void start() {
        if (!ready || media == null) return;
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { wantsPlay = false; update(); return; }
        wantsPlay = true; media.start(); update();
    }
    private void toggle() {
        if (!ready) return;
        if (media.isPlaying()) pause();
        else { if (completed) { media.seekTo(0); completed = false; } start(); }
    }
    void pause() { wantsPlay = false; if (ready && media != null && media.isPlaying()) media.pause(); audio.abandonAudioFocusRequest(focus); update(); }
    boolean isPlaying() { return ready && media != null && media.isPlaying(); }
    boolean isPreparing() { return preparing; }
    void seekTo(int positionMs) {
        if (!ready) return;
        if (preview) previewSeek.accept(positionMs);
        else { completed = false; media.seekTo(Math.max(0, Math.min(total, positionMs)), MediaPlayer.SEEK_CLOSEST); }
    }
    int position() { return offset + (ready && media != null ? media.getCurrentPosition() : 0); }
    private void update() {
        play.setImageDrawable(new UiIcon(isPlaying() ? UiIcon.Kind.PAUSE : UiIcon.Kind.PLAY, MINT));
        if (ready && !dragging) { seek.setProgress(Math.min(total, position())); time.setText(clock(position()) + " / " + clock(total)); }
    }
    void stop() { release(); view.setVisibility(View.GONE); }
    private void release() {
        ready = false; preparing = false; wantsPlay = false; completed = false;
        MediaPlayer old = media; media = null; if (old != null) old.release(); audio.abandonAudioFocusRequest(focus);
    }
    private ImageButton icon(UiIcon.Kind kind, String label, View.OnClickListener click) {
        ImageButton button = new ImageButton(context); button.setImageDrawable(new UiIcon(kind, MINT)); button.setContentDescription(label); button.setTooltipText(label);
        button.setBackgroundColor(Color.TRANSPARENT); button.setPadding(dp(12), dp(12), dp(12), dp(12)); button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48))); button.setOnClickListener(click); return button;
    }
    static String clock(int ms) { int seconds = Math.max(0, ms / 1000); return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    @Override public void close() { stop(); handler.removeCallbacksAndMessages(null); context.unregisterReceiver(noisy); }
}
