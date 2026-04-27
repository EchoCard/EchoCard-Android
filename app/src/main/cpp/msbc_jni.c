/*
 * msbc_jni.c — JNI bridge for the Google libsbc mSBC encoder.
 *
 * Mirrors iOS CallMate/Core/Audio/MSBCCodec.swift `MSBCEncoder` — per-instance
 * `sbc_t` context (libsbc is not thread-safe, so callers keep an instance to
 * themselves) and encodes 120-sample mono 16 kHz PCM (Int16) frames into
 * 57-byte SBC frames. The MCU attaches the 2-byte H2 header itself on eSCO TX.
 *
 * Written in plain C because libsbc's `sbc.h` relies on `stdalign.h` / C11
 * attribute positions that the NDK clang++ C++17 front-end rejects; the JNI
 * ABI is the same regardless of source language.
 */

#include <jni.h>
#include <stdlib.h>
#include <stdbool.h>
#include <stdint.h>

#include "sbc.h"

typedef struct {
    sbc_t ctx;
    struct sbc_frame frame;
} MsbcEncoderCtx;

static jint throwIllegalState(JNIEnv* env, const char* msg) {
    jclass ex = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (ex) (*env)->ThrowNew(env, ex, msg);
    return -1;
}

JNIEXPORT jlong JNICALL
Java_com_vaca_callmate_core_audio_MsbcNative_encoderCreate(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    MsbcEncoderCtx* enc = (MsbcEncoderCtx*)calloc(1, sizeof(MsbcEncoderCtx));
    if (enc == NULL) return 0;
    sbc_reset(&enc->ctx);
    enc->frame.msbc = true;
    enc->frame.freq = SBC_FREQ_16K;
    enc->frame.mode = SBC_MODE_MONO;
    enc->frame.bam = SBC_BAM_LOUDNESS;
    enc->frame.nblocks = 0;
    enc->frame.nsubbands = 0;
    enc->frame.bitpool = 0;
    return (jlong)(intptr_t)enc;
}

JNIEXPORT void JNICALL
Java_com_vaca_callmate_core_audio_MsbcNative_encoderDestroy(JNIEnv* env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    if (handle == 0) return;
    free((void*)(intptr_t)handle);
}

/*
 * Encode `frames` mSBC frames (each 120 Int16 samples) at pcm[pcmOffset…] into
 * out[outOffset…]. Returns number of bytes written (== frames * 57) on success,
 * or a negative value on error.
 */
JNIEXPORT jint JNICALL
Java_com_vaca_callmate_core_audio_MsbcNative_encoderEncode(
        JNIEnv* env, jobject thiz, jlong handle,
        jshortArray pcm, jint pcmOffset, jint frames,
        jbyteArray out, jint outOffset) {
    (void)thiz;
    if (handle == 0) return throwIllegalState(env, "encoder closed");
    if (frames < 1) return throwIllegalState(env, "frames < 1");

    MsbcEncoderCtx* enc = (MsbcEncoderCtx*)(intptr_t)handle;
    const int samplesPerFrame = 120;
    const int bytesPerFrame = 57;

    jsize pcmLen = (*env)->GetArrayLength(env, pcm);
    const int samplesNeeded = frames * samplesPerFrame;
    if (pcmOffset < 0 || pcmOffset + samplesNeeded > pcmLen) {
        return throwIllegalState(env, "bad pcm range");
    }
    jsize outLen = (*env)->GetArrayLength(env, out);
    const int bytesNeeded = frames * bytesPerFrame;
    if (outOffset < 0 || outOffset + bytesNeeded > outLen) {
        return throwIllegalState(env, "bad output buffer");
    }

    jshort* pcmElems = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (pcmElems == NULL) return -1;
    jbyte* outElems = (*env)->GetByteArrayElements(env, out, NULL);
    if (outElems == NULL) {
        (*env)->ReleaseShortArrayElements(env, pcm, pcmElems, JNI_ABORT);
        return -1;
    }

    int written = 0;
    int rc = 0;
    for (int i = 0; i < frames; i++) {
        const int16_t* inPtr = (const int16_t*)(pcmElems + pcmOffset + i * samplesPerFrame);
        void* outPtr = (uint8_t*)outElems + outOffset + i * bytesPerFrame;
        rc = sbc_encode(&enc->ctx,
                        inPtr, 1,
                        NULL, 0,
                        &enc->frame,
                        outPtr, (unsigned)bytesPerFrame);
        if (rc != 0) break;
        written += bytesPerFrame;
    }

    (*env)->ReleaseShortArrayElements(env, pcm, pcmElems, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, outElems, 0);

    if (rc != 0) {
        return -2;
    }
    return (jint)written;
}
