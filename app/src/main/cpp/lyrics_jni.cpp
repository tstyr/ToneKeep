#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <memory>
#include <sstream>
#include <vector>
#include <algorithm>

namespace {
struct Session { whisper_context *ctx; std::atomic<bool> cancelled{false}; explicit Session(whisper_context *c) : ctx(c) {} };
void fail(JNIEnv *env, const char *message) { env->ThrowNew(env->FindClass("java/io/IOException"), message); }
std::string quoted(const std::string &s) {
    std::string out = "\"";
    for (unsigned char c : s) {
        if (c == '"' || c == '\\') { out += '\\'; out += c; }
        else if (c < 32) { const char *hex = "0123456789abcdef"; out += "\\u00"; out += hex[c >> 4]; out += hex[c & 15]; }
        else out += c;
    }
    return out + "\"";
}
}
extern "C" JNIEXPORT jlong JNICALL Java_jp_tonekeep_app_NativeWhisper_open(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    auto params = whisper_context_default_params(); params.use_gpu = false;
    whisper_context *ctx = nullptr;
    try { ctx = whisper_init_from_file_with_params(p, params); }
    catch (...) { env->ReleaseStringUTFChars(path, p); fail(env, "Insufficient native memory loading model"); return 0; }
    env->ReleaseStringUTFChars(path, p);
    if (!ctx) fail(env, "Whisper model could not be loaded (memory or invalid model)");
    if (!ctx) return 0;
    try { return reinterpret_cast<jlong>(new Session(ctx)); }
    catch (...) { whisper_free(ctx); fail(env, "Insufficient native memory"); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_jp_tonekeep_app_NativeWhisper_cancel(JNIEnv *, jclass, jlong handle) { reinterpret_cast<Session *>(handle)->cancelled = true; }
extern "C" JNIEXPORT void JNICALL Java_jp_tonekeep_app_NativeWhisper_close(JNIEnv *, jclass, jlong handle) { auto *s = reinterpret_cast<Session *>(handle); whisper_free(s->ctx); delete s; }
extern "C" JNIEXPORT jbyteArray JNICALL Java_jp_tonekeep_app_NativeWhisper_transcribe(JNIEnv *env, jclass, jlong handle, jfloatArray audio, jboolean japanese, jint threads) {
    try {
        auto *session = reinterpret_cast<Session *>(handle);
        auto *ctx = session ? session->ctx : nullptr;
        const jsize count = env->GetArrayLength(audio);
        if (!ctx || count <= 0 || count > 30 * WHISPER_SAMPLE_RATE) { fail(env, "Invalid audio window"); return nullptr; }
        std::vector<float> pcm(count); env->GetFloatArrayRegion(audio, 0, count, pcm.data());
        auto p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        p.n_threads = std::clamp(static_cast<int>(threads), 1, 4);
        p.language = japanese ? "ja" : "auto"; p.translate = false; p.no_context = true;
        p.token_timestamps = true; p.suppress_blank = true; p.suppress_nst = true;
        p.print_progress = p.print_realtime = p.print_timestamps = false;
        p.abort_callback = [](void *data) { return static_cast<Session *>(data)->cancelled.load(); };
        p.abort_callback_user_data = session;
        p.encoder_begin_callback = [](whisper_context *, whisper_state *, void *data) { return !static_cast<Session *>(data)->cancelled.load(); };
        p.encoder_begin_callback_user_data = session;
        const int result = whisper_full(ctx, p, pcm.data(), count);
        if (session->cancelled || result != 0) { fail(env, session->cancelled ? "Cancelled" : "Whisper inference failed"); return nullptr; }
        std::ostringstream out; out.imbue(std::locale::classic()); out << "[";
        for (int i = 0; i < whisper_full_n_segments(ctx); ++i) {
            if (i) out << ',';
            out << "{\"start\":" << whisper_full_get_segment_t0(ctx, i) * 10
                << ",\"end\":" << whisper_full_get_segment_t1(ctx, i) * 10
                << ",\"noSpeech\":" << whisper_full_get_segment_no_speech_prob(ctx, i)
                << ",\"tokens\":[";
            std::string text; bool first = true;
            for (int t = 0; t < whisper_full_n_tokens(ctx, i); ++t) {
                auto d = whisper_full_get_token_data(ctx, i, t);
                if (d.id >= whisper_token_eot(ctx)) continue;
                std::string piece = whisper_full_get_token_text(ctx, i, t);
                if (!first) out << ','; first = false;
                out << "{\"from\":" << text.size() << ",\"to\":" << text.size() + piece.size()
                    << ",\"start\":" << d.t0 * 10 << ",\"end\":" << d.t1 * 10 << ",\"p\":" << d.p << '}';
                text += piece;
            }
            out << "],\"text\":" << quoted(text) << '}';
        }
        out << ']'; const auto json = out.str();
        auto bytes = env->NewByteArray(static_cast<jsize>(json.size()));
        if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(json.size()), reinterpret_cast<const jbyte *>(json.data()));
        return bytes;
    } catch (const std::bad_alloc &) { fail(env, "Insufficient native memory"); return nullptr; }
      catch (...) { fail(env, "Native inference error"); return nullptr; }
}
