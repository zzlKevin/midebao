package com.smilelight.midebao

import com.smilelight.midebao.audio.AubioProcessorLike

/**
 * aubio 节拍检测的 JNI 封装。
 *
 * 职责：
 * 1. 通过 JNI 调用 C 层 aubio tempo 算法，完成 BPM 估计与节拍定位。
 * 2. 实现 [AubioProcessorLike] 接口，供 [com.smilelight.midebao.audio.BeatTracker]
 *    以纯 Kotlin 方式调用，便于单元测试用 mock 替换。
 *
 * 注意：
 * - 本类依赖 native 库 `pitch`，只能在 Android 设备上运行；
 *   单元测试请使用实现了 [AubioProcessorLike] 的 mock。
 * - 所有 external 方法签名与 `app/src/main/jni/pitch.c` 中的 JNI 函数一一对应。
 */
class AubioProcessor : AubioProcessorLike {
    // 存储 JNI 指针的字段（必须与 C 代码中的字段名一致）
    private val ptr: Long = 0
    private val input: Long = 0
    private val tempoOut: Long = 0

    /** 初始化 aubio tempo 对象。 */
    external fun initTempo(sampleRate: Int, bufferSize: Int)

    /**
     * 输入一帧采样，返回当前 BPM。
     * 实现 [AubioProcessorLike.getTempo]。
     */
    override external fun getTempo(input: FloatArray): Float

    /**
     * 返回最近一次节拍对应的样本位置。
     * 实现 [AubioProcessorLike.getLastBeatSample]。
     */
    override external fun getLastBeatSample(): Long

    /** 释放 aubio tempo 对象。 */
    external fun cleanupTempo()

    companion object {
        init {
            System.loadLibrary("pitch")
        }
    }
}
