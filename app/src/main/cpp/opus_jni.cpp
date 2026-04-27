#include <jni.h>
#include <opus.h>
#include <cstring>

namespace {

jint throwIllegalState(JNIEnv* env, const char* msg) {
    jclass ex = env->FindClass("java/lang/IllegalStateException");
    if (ex) env->ThrowNew(ex, msg);
    return -1;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_vaca_callmate_core_audio_LibOpusNative_encoderCreate(JNIEnv* env, jobject /*thiz*/, jint sampleRate,
                                                              jint channels) {
    int err = OPUS_OK;
    OpusEncoder* enc = opus_encoder_create(sampleRate, channels, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || enc == nullptr) {
        return 0;
    }
    err = opus_encoder_ctl(enc, OPUS_SET_BITRATE(24000));
    if (err != OPUS_OK) {
        opus_encoder_destroy(enc);
        return 0;
    }
    return reinterpret_cast<jlong>(enc);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vaca_callmate_core_audio_LibOpusNative_encoderDestroy(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    opus_encoder_destroy(reinterpret_cast<OpusEncoder*>(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_vaca_callmate_core_audio_LibOpusNative_encoderEncode(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                            jshortArray pcm, jint pcmOffset, jint frameSamples,
                                                            jbyteArray out, jint outOffset, jint maxOutBytes) {
    if (handle == 0) return throwIllegalState(env, "encoder closed");
    auto* enc = reinterpret_cast<OpusEncoder*>(handle);
    jsize pcmLen = env->GetArrayLength(pcm);
    if (pcmOffset < 0 || frameSamples < 1 || pcmOffset + frameSamples > pcmLen) {
        return throwIllegalState(env, "bad pcm range");
    }
    jsize outLen = env->GetArrayLength(out);
    if (outOffset < 0 || maxOutBytes < 1 || outOffset + maxOutBytes > outLen) {
        return throwIllegalState(env, "bad output buffer");
    }
    jshort* pcmElems = env->GetShortArrayElements(pcm, nullptr);
    if (!pcmElems) return -1;
    jbyte* outElems = env->GetByteArrayElements(out, nullptr);
    if (!outElems) {
        env->ReleaseShortArrayElements(pcm, pcmElems, JNI_ABORT);
        return -1;
    }
    const opus_int16* pcmPtr = reinterpret_cast<const opus_int16*>(pcmElems + pcmOffset);
    unsigned char* outPtr = reinterpret_cast<unsigned char*>(outElems + outOffset);
    int n = opus_encode(enc, pcmPtr, frameSamples, outPtr, maxOutBytes);
    env->ReleaseShortArrayElements(pcm, pcmElems, JNI_ABORT);
    env->ReleaseByteArrayElements(out, outElems, 0);
    return static_cast<jint>(n);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_vaca_callmate_core_audio_LibOpusNative_decoderCreate(JNIEnv* env, jobject /*thiz*/, jint sampleRate,
                                                              jint channels) {
    int err = OPUS_OK;
    OpusDecoder* dec = opus_decoder_create(sampleRate, channels, &err);
    if (err != OPUS_OK || dec == nullptr) {
        return 0;
    }
    return reinterpret_cast<jlong>(dec);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vaca_callmate_core_audio_LibOpusNative_decoderDestroy(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    opus_decoder_destroy(reinterpret_cast<OpusDecoder*>(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_vaca_callmate_core_audio_LibOpusNative_decoderDecode(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                              jbyteArray opus, jint opusOffset, jint opusLen,
                                                              jshortArray pcm, jint pcmOffset, jint frameSamples,
                                                              jboolean decodeFec) {
    if (handle == 0) return throwIllegalState(env, "decoder closed");
    auto* dec = reinterpret_cast<OpusDecoder*>(handle);
    if (opusLen < 0) return throwIllegalState(env, "bad opus length");
    jsize opusArrLen = env->GetArrayLength(opus);
    if (opusOffset < 0 || opusOffset + opusLen > opusArrLen) {
        return throwIllegalState(env, "bad opus range");
    }
    jsize pcmLen = env->GetArrayLength(pcm);
    if (pcmOffset < 0 || frameSamples < 1 || pcmOffset + frameSamples > pcmLen) {
        return throwIllegalState(env, "bad pcm buffer");
    }
    jbyte* opusElems = env->GetByteArrayElements(opus, nullptr);
    if (!opusElems) return -1;
    jshort* pcmElems = env->GetShortArrayElements(pcm, nullptr);
    if (!pcmElems) {
        env->ReleaseByteArrayElements(opus, opusElems, JNI_ABORT);
        return -1;
    }
    const unsigned char* inPtr = reinterpret_cast<const unsigned char*>(opusElems + opusOffset);
    opus_int16* pcmPtr = reinterpret_cast<opus_int16*>(pcmElems + pcmOffset);
    int fec = decodeFec ? 1 : 0;
    int n = opus_decode(dec, inPtr, opusLen, pcmPtr, frameSamples, fec);
    env->ReleaseByteArrayElements(opus, opusElems, JNI_ABORT);
    env->ReleaseShortArrayElements(pcm, pcmElems, 0);
    return static_cast<jint>(n);
}
