import jp.tonekeep.app.PitchSettings;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Runs the production DSP argument builder on the host; no Android stubs or reimplemented filter. */
public final class PitchFixtureRunner {
    public static void main(String[] args) throws Exception {
        File source = new File(args[0]), output = new File(args[1]);
        PitchSettings settings = new PitchSettings(Integer.parseInt(args[2]), Boolean.parseBoolean(args[3]), args[4]);
        File work = output.getParentFile();
        File intermediate = new File(work, "float-" + settings.semitones() + ".wav");
        File peak = new File(work, "peak-" + settings.semitones() + ".log");
        execute(settings.shiftArgs(source, intermediate), new File(work, "shift.log"));
        execute(settings.peakArgs(intermediate), peak);
        double gain = PitchSettings.gainForPeak(Files.readString(peak.toPath(), StandardCharsets.UTF_8));
        execute(settings.exportArgs(source, intermediate, output, gain), new File(work, "export.log"));
        System.out.printf(Locale.ROOT, "{\"ratio\":%.10f,\"gain\":%.10f}%n", settings.ratio(), gain);
    }
    private static void execute(List<String> args, File log) throws Exception {
        String ffmpeg = System.getenv("FFMPEG_BIN");
        List<String> command = new ArrayList<>(); command.add(ffmpeg == null ? "ffmpeg" : ffmpeg); command.addAll(args);
        Process child = new ProcessBuilder(command).redirectError(log).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!child.waitFor(120, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new IllegalStateException("FFmpeg timed out"); }
        if (child.exitValue() != 0) throw new IllegalStateException(Files.readString(log.toPath()));
    }
}
