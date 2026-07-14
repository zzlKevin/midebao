package com.smilelight.midebao.audio

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 音频特征提取器：从一帧 PCM 浮点采样中提取多维特征。
 *
 * 设计原则：
 * 1. 纯函数式（除 FFT 实例外无内部可变状态），便于单元测试。
 * 2. 不依赖 Android API，可在 JVM 单元测试中直接运行。
 * 3. 输出 [AudioFeatures] 数据类，所有字段都归一化到 [0,1] 或 [-1,1]，
 *    便于后续 [MusicStateTracker] 与 [ActionMapper] 统一处理。
 *
 * 提取的特征：
 * - rms：均方根能量（响度近似）。
 * - spectralCentroid：频谱质心（音色亮度），归一化到 [0,1]。
 * - spectralFlux：相邻帧频谱变化量（onset 强度的近似），归一化到 [0,1]。
 * - onsetStrength：综合 onset 强度（flux + 低频能量增量），归一化到 [0,1]。
 * - bassEnergy：低频段（0~250Hz）能量占比，归一化到 [0,1]。
 * - midEnergy：中频段（250~2000Hz）能量占比，归一化到 [0,1]。
 * - highEnergy：高频段（2000Hz 以上）能量占比，归一化到 [0,1]。
 * - totalEnergy：总能量（用于 PE 计算）。
 */
class AudioFeatureExtractor(
    /** 采样率（Hz）。 */
    val sampleRate: Int,
    /** FFT 窗口大小，必须为 2 的幂。 */
    val fftSize: Int = 2048
) {
    private val fft = DoubleFFT_1D(fftSize.toLong())

    /** 频率分辨率（Hz/bin）。 */
    private val binHz: Double = sampleRate.toDouble() / fftSize

    /** 低频段上限（Hz）。 */
    private val bassUpperHz = 250.0
    /** 中频段上限（Hz）。 */
    private val midUpperHz = 2000.0

    init {
        require(sampleRate > 0) { "sampleRate 必须为正" }
        require(fftSize > 0 && (fftSize and (fftSize - 1)) == 0) {
            "fftSize 必须为 2 的幂，当前为 $fftSize"
        }
    }

    /**
     * 从一帧采样中提取特征。
     *
     * @param samples         归一化到 [-1,1] 的浮点采样（长度可小于 fftSize，会补零）。
     * @param previousSpectrum 上一帧的幅度谱（长度 fftSize/2+1），用于计算 flux；
     *                         首帧传 null。
     * @return 本帧特征；同时通过 [lastSpectrum] 暴露幅度谱供下一帧使用。
     */
    fun extract(
        samples: FloatArray,
        previousSpectrum: DoubleArray? = null
    ): AudioFeatures {
        // 1. 加窗 + 补零到 fftSize
        val windowed = DoubleArray(fftSize)
        val n = minOf(samples.size, fftSize)
        for (i in 0 until n) {
            // Hann 窗，降低频谱泄漏
            val w = 0.5 * (1 - Math.cos(2 * Math.PI * i / (fftSize - 1)))
            windowed[i] = samples[i].toDouble() * w
        }

        // 2. FFT（jtransforms 原地变换，结果长度 fftSize，前半为实部后半为虚部）
        fft.realForward(windowed)
        val half = fftSize / 2
        val magnitude = DoubleArray(half + 1)
        // bin 0（直流）只有实部
        magnitude[0] = abs(windowed[0])
        for (k in 1 until half) {
            val re = windowed[k]
            val im = windowed[fftSize - k]
            magnitude[k] = sqrt(re * re + im * im)
        }
        magnitude[half] = abs(windowed[half])

        // 3. RMS（用原始采样，不用加窗后的）
        var sumSq = 0.0
        for (i in 0 until n) sumSq += samples[i].toDouble() * samples[i].toDouble()
        val rms = sqrt(sumSq / maxOf(n, 1))

        // 4. 频谱质心（亮度）
        var weightedSum = 0.0
        var magSum = 0.0
        for (k in 0..half) {
            val freq = k * binHz
            weightedSum += freq * magnitude[k]
            magSum += magnitude[k]
        }
        val centroidHz = if (magSum > 1e-9) weightedSum / magSum else 0.0
        // 归一化到 [0,1]：以 Nyquist 为上界
        val nyquist = sampleRate / 2.0
        val spectralCentroid = (centroidHz / nyquist).coerceIn(0.0, 1.0)

        // 5. 频谱通量（与上一帧的差异）
        val spectralFlux = if (previousSpectrum != null && previousSpectrum.size == magnitude.size) {
            var flux = 0.0
            for (k in 0..half) {
                val diff = magnitude[k] - previousSpectrum[k]
                if (diff > 0) flux += diff * diff  // 只计正向变化（onset）
            }
            sqrt(flux / (half + 1))
        } else 0.0

        // 6. 频段能量
        val bassBins = (bassUpperHz / binHz).toInt().coerceIn(1, half)
        val midBins = (midUpperHz / binHz).toInt().coerceIn(bassBins + 1, half)
        var bassE = 0.0
        var midE = 0.0
        var highE = 0.0
        for (k in 0..half) {
            val e = magnitude[k]
            when {
                k <= bassBins -> bassE += e
                k <= midBins -> midE += e
                else -> highE += e
            }
        }
        val total = bassE + midE + highE + 1e-9
        val bassEnergy = (bassE / total).coerceIn(0.0, 1.0)
        val midEnergy = (midE / total).coerceIn(0.0, 1.0)
        val highEnergy = (highE / total).coerceIn(0.0, 1.0)

        // 7. onset 强度 = flux 归一化 + 低频增量归一化
        // flux 经验上限约 fftSize/4，做软归一化
        val fluxNorm = (spectralFlux / (fftSize / 4.0)).coerceIn(0.0, 1.0)
        val onsetStrength = fluxNorm

        // 8. 总能量（dB 归一化到 [0,1]）
        val totalEnergy = if (rms > 1e-9) {
            (20 * log10(rms) / -60.0 + 1.0).coerceIn(0.0, 1.0)
        } else 0.0

        // 保存幅度谱供下一帧使用
        lastSpectrum = magnitude.copyOf()

        return AudioFeatures(
            rms = rms,
            spectralCentroid = spectralCentroid,
            spectralFlux = fluxNorm,
            onsetStrength = onsetStrength,
            bassEnergy = bassEnergy,
            midEnergy = midEnergy,
            highEnergy = highEnergy,
            totalEnergy = totalEnergy
        )
    }

    /** 最近一次 extract 后的幅度谱，供下一帧计算 flux 使用。 */
    var lastSpectrum: DoubleArray? = null
        private set
}

/**
 * 一帧音频的多维特征。
 * 所有归一化字段都在 [0,1]；rms 为原始值（[-1,1] 范围）。
 */
data class AudioFeatures(
    val rms: Double,
    val spectralCentroid: Double,
    val spectralFlux: Double,
    val onsetStrength: Double,
    val bassEnergy: Double,
    val midEnergy: Double,
    val highEnergy: Double,
    val totalEnergy: Double
) {
    companion object {
        /** 全零特征（静音帧）。 */
        val SILENT = AudioFeatures(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
}
