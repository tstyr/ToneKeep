package jp.tonekeep.app;

import android.os.Bundle;
import android.os.Parcel;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
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
            assertTrue(find(root, "選択した曲を一括保存  ↓").isShown());
            Bundle saved = new Bundle(); controller.saveInstanceState(saved);
            Parcel parcel = Parcel.obtain();
            try { saved.writeToParcel(parcel, 0); assertTrue("Saved state must not contain the playlist", parcel.dataSize() < 32768); }
            finally { parcel.recycle(); }
            assertNotNull(activity.onRetainNonConfigurationInstance());
            find(root, "選択を解除").performClick(); assertTrue(((Set<?>) selectedField.get(activity)).isEmpty());
        }
    }
    private Button find(View root, String text) {
        Button result = search(root, text); assertNotNull("Missing button: " + text, result); return result;
    }
    private Button search(View root, String text) {
        if (root instanceof Button button && button.getText().toString().equals(text)) return button;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            Button match = search(group.getChildAt(i), text); if (match != null) return match;
        }
        return null;
    }
}
