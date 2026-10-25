package jp.tonekeep.app;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared, platform-independent DSP arguments for Android and the audio integration test. */
public record PitchSettings(int semitones, boolean preserveFormants, String format) {
    public PitchSettings {
        if (semitones < -12 || semitones > 12) throw new IllegalArgumentException("キーは-12〜+12半音で指定してください。");
        if (!format.equals("flac") && !format.equals("wav")) throw new IllegalArgumentException("出力はFLACまたはWAVを選択してください。");
    }
    public double ratio() { return Math.pow(2, semitones / 12.0); }
    public String label() { return semitones == 0 ? "原キー（±0）" : String.format(Locale.ROOT, "%+d 半音", semitones); }
    public String filter() {
        return "rubberband=tempo=1:pitch=" + number(ratio())
                + ":transients=crisp:detector=compound:phase=laminar:window=standard"
                + ":smoothing=off:formant=" + (preserveFormants ? "preserved" : "shifted")
                + ":pitchq=quality:channels=together";
    }
    private static List<String> base() { return new ArrayList<>(List.of("-nostdin", "-hide_banner", "-y", "-nostats", "-progress", "pipe:1")); }
    private static void input(List<String> args, File file) { args.addAll(List.of("-protocol_whitelist", "file,pipe", "-i", file.getAbsolutePath())); }
    public List<String> shiftArgs(File input, File intermediate) {
        List<String> args = base(); args.addAll(List.of("-v", "error")); input(args, input);
        args.addAll(List.of("-map", "0:a:0", "-vn", "-af", filter(), "-c:a", "pcm_f32le", "-rf64", "auto", intermediate.getAbsolutePath()));
        return args;
    }
    public List<String> peakArgs(File intermediate) {
        List<String> args = base(); args.addAll(List.of("-v", "info")); input(args, intermediate);
        args.addAll(List.of("-map", "0:a:0", "-af", "astats=metadata=0:reset=0:measure_perchannel=none:measure_overall=Peak_level", "-f", "null", "-"));
        return args;
    }
    public List<String> exportArgs(File source, File intermediate, File output, double gain) {
        if (!Double.isFinite(gain) || gain <= 0 || gain > 1) throw new IllegalArgumentException("Invalid output gain");
        List<String> args = base(); args.addAll(List.of("-v", "error")); input(args, intermediate); input(args, source);
        args.addAll(List.of("-map", "0:a:0", "-map_metadata", "1", "-map_chapters", "-1", "-vn", "-af", "volume=" + number(gain)));
        if (format.equals("flac")) args.addAll(List.of("-c:a", "flac", "-sample_fmt", "s32", "-bits_per_raw_sample", "24", "-compression_level", "5"));
        else args.addAll(List.of("-c:a", "pcm_s24le", "-rf64", "auto"));
        args.add(output.getAbsolutePath()); return args;
    }
    public static double gainForPeak(String log) {
        Matcher match = Pattern.compile("Peak level dB:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(log);
        double peak = Double.NEGATIVE_INFINITY;
        while (match.find()) peak = Math.max(peak, Double.parseDouble(match.group(1)));
        // A float intermediate keeps processing overshoot intact. Attenuate only when needed.
        return peak > -0.1 ? Math.pow(10, (-0.1 - peak) / 20.0) : 1.0;
    }
    private static String number(double value) { return String.format(Locale.ROOT, "%.12f", value); }
}
