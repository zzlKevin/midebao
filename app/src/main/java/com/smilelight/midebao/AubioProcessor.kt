package com.smilelight.midebao

class AubioProcessor {
    // 存储 JNI 指针的字段（必须与 C 代码中的字段名一致）
    private val ptr: Long = 0
    private val input: Long = 0
    private val tempoOut: Long = 0   // 新增

    external fun initTempo(sampleRate: Int, bufferSize: Int)
    external fun getTempo(input: FloatArray?): Float
    external fun getLastBeatSample(): Long

    external fun cleanupTempo()

    companion object {
        init {
            System.loadLibrary("pitch")
        }
    }
}