package jp.tonekeep.app;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared, platform-independent DSP arguments for Android and the audio integration test. */
public record PitchSettings(int semitones, boolean preserveFormants, String format) {
    public static final List<String> FORMATS = List.of("flac", "wav", "mp3", "m4a", "opus", "ogg");
    public static final List<String> FORMAT_LABELS = List.of("FLAC · 24bit", "WAV · 24bit", "MP3 · 320kbps", "M4A / AAC · 256kbps", "Opus · 192kbps", "Ogg / Vorbis · 品質8");
    public PitchSettings {
        if (semitones < -12 || semitones > 12) throw new IllegalArgumentException("キーは-12〜+12半音で指定してください。");
        if (!FORMATS.contains(format)) throw new IllegalArgumentException("対応する出力形式を選択してください。");
    }
    public String formatLabel() { return FORMAT_LABELS.get(FORMATS.indexOf(format)); }
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
    public List<String> previewArgs(File input, File intermediate, double start) {
        if (!Double.isFinite(start) || start < 0) throw new IllegalArgumentException("Invalid preview position");
        List<String> args = base(); args.addAll(List.of("-v", "error", "-ss", number(start))); input(args, input);
        args.addAll(List.of("-t", "20", "-map", "0:a:0", "-vn", "-af", filter(), "-c:a", "pcm_f32le", "-rf64", "auto", intermediate.getAbsolutePath()));
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
        // Lossy encoders can overshoot on reconstruction. Leave additional headroom.
        double outputGain = gain * (format.equals("flac") || format.equals("wav") ? 1 : Math.pow(10, -2 / 20.0));
        args.addAll(List.of("-map", "0:a:0", "-map_metadata", "1", "-map_chapters", "-1", "-vn", "-af", "volume=" + number(outputGain)));
        switch (format) {
            case "flac" -> args.addAll(List.of("-c:a", "flac", "-sample_fmt", "s32", "-bits_per_raw_sample", "24", "-compression_level", "5"));
            case "wav" -> args.addAll(List.of("-c:a", "pcm_s24le", "-rf64", "auto"));
            case "mp3" -> args.addAll(List.of("-c:a", "libmp3lame", "-b:a", "320k", "-ar", "48000", "-ac", "2", "-id3v2_version", "3"));
            case "m4a" -> args.addAll(List.of("-c:a", "aac", "-b:a", "256k", "-ar", "48000", "-ac", "2", "-movflags", "+faststart"));
            case "opus" -> args.addAll(List.of("-c:a", "libopus", "-b:a", "192k", "-vbr", "on", "-compression_level", "10", "-ar", "48000", "-ac", "2"));
            case "ogg" -> args.addAll(List.of("-c:a", "libvorbis", "-q:a", "8", "-ar", "48000", "-ac", "2"));
            default -> throw new IllegalStateException("Unknown output format");
        }
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
