package jp.tonekeep.app;

/** JNI has no networking. Only the serial LyricsService worker owns model contexts. */
final class NativeWhisper {
    static { System.loadLibrary("tonekeep_lyrics"); }
    static native long open(String path) throws java.io.IOException;
    static native byte[] transcribe(long handle, float[] pcm, boolean japanese, int threads) throws java.io.IOException;
    static native void cancel(long handle);
    static native void close(long handle);
    private NativeWhisper() { }
}
