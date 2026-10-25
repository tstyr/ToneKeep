package jp.tonekeep.app;

import java.util.Locale;

final class AudioFile {
    private AudioFile() { }
    static String sizeLabel(long bytes) {
        if (bytes <= 0) return "容量不明";
        return bytes >= 1_073_741_824L ? String.format(Locale.JAPAN, "%.2f GB", bytes / 1_073_741_824.0)
                : String.format(Locale.JAPAN, "%.1f MB", bytes / 1_048_576.0);
    }
}
