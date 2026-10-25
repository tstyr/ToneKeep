package jp.tonekeep.app;

import org.junit.Test;
import java.util.Locale;
import static org.junit.Assert.*;

public class PitchSettingsTest {
    @Test public void musicalIntervalsUseEqualTemperamentWithoutChangingTempo() {
        assertEquals(2, new PitchSettings(12, true, "flac").ratio(), 1e-12);
        assertEquals(.5, new PitchSettings(-12, false, "wav").ratio(), 1e-12);
        assertEquals(Math.pow(2, 3 / 12.0), new PitchSettings(3, true, "flac").ratio(), 1e-12);
        assertTrue(new PitchSettings(3, true, "flac").filter().contains("tempo=1:"));
        assertTrue(new PitchSettings(3, true, "flac").filter().contains("channels=together"));
    }
    @Test public void processingNumbersDoNotUseDeviceDecimalSeparator() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertTrue(new PitchSettings(3, true, "flac").filter().contains("pitch=1.189207115003"));
            assertFalse(new PitchSettings(3, false, "wav").filter().contains("formant=preserved"));
        } finally { Locale.setDefault(previous); }
    }
    @Test public void peakProtectionOnlyAttenuatesAndHandlesSilence() {
        assertEquals(1, PitchSettings.gainForPeak("Peak level dB: -12.400000"), 0);
        assertEquals(1, PitchSettings.gainForPeak("Peak level dB: -inf"), 0);
        assertEquals(Math.pow(10, -6.1 / 20), PitchSettings.gainForPeak("Peak level dB: -2.0\nPeak level dB: 6.0"), 1e-12);
        assertEquals(Math.pow(10, -.1 / 20), PitchSettings.gainForPeak("Peak level dB: 0.000000"), 1e-12);
    }
    @Test public void unsupportedSettingsAreRejectedBeforeStartingNativeProcess() {
        assertThrows(IllegalArgumentException.class, () -> new PitchSettings(13, true, "flac"));
        assertThrows(IllegalArgumentException.class, () -> new PitchSettings(-13, true, "wav"));
        assertThrows(IllegalArgumentException.class, () -> new PitchSettings(3, true, "mp3"));
    }
}
