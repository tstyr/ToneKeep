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
            find(root, "ファイル").performClick();
            Intent pick = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, pick.getAction()); assertEquals("audio/*", pick.getType());
            assertTrue((pick.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0);
            activity.onActivityResult(1067, Activity.RESULT_OK, new Intent().setData(Uri.parse("content://audio/owned-file")));
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
            assertTrue(find(root, "全選択").performClick());
            Field selectedField = MainActivity.class.getDeclaredField("selected"); selectedField.setAccessible(true);
            assertEquals(1000, ((Set<?>) selectedField.get(activity)).size());
            assertTrue(find(root, "保存（1000）").isShown());
            Bundle saved = new Bundle(); controller.saveInstanceState(saved);
            Parcel parcel = Parcel.obtain();
            try { saved.writeToParcel(parcel, 0); assertTrue("Saved state must not contain the playlist", parcel.dataSize() < 32768); }
            finally { parcel.recycle(); }
            assertNotNull(activity.onRetainNonConfigurationInstance());
            description(root, "選択を解除").performClick(); assertTrue(((Set<?>) selectedField.get(activity)).isEmpty());
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
