package jp.tonekeep.app;

import android.content.Context;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Only model downloads use the network; immutable revision + LFS SHA-256 pin the artifact. */
final class ModelManager {
    record Spec(String id, long bytes, String sha256, long recommendedMemory) { }
    static final String REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1";
    static final List<Spec> MODELS = List.of(
            new Spec("small", 487601967L, "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b", 1_500_000_000L),
            new Spec("medium", 1533763059L, "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208", 3_500_000_000L));
    interface Progress { void update(int percent, String message); }
    private final File root;
    ModelManager(Context context) { root = new File(context.getNoBackupFilesDir(), "lyrics-models"); }
    static Spec spec(String id) { return MODELS.stream().filter(s -> s.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("未対応のモデル")); }
    File file(String id) { spec(id); return new File(root, "ggml-" + id + ".bin"); }
    boolean installed(String id) { return file(id).isFile() && file(id).length() == spec(id).bytes; }
    long partialBytes(String id) { return new File(root, spec(id).id + ".part").length(); }
    void delete(String id) throws IOException { Files.deleteIfExists(file(id).toPath()); Files.deleteIfExists(new File(root, id + ".part").toPath()); }
    File verified(String id, BooleanSupplier active) throws Exception {
        File f = file(id); Spec s = spec(id);
        if(f.length()!=s.bytes)throw new IOException("認識データの取得が完了していません。再試行してください。");
        if(!hash(f,active).equals(s.sha256))throw new IOException("認識データが破損しています。再試行で取得し直します。");
        return f;
    }
    void download(String id, BooleanSupplier active, Progress progress) throws Exception {
        Spec s = spec(id); if (!root.isDirectory() && !root.mkdirs()) throw new IOException("モデル保存先を作成できません");
        if (installed(id)) { try{verified(id,active);return;}catch(IOException corrupt){Files.deleteIfExists(file(id).toPath());} }
        File partial = new File(root, id + ".part"); long offset = partial.length();
        if (offset > s.bytes) { Files.delete(partial.toPath()); offset = 0; }
        if (root.getUsableSpace() < s.bytes - offset + 128L * 1024 * 1024) throw new IOException("モデル用の空き容量が不足しています");
        if (offset < s.bytes) {
            progress.update((int)(offset*100/s.bytes),"認識データの取得に接続しています · "+AudioFile.sizeLabel(offset)+" / "+AudioFile.sizeLabel(s.bytes));
            HttpURLConnection connection = (HttpURLConnection) new URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/" + REVISION + "/ggml-" + id + ".bin").openConnection();
            connection.setConnectTimeout(15000); connection.setReadTimeout(15000); connection.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            try {
                int code = connection.getResponseCode();
                if (code == 200) offset = 0;
                else if (code != 206) throw new IOException("モデル取得に失敗しました HTTP " + code);
                if (code == 206 && !validRange(connection.getHeaderField("Content-Range"), offset, s.bytes)) throw new IOException("再開位置が一致しません");
                long received = offset, last = 0;
                try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(partial, offset > 0)) {
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        require(active); if (received + n > s.bytes) throw new IOException("モデルサイズが不正です");
                        out.write(buffer, 0, n); received += n;
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - last > 700) { progress.update((int)(received * 100 / s.bytes), "モデル取得 " + AudioFile.sizeLabel(received) + " / " + AudioFile.sizeLabel(s.bytes)); last = now; }
                    }
                    out.getFD().sync();
                }
            } catch(java.net.SocketTimeoutException | java.net.UnknownHostException unavailable){throw new IOException("通信が途切れました。再試行で取得を続きから再開できます。",unavailable);} finally { connection.disconnect(); }
        }
        require(active); progress.update(-1, "モデルのSHA-256を検証しています");
        if (partial.length() != s.bytes) throw new IOException("ダウンロードが中断されました。再開できます");
        if (!hash(partial, active).equals(s.sha256)) { Files.delete(partial.toPath()); throw new IOException("SHA-256が一致しません。再取得してください"); }
        require(active); Files.move(partial.toPath(), file(id).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    static boolean validRange(String header, long offset, long total) { return header != null && header.matches("bytes " + offset + "-" + (total - 1) + "/" + total); }
    static String hash(File file, BooleanSupplier active) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) { require(active); md.update(b, 0, n); } }
        StringBuilder out = new StringBuilder(); for (byte b : md.digest()) out.append(Character.forDigit((b >>> 4) & 15,16)).append(Character.forDigit(b & 15,16)); return out.toString();
    }
    static void require(BooleanSupplier active) throws InterruptedException { if (Thread.currentThread().isInterrupted() || !active.getAsBoolean()) throw new InterruptedException("中止しました"); }
}
