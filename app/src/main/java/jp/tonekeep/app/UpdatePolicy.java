package jp.tonekeep.app;

import java.util.Set;

final class UpdatePolicy {
    static void validate(String installedPackage, long installedVersion, Set<String> installedSigners,
                         String candidatePackage, long candidateVersion, Set<String> candidateSigners) {
        if (!installedPackage.equals(candidatePackage)) throw new IllegalArgumentException("このAPKはToneKeepの現在のアプリIDと一致しません。");
        if (candidateVersion <= installedVersion) throw new IllegalArgumentException("現在より新しいバージョンのAPKを選択してください。");
        if (installedSigners.isEmpty() || !installedSigners.equals(candidateSigners))
            throw new IllegalArgumentException("APKの署名が現在のアプリと一致しません。公式の配布ファイルを選択してください。");
    }
}
