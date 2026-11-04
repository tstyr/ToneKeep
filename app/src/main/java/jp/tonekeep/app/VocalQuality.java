package jp.tonekeep.app;

enum VocalQuality {
    STANDARD("standard", "標準"), HIGH("high", "高精度");
    final String id, label;
    VocalQuality(String id, String label) { this.id = id; this.label = label; }
    static VocalQuality fromId(String id) {
        for (VocalQuality quality : values()) if (quality.id.equals(id)) return quality;
        throw new IllegalArgumentException("対応するボーカル抽出モードを選択してください。");
    }
}
