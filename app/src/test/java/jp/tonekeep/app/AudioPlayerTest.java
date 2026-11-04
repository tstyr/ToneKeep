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
