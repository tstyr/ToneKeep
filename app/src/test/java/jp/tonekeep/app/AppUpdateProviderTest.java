package jp.tonekeep.app;

import android.content.Context;
import android.content.Intent;
import androidx.core.content.FileProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AppUpdateProviderTest {
    @Test public void installerOnlyGrantsReadAccessToThePrivateUpdateFolder() {
        Context context = RuntimeEnvironment.getApplication();
        Intent intent = AppUpdate.installer(context);
        assertEquals("content", intent.getData().getScheme()); assertEquals("application/vnd.android.package-archive", intent.getType());
        assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertEquals(0, intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        assertThrows(IllegalArgumentException.class, () -> FileProvider.getUriForFile(context, context.getPackageName() + ".updates", new java.io.File(context.getFilesDir(), "private")));
    }
}
