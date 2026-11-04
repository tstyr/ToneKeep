package jp.tonekeep.app;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import org.jtransforms.fft.FloatFFT_1D;

/** UVR MDX Voc_FT inference on bounded overlapping windows of 44.1 kHz stereo float PCM.
 * Tensor layout and periodic Hann STFT follow the upstream UVR/sherpa-onnx implementation.
 * No Android dependencies: the same pipeline is exercised by the host integration runner.
 */
final class VocalSeparator implements AutoCloseable {
    static final int RATE = 44100, FFT = 6144, HOP = 1024, BINS = 3072, FRAMES = 256;
    static final int WINDOW = HOP * (FRAMES - 1), CONTEXT = RATE + FFT / 2;
    static final int STEP = WINDOW - 2 * CONTEXT;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final OrtSession.RunOptions runOptions = new OrtSession.RunOptions();
    private final FloatFFT_1D fft = new FloatFFT_1D(FFT);
    private final float[] window = new float[FFT];
    private final FloatBuffer inputBuffer = ByteBuffer.allocateDirect(4 * BINS * FRAMES * Float.BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private boolean closed;

    VocalSeparator(File model) throws Exception {
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.min(2, Runtime.getRuntime().availableProcessors()));
            options.setInterOpNumThreads(1);
            session = environment.createSession(model.getAbsolutePath(), options);
        }
        for (int i = 0; i < FFT; i++) window[i] = (float) (.5 - .5 * Math.cos(2 * Math.PI * i / FFT));
        var info = session.getInputInfo().get("input");
        if (info == null || !(info.getInfo() instanceof ai.onnxruntime.TensorInfo tensor)
                || !java.util.Arrays.equals(java.util.Arrays.copyOfRange(tensor.getShape(), 1, 4), new long[]{4, BINS, FRAMES})) {
            close(); throw new IOException("分離モデルの形式が一致しません。");
        }
    }
    synchronized void cancel() {
        if (!closed) try { runOptions.setTerminate(true); } catch (ai.onnxruntime.OrtException ignored) { }
    }
    void separate(File input, File output, BooleanSupplier active, IntConsumer progress) throws Exception {
        separate(input, output, VocalQuality.STANDARD, active, progress);
    }
    void separate(File input, File output, VocalQuality quality, BooleanSupplier active, IntConsumer progress) throws Exception {
        if (input.length() == 0 || input.length() % 8 != 0) throw new IOException("音声データを読み込めません。");
        long total = input.length() / 8;
        int stride = quality == VocalQuality.HIGH ? STEP / 2 : STEP;
        float[][] accumulated = new float[2][STEP]; float[] weights = new float[STEP];
        float[] blend = new float[STEP];
        for (int i = 0; i < STEP; i++) blend[i] = quality == VocalQuality.HIGH
                ? (float) Math.pow(Math.sin(Math.PI * (i + .5) / STEP), 2) : 1;
        try (RandomAccessFile source = new RandomAccessFile(input, "r");
             BufferedOutputStream destination = new BufferedOutputStream(new FileOutputStream(output))) {
            for (long position = 0; position < total; position += stride) {
                requireActive(active);
                float[][] samples = readWindow(source, total, position - CONTEXT);
                float[] spectrum = stft(samples, active);
                float[] separated = infer(spectrum, active);
                if (quality == VocalQuality.HIGH) {
                    // MDX denoise: cancel the model's polarity-independent prediction bias.
                    for (int i = 0; i < spectrum.length; i++) spectrum[i] = -spectrum[i];
                    float[] negative = infer(spectrum, active);
                    for (int i = 0; i < separated.length; i++) separated[i] = (separated[i] - negative[i]) * .5f;
                }
                float[][] vocals = istft(separated, active);
                int count = (int) Math.min(STEP, total - position);
                for (int i = 0; i < count; i++) {
                    // Normalize a 50% overlap-add; the first/last windows keep exact length.
                    float weight = blend[i];
                    weights[i] += weight;
                    for (int channel = 0; channel < 2; channel++) accumulated[channel][i] += vocals[channel][CONTEXT + i] * weight;
                }
                int emitted = (int) Math.min(stride, total - position);
                ByteBuffer bytes = ByteBuffer.allocate(emitted * 8).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < emitted; i++) for (int channel = 0; channel < 2; channel++) {
                    float value = accumulated[channel][i] / weights[i];
                    if (!Float.isFinite(value)) throw new IOException("分離結果に無効な音声が含まれます。");
                    bytes.putFloat(value);
                }
                requireActive(active); destination.write(bytes.array());
                progress.accept((int) (100 * (position + emitted) / total));
                for (float[] channel : accumulated) {
                    System.arraycopy(channel, stride, channel, 0, STEP - stride);
                    java.util.Arrays.fill(channel, STEP - stride, STEP, 0);
                }
                System.arraycopy(weights, stride, weights, 0, STEP - stride);
                java.util.Arrays.fill(weights, STEP - stride, STEP, 0);
            }
        }
    }
    private float[] infer(float[] spectrum, BooleanSupplier active) throws Exception {
        requireActive(active);
        // Reuse direct input storage across both polarities and all windows.
        inputBuffer.clear(); inputBuffer.put(spectrum); inputBuffer.flip();
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, inputBuffer, new long[]{1, 4, BINS, FRAMES});
             OrtSession.Result result = session.run(Map.of("input", tensor), runOptions)) {
            requireActive(active);
            FloatBuffer prediction = ((OnnxTensor) result.get(0)).getFloatBuffer();
            if (prediction.hasArray() && prediction.arrayOffset() == 0 && prediction.remaining() == prediction.array().length) return prediction.array();
            float[] separated = new float[spectrum.length]; prediction.get(separated);
            return separated;
        }
    }
    private static float[][] readWindow(RandomAccessFile source, long total, long start) throws IOException {
        float[][] samples = new float[2][WINDOW];
        long first = Math.max(0, start), last = Math.min(total, start + WINDOW);
        int count = (int) Math.max(0, last - first);
        byte[] data = new byte[count * 8]; source.seek(first * 8); source.readFully(data);
        FloatBuffer floats = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        int offset = (int) (first - start);
        for (int i = 0; i < count; i++) for (int channel = 0; channel < 2; channel++) samples[channel][offset + i] = floats.get();
        return samples;
    }
    float[] stft(float[][] samples, BooleanSupplier active) throws InterruptedException {
        float[] spectrum = new float[4 * BINS * FRAMES], frame = new float[2 * FFT];
        for (int channel = 0; channel < 2; channel++) for (int t = 0; t < FRAMES; t++) {
            requireActive(active);
            for (int i = 0; i < FFT; i++) {
                int index = t * HOP + i - FFT / 2;
                // Reflect padding matches a centered STFT; outer track padding is zero.
                if (index < 0) index = -index;
                if (index >= WINDOW) index = 2 * WINDOW - 2 - index;
                frame[2 * i] = samples[channel][index] * window[i]; frame[2 * i + 1] = 0;
            }
            fft.complexForward(frame);
            for (int f = 0; f < BINS; f++) {
                spectrum[(2 * channel * BINS + f) * FRAMES + t] = frame[2 * f];
                spectrum[((2 * channel + 1) * BINS + f) * FRAMES + t] = frame[2 * f + 1];
            }
        }
        return spectrum;
    }
    float[][] istft(float[] spectrum, BooleanSupplier active) throws InterruptedException {
        float[][] samples = new float[2][WINDOW]; float[] normalization = new float[WINDOW], frame = new float[2 * FFT];
        for (int t = 0; t < FRAMES; t++) for (int i = 0; i < FFT; i++) {
            int index = t * HOP + i - FFT / 2;
            if (index >= 0 && index < WINDOW) normalization[index] += window[i] * window[i];
        }
        for (int channel = 0; channel < 2; channel++) for (int t = 0; t < FRAMES; t++) {
            requireActive(active); java.util.Arrays.fill(frame, 0);
            for (int f = 0; f < BINS; f++) {
                float real = spectrum[(2 * channel * BINS + f) * FRAMES + t];
                float imaginary = spectrum[((2 * channel + 1) * BINS + f) * FRAMES + t];
                frame[2 * f] = real; frame[2 * f + 1] = imaginary;
                if (f > 0) { frame[2 * (FFT - f)] = real; frame[2 * (FFT - f) + 1] = -imaginary; }
            }
            fft.complexInverse(frame, true);
            for (int i = 0; i < FFT; i++) {
                int index = t * HOP + i - FFT / 2;
                if (index >= 0 && index < WINDOW) samples[channel][index] += frame[2 * i] * window[i];
            }
        }
        for (float[] channel : samples) for (int i = 0; i < WINDOW; i++) channel[i] /= Math.max(1e-8f, normalization[i]);
        return samples;
    }
    private static void requireActive(BooleanSupplier active) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || !active.getAsBoolean()) throw new InterruptedException("ボーカル抽出を中止しました。");
    }
    @Override public synchronized void close() throws ai.onnxruntime.OrtException {
        if (!closed) { closed = true; session.close(); runOptions.close(); }
    }
}
