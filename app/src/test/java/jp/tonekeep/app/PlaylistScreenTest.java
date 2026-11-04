package jp.tonekeep.app;

import android.os.Bundle;
import android.os.Parcel;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.CheckBox;
import android.widget.Spinner;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
public class PlaylistScreenTest {
    @Test public void lyricsAllowCorrectingTheSongAndDisplayProvidedLyricsWithoutANetworkRequest() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            LyricsDialog lyrics = new LyricsDialog(controller.get());
            try {
                lyrics.show("Song", "Artist", 120, "Owned test lyrics");
                android.app.AlertDialog form = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
                View view = form.getWindow().getDecorView();
                assertEquals("Song", ((android.widget.EditText) description(view, "歌詞検索の曲名")).getText().toString());
                ((android.widget.EditText) description(view, "歌詞検索のアーティスト")).setText("Corrected artist");
                form.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick();
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                android.app.AlertDialog result = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
                assertTrue(result.isShowing()); lyrics.close(); assertFalse(result.isShowing()); assertFalse(form.isShowing());
            } finally { lyrics.close(); }
        }
    }
    @Before public void resetDatabase() throws Exception {
        Field field = DownloadStore.class.getDeclaredField("instance"); field.setAccessible(true); field.set(null, null);
        org.robolectric.RuntimeEnvironment.getApplication().deleteDatabase("downloads.db");
    }
    @After public void closeDatabase() throws Exception {
        Field field = DownloadStore.class.getDeclaredField("instance"); field.setAccessible(true);
        android.database.sqlite.SQLiteOpenHelper helper = (android.database.sqlite.SQLiteOpenHelper) field.get(null);
        if (helper != null) helper.close(); field.set(null, null);
    }
    @Test public void localAudioPickerAndKeySettingsSurviveRecreation() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            description(root, "キー変更").performClick();
            String export = "保存";
            assertFalse(find(root, export).isEnabled());
            assertFalse(description(root, "選択した音源からボーカルを抽出").isEnabled());
            for (android.view.ViewParent parent = find(root, export).getParent(); parent != null; parent = parent.getParent())
                assertFalse("Save must stay outside the scrollable controls", parent instanceof android.widget.ScrollView);
            find(root, "ファイル").performClick();
            Intent pick = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, pick.getAction()); assertEquals("audio/*", pick.getType());
            assertTrue((pick.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0);
            activity.onActivityResult(1067, Activity.RESULT_OK, new Intent().setData(Uri.parse("content://audio/owned-file")));
            assertTrue(description(root, "選択した音源からボーカルを抽出").isEnabled());
            assertFalse(find(root, export).isEnabled());
            description(root, "半音上げる").performClick(); description(root, "半音上げる").performClick();
            assertTrue(find(root, export).isEnabled());
            widget(root, CheckBox.class).setChecked(false);
            widget(root, Spinner.class).setSelection(1);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            controller.recreate();
            root = controller.get().findViewById(android.R.id.content);
            assertEquals(14, widget(root, SeekBar.class).getProgress());
            assertFalse(widget(root, CheckBox.class).isChecked());
            assertEquals(1, widget(root, Spinner.class).getSelectedItemPosition());
            assertTrue(find(root, export).isEnabled());
            for (int i = 0; i < 30; i++) description(root, "半音下げる").performClick();
            assertEquals(0, widget(root, SeekBar.class).getProgress());
            for (int i = 0; i < 12; i++) description(root, "半音上げる").performClick();
            assertFalse(find(root, export).isEnabled());
        }
    }
    @Test public void vocalQualityChoiceIsRememberedButCancellingDoesNotChangeIt() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            Method select = MainActivity.class.getDeclaredMethod("selectPitch", String.class, String.class); select.setAccessible(true);
            select.invoke(activity, "content://audio/quality", "Quality test");
            ViewGroup root = activity.findViewById(android.R.id.content);
            description(root, "選択した音源からボーカルを抽出").performClick();
            android.app.AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            View choices = dialog.getWindow().getDecorView();
            assertTrue(((android.widget.RadioButton) description(choices, "標準のボーカル抽出")).isChecked());
            description(choices, "高精度のボーカル抽出").performClick();
            assertTrue(((android.widget.RadioButton) description(choices, "高精度のボーカル抽出")).isChecked());
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            DownloadStore store = new DownloadStore(activity);
            assertTrue(store.prefs.getBoolean("vocal_high_quality", false));
            assertEquals("high", store.all().get(0).vocalQuality());
            description(root, "選択した音源からボーカルを抽出").performClick();
            dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            choices = dialog.getWindow().getDecorView();
            assertTrue(((android.widget.RadioButton) description(choices, "高精度のボーカル抽出")).isChecked());
            description(choices, "標準のボーカル抽出").performClick();
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(store.prefs.getBoolean("vocal_high_quality", false)); assertEquals(1, store.count());
        }
    }
    private <T extends View> T widget(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            T match = widget(group.getChildAt(i), type); if (match != null) return match;
        }
        return null;
    }
    @Test public void selectAllIncludesTracksBeyondVisiblePageWithoutPuttingHugeResultsInSavedBundle() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            Field itemsField = MainActivity.class.getDeclaredField("items"); itemsField.setAccessible(true);
            @SuppressWarnings("unchecked") List<MediaInfo.Track> items = (List<MediaInfo.Track>) itemsField.get(activity);
            for (int i = 0; i < 1000; i++) items.add(new MediaInfo.Track("" + i, "Song " + i, "Artist", "https://example.com/" + i, "Test", 120, false, List.of()));
            Method render = MainActivity.class.getDeclaredMethod("renderResults"); render.setAccessible(true); render.invoke(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            CheckBox first = widget(root, CheckBox.class); first.performClick();
            assertSame(first, widget(root, CheckBox.class)); assertTrue(find(root, "保存（1）").isShown());
            assertTrue(find(root, "全選択").performClick());
            assertSame(first, widget(root, CheckBox.class)); assertTrue(first.isChecked());
            Field selectedField = MainActivity.class.getDeclaredField("selected"); selectedField.setAccessible(true);
            assertEquals(1000, ((Set<?>) selectedField.get(activity)).size());
            assertTrue(find(root, "保存（1000）").isShown());
            Bundle saved = new Bundle(); controller.saveInstanceState(saved);
            Parcel parcel = Parcel.obtain();
            try { saved.writeToParcel(parcel, 0); assertTrue("Saved state must not contain the playlist", parcel.dataSize() < 32768); }
            finally { parcel.recycle(); }
            assertNotNull(activity.onRetainNonConfigurationInstance());
            description(root, "選択を解除").performClick(); assertTrue(((Set<?>) selectedField.get(activity)).isEmpty());
            assertSame(first, widget(root, CheckBox.class)); assertFalse(first.isChecked());
        }
    }
    @Test public void historyOnlyRebuildsAfterAnotherStoreClientChangesIt() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get(); DownloadStore writer = new DownloadStore(activity);
            DownloadStore.Entry entry = writer.enqueue(new MediaInfo.Track("1", "Existing", "", "https://example.com/history", "Test", 1, false, List.of()), "", "best");
            ViewGroup root = activity.findViewById(android.R.id.content); description(root, "ライブラリ").performClick();
            Field field = MainActivity.class.getDeclaredField("results"); field.setAccessible(true);
            ViewGroup rows = (ViewGroup) field.get(activity); View first = rows.getChildAt(0);
            Method render = MainActivity.class.getDeclaredMethod("renderDownloads", boolean.class); render.setAccessible(true);
            render.invoke(activity, false); assertSame(first, rows.getChildAt(0));
            writer.update(entry.id(), "running", "work", 25, null, null);
            render.invoke(activity, false); assertNotSame(first, rows.getChildAt(0));
            View updated = rows.getChildAt(0); render.invoke(activity, false); assertSame(updated, rows.getChildAt(0));
        }
    }
    @Test public void searchCardsShowArtworkAndPreviewBeforeAnyDownloadIsQueued() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            Field itemsField = MainActivity.class.getDeclaredField("items"); itemsField.setAccessible(true);
            @SuppressWarnings("unchecked") List<MediaInfo.Track> items = (List<MediaInfo.Track>) itemsField.get(activity);
            items.add(new MediaInfo.Track("1", "Song", "Artist", "https://example.com/song", "Test", 120, false, List.of()));
            items.add(new MediaInfo.Track("2", "Live", "Artist", "https://example.com/live", "Test", 0, true, List.of()));
            Method render = MainActivity.class.getDeclaredMethod("renderResults"); render.setAccessible(true); render.invoke(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            assertNotNull(((android.widget.ImageView) description(root, "Songのサムネイル")).getDrawable());
            assertTrue(description(root, "Songを試聴（最大30秒）").isEnabled());
            assertFalse(description(root, "Liveを試聴（最大30秒）").isEnabled());
            assertTrue(description(root, "Songの保存形式を選ぶ").isEnabled());
            assertEquals(0, new DownloadStore(activity).count());
        }
    }
    @Test public void artworkLoadsAndActivityCanCloseWithAnImageRequest() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            java.io.File thumbnail = new java.io.File(activity.getCacheDir(), "owned-thumbnail.png");
            java.nio.file.Files.write(thumbnail.toPath(), java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=="));
            Field itemsField = MainActivity.class.getDeclaredField("items"); itemsField.setAccessible(true);
            @SuppressWarnings("unchecked") List<MediaInfo.Track> items = (List<MediaInfo.Track>) itemsField.get(activity);
            items.add(new MediaInfo.Track("1", "Artwork", "Artist", "https://example.com/song", "Test", 120, false, List.of(), Uri.fromFile(thumbnail).toString()));
            Method render = MainActivity.class.getDeclaredMethod("renderResults"); render.setAccessible(true); render.invoke(activity);
            android.widget.ImageView image = (android.widget.ImageView) description(activity.findViewById(android.R.id.content), "Artworkのサムネイル");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!(image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable) && System.nanoTime() < deadline) {
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(20);
            }
            assertTrue("Artwork must replace the placeholder", image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
        }
    }
    private Button find(View root, String text) {
        Button result = search(root, text); assertNotNull("Missing button: " + text, result); return result;
    }
    private View description(View root, String value) {
        if (value.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { View found = description(group.getChildAt(i), value); if (found != null) return found; }
        return null;
    }
    private Button search(View root, String text) {
        if (root instanceof Button button && button.getText().toString().equals(text)) return button;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            Button match = search(group.getChildAt(i), text); if (match != null) return match;
        }
        return null;
    }
}
