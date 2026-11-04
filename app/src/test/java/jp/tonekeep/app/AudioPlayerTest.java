package jp.tonekeep.app;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29)
public class AudioPlayerTest {
    @Test public void remoteSampleStopsAtThirtySecondsAndCanBeReplayed() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        MediaInfo.Sample sample = new MediaInfo.Sample("https://cdn.example.com/audio.m4a", java.util.Map.of("User-Agent", "Fixture"));
        Uri uri = Uri.parse(sample.url());
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri, sample.headers()), new ShadowMediaPlayer.MediaInfo(120000, 0));
        try (AudioPlayer player = new AudioPlayer(context, position -> fail("A search sample must not start a pitch render"), () -> {}, () -> {})) {
            player.loadSample(sample, "Sample", () -> fail("Sample failed"));
            Shadows.shadowOf(Looper.getMainLooper()).idle(); assertTrue(player.isPlaying());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(31));
            assertFalse(player.isPlaying()); assertEquals(30000, player.position());
            java.lang.reflect.Field field = AudioPlayer.class.getDeclaredField("play"); field.setAccessible(true);
            ((android.widget.ImageButton) field.get(player)).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle(); assertTrue(player.isPlaying()); assertEquals(0, player.position());
            player.pause(); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(31));
            assertFalse(player.isPlaying());
            player.seekTo(120000); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertEquals(30000, player.position());
            ((android.widget.ImageButton) field.get(player)).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle(); assertTrue(player.isPlaying()); assertEquals(0, player.position());
        }
    }
    @Test public void refreshTimerRunsOnlyWhilePlayingAndIconsAreReused() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); Uri uri = Uri.parse("file:///timer.wav");
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri), new ShadowMediaPlayer.MediaInfo(1000, 0));
        try (AudioPlayer player = new AudioPlayer(context, position -> {}, () -> {}, () -> {})) {
            java.lang.reflect.Field handlerField = AudioPlayer.class.getDeclaredField("handler"), tickField = AudioPlayer.class.getDeclaredField("tick"), playField = AudioPlayer.class.getDeclaredField("play");
            handlerField.setAccessible(true); tickField.setAccessible(true); playField.setAccessible(true);
            android.os.Handler handler = (android.os.Handler) handlerField.get(player); Runnable tick = (Runnable) tickField.get(player);
            android.widget.ImageButton button = (android.widget.ImageButton) playField.get(player);
            assertFalse(handler.hasCallbacks(tick));
            player.load(uri, "Timer", false, 0, 0, () -> fail("Playback failed"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(player.isPlaying()); assertTrue(handler.hasCallbacks(tick));
            android.graphics.drawable.Drawable playing = button.getDrawable();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));
            assertSame(playing, button.getDrawable());
            player.pause(); assertFalse(handler.hasCallbacks(tick));
            android.graphics.drawable.Drawable paused = button.getDrawable();
            button.performClick(); assertTrue(handler.hasCallbacks(tick));
            player.pause(); assertSame(paused, button.getDrawable()); assertFalse(handler.hasCallbacks(tick));
            button.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
            assertFalse(player.isPlaying()); assertFalse(handler.hasCallbacks(tick));
            player.stop(); assertFalse(handler.hasCallbacks(tick));
        }
        assertEquals("0:00", AudioPlayer.clock(-1000)); assertEquals("1:01", AudioPlayer.clock(61000));
    }
    @Test public void previewSeeksReuseTheRenderedWindowAndOnlyRenderOutsideIt() {
        Context context = RuntimeEnvironment.getApplication();
        Uri uri = Uri.parse("file:///preview.wav");
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri), new ShadowMediaPlayer.MediaInfo(20_000, 0));
        List<Integer> renders = new ArrayList<>();
        try (AudioPlayer player = new AudioPlayer(context, renders::add, () -> {}, () -> {})) {
            player.load(uri, "Preview", true, 10_000, 120_000, () -> fail("Playback failed"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(player.isPreparing());
            player.seekTo(15_000);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(renders.isEmpty());
            assertEquals(15_000, player.position());
            player.pause();
            player.seekTo(10_000);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(renders.isEmpty());
            assertEquals(10_000, player.position());
            player.seekTo(9_999);
            player.seekTo(30_000);
            assertEquals(List.of(9_999, 30_000), renders);
        }
    }
    @Test public void tailWindowUsesActualClipDurationRatherThanTwentySeconds() {
        Context context = RuntimeEnvironment.getApplication();
        Uri uri = Uri.parse("file:///tail.wav");
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri), new ShadowMediaPlayer.MediaInfo(1_000, 0));
        List<Integer> renders = new ArrayList<>();
        try (AudioPlayer player = new AudioPlayer(context, renders::add, () -> {}, () -> {})) {
            player.load(uri, "Tail", true, 119_000, 120_000, () -> fail("Playback failed"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            player.seekTo(119_500);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(renders.isEmpty());
            assertEquals(119_500, player.position());
            player.seekTo(120_000);
            assertEquals(List.of(120_000), renders);
        }
    }
}
