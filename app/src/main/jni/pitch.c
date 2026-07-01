// 修改后的 pitch.c（现在用于 BPM 检测）
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <jni.h>
#include "aubio.h"

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
    unsigned int win_s = (unsigned int) bufferSize;
    unsigned int hop_s = win_s / 2;  // 改为与 win_s 相等
//    unsigned int win_s = 1024;           // 改为 1024
//    unsigned int hop_s = 256;            // 改为 256
    unsigned int samplerate = (unsigned int) sampleRate;

    // 创建 tempo 对象
//    aubio_tempo_t * o = new_aubio_tempo("default", win_s, hop_s, samplerate);
    aubio_tempo_t * o = new_aubio_tempo("complex", win_s, hop_s, samplerate);
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
    if (last_beat == 0) {
        return -1;  // 尚未检测到
    }
    float bpm = aubio_tempo_get_bpm(o);
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

