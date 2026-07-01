// 修改后的 pitch.c（现在用于 BPM 检测）
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <jni.h>
#include "aubio.h"
#include <android/log.h>
#include <math.h>

#define LOG_TAG "AubioNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// 在文件顶部添加 tempo_out 的 field id（与 Java 中的字段对应）
jfieldID getTempoOutFieldId(JNIEnv * env, jobject obj)
{
    static jfieldID ptrFieldId = 0;
    if (!ptrFieldId) {
        jclass c = (*env)->GetObjectClass(env, obj);
        ptrFieldId = (*env)->GetFieldID(env, c, "tempoOut", "J");
        (*env)->DeleteLocalRef(env, c);
    }
    return ptrFieldId;
}
// 注意：这些 getter 函数不变，因为我们需要存储 JNI 对象中的指针
jfieldID getPtrFieldId(JNIEnv * env, jobject obj)
{
    static jfieldID ptrFieldId = 0;
    if (!ptrFieldId) {
        jclass c = (*env)->GetObjectClass(env, obj);
        ptrFieldId = (*env)->GetFieldID(env, c, "ptr", "J");
        (*env)->DeleteLocalRef(env, c);
    }
    return ptrFieldId;
}

jfieldID getInputFieldId(JNIEnv * env, jobject obj)
{
    static jfieldID ptrFieldId = 0;
    if (!ptrFieldId) {
        jclass c = (*env)->GetObjectClass(env, obj);
        ptrFieldId = (*env)->GetFieldID(env, c, "input", "J");
        (*env)->DeleteLocalRef(env, c);
    }
    return ptrFieldId;
}

// 注意：我们移除了 pitch 字段，因为不需要存储 pitch 输出（tempo 输出是 BPM，直接返回即可）

// 初始化 tempo 对象（对应 Java 中的 initTempo）
void Java_com_smilelight_midebao_AubioProcessor_initTempo(JNIEnv * env, jobject obj, jint sampleRate, jint bufferSize)
{
//    unsigned int win_s = (unsigned int) bufferSize * 2; // 4096
//    unsigned int hop_s = (unsigned int) bufferSize;     // 2048
//    unsigned int samplerate = (unsigned int) sampleRate;
    unsigned int win_s = 2048;          // 窗口大小（采样点）
    unsigned int hop_s = 512;           // 步长（每次处理 512 采样）
    unsigned int samplerate = 16000;
    // 创建 tempo 对象
    aubio_tempo_t * o = new_aubio_tempo("default", win_s, hop_s, samplerate);
    // 降低阈值，更易触发
    aubio_tempo_set_threshold(o, 0.1);  // 默认0.3，降为0.1
//    aubio_tempo_t * o = new_aubio_tempo("complex", win_s, hop_s, samplerate);
    fvec_t *input = new_fvec(hop_s);        // 输入缓冲区
    fvec_t *tempoOut = new_fvec(2);         // 输出缓冲区（存放节拍信息）

    (*env)->SetLongField(env, obj, getPtrFieldId(env, obj), (jlong)(o));
    (*env)->SetLongField(env, obj, getInputFieldId(env, obj), (jlong)(input));
    (*env)->SetLongField(env, obj, getTempoOutFieldId(env, obj), (jlong)(tempoOut));

}

// 处理音频并返回 BPM（对应 Java 中的 getTempo）
jfloat Java_com_smilelight_midebao_AubioProcessor_getTempo(JNIEnv * env, jobject obj, jfloatArray inputArray)
{
    aubio_tempo_t * o = (aubio_tempo_t *)(*env)->GetLongField(env, obj, getPtrFieldId(env, obj));
    fvec_t *input = (fvec_t *)(*env)->GetLongField(env, obj, getInputFieldId(env, obj));
    fvec_t *tempoOut = (fvec_t *)(*env)->GetLongField(env, obj, getTempoOutFieldId(env, obj));

    jsize len = (*env)->GetArrayLength(env, inputArray);
    if (len != input->length) {
        return -1; // 长度不匹配
    }

    jfloat *body = (*env)->GetFloatArrayElements(env, inputArray, 0);
    for (u_int i = 0; i < len; i++) {
        fvec_set_sample(input, body[i], i);
    }
    (*env)->ReleaseFloatArrayElements(env, inputArray, body, 0);

    aubio_tempo_do(o, input, tempoOut);

    // 检查是否检测到节拍
    uint_t last_beat = aubio_tempo_get_last(o);
    float bpm = aubio_tempo_get_bpm(o);

    // 日志过滤：仅当 bpm 的整数部分变化时才打印
    static float last_logged_bpm = -999.0f;
    if (fabsf(bpm - last_logged_bpm) >= 1.0f) {
        LOGI("getTempo: last_beat=%u, bpm=%f", last_beat, bpm);
        last_logged_bpm = bpm;
    }
    return bpm;
}

// 新增：获取节拍样本位置
JNIEXPORT jlong JNICALL
Java_com_smilelight_midebao_AubioProcessor_getLastBeatSample(JNIEnv *env, jobject obj) {
    aubio_tempo_t * o = (aubio_tempo_t *)(*env)->GetLongField(env, obj, getPtrFieldId(env, obj));
    if (o == NULL) return 0;
    uint_t last_beat = aubio_tempo_get_last(o);  // ✅ 正确：只需一个参数[reference:2]
    return (jlong)last_beat;
}



// 清理资源（对应 Java 中的 cleanupTempo）
void Java_com_smilelight_midebao_AubioProcessor_cleanupTempo(JNIEnv * env, jobject obj)
{
    aubio_tempo_t * o = (aubio_tempo_t *)(*env)->GetLongField(env, obj, getPtrFieldId(env, obj));
    fvec_t *input = (fvec_t *)(*env)->GetLongField(env, obj, getInputFieldId(env, obj));
    fvec_t *tempoOut = (fvec_t *)(*env)->GetLongField(env, obj, getTempoOutFieldId(env, obj));
    del_aubio_tempo(o);
    del_fvec(input);
    del_fvec(tempoOut);
    aubio_cleanup();
}

