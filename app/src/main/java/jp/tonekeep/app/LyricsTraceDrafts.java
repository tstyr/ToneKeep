package jp.tonekeep.app;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class LyricsTraceDrafts {
    private final File root;
    LyricsTraceDrafts(Context context){root=new File(context.getNoBackupFilesDir(),"lyrics-trace-drafts");}
    static String fingerprint(DownloadStore.Entry entry,Lyrics doc){return hash(entry.id()+"|"+entry.contentUri()+"|"+doc.sourceHash()+"|"+ReferenceLyricsAlignment.text(doc));}
    private AtomicFile file(String key,boolean words){if(!key.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();return new AtomicFile(new File(root,key+(words?"-words":"-lines")+".json"));}
    String load(String key,boolean words){try{return new String(file(key,words).readFully(),StandardCharsets.UTF_8);}catch(IOException e){return "";}}
    void write(String key,boolean words,String json) throws IOException {if(!root.isDirectory()&&!root.mkdirs())throw new IOException("下書きを保存できません");AtomicFile file=file(key,words);FileOutputStream out=null;try{out=file.startWrite();out.write(json.getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(IOException e){if(out!=null)file.failWrite(out);throw e;}}
    void remove(String key,boolean words){file(key,words).delete();}
    private static String hash(String input){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return out.toString();}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
}
