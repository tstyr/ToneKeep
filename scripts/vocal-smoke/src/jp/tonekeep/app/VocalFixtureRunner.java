package jp.tonekeep.app;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/** Host integration checks using exactly the Android inference/STFT implementation. */
public final class VocalFixtureRunner {
    public static void main(String[] args) throws Exception {
        File input = new File(args[1]), output = new File(args[2]);
        VocalQuality quality = VocalQuality.fromId(args[3]);
        try (VocalSeparator separator = new VocalSeparator(new File(args[0]))) {
            float[][] samples = new float[2][VocalSeparator.WINDOW];
            for (int c = 0; c < 2; c++) for (int i = 0; i < samples[c].length; i++)
                samples[c][i] = (float) (.4 * Math.sin(2 * Math.PI * (60 + c * 30) * i / VocalSeparator.FFT));
            float[][] recovered = separator.istft(separator.stft(samples, () -> true), () -> true);
            double error = 0;
            for (int c = 0; c < 2; c++) for (int i = VocalSeparator.CONTEXT; i < VocalSeparator.WINDOW - VocalSeparator.CONTEXT; i++)
                error = Math.max(error, Math.abs(samples[c][i] - recovered[c][i]));
            if (error > 1e-4) throw new AssertionError("STFT round trip error: " + error);
            AtomicInteger progress = new AtomicInteger();
            separator.separate(input, output, quality, () -> true, percent -> {
                if (percent < progress.get()) throw new AssertionError("Progress moved backwards");
                progress.set(percent);
            });
            if (output.length() != input.length() || progress.get() != 100) throw new AssertionError("Output length/progress mismatch");
            File cancelled = new File(output.getParentFile(), "cancelled.f32");
            try {
                separator.separate(input, cancelled, quality, () -> false, percent -> {});
                throw new AssertionError("Cancellation was ignored");
            } catch (InterruptedException expected) { Files.deleteIfExists(cancelled.toPath()); }
            System.out.println("Vocal inference, STFT round trip, duration, progress and cancellation: PASS");
        }
    }
}
