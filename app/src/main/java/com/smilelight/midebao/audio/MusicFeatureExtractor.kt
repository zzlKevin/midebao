package com.smilelight.midebao.audio

import org.jtransforms.fft.DoubleFFT_1D

/**
 * 音乐特征提取器：从一段 PCM 音频样本中提取多维特征。
 *
 * 【设计依据】
 * 本类是纯函数式的特征提取（输入样本数组 → 输出特征对象），不持有可变状态，
 * 便于单元测试。频谱通量（spectral flux）需要上一帧频谱，由调用方通过
 * [previousSpectrum] 参数传入，避免本类内部维护状态。
 *
 * 【提取的特征】
 * 1. RMS（均方根音量）：时域能量，反映整体响度。
 * 2. 频谱质心（Spectral Centroid）：频谱"重心"频率，反映音色明暗。
 *    高质心 = 明亮（钹、人声齿音）；低质心 = 沉闷（贝斯、底鼓）。
 * 3. 频谱通量（Spectral Flux）：相邻帧频谱幅值正差之和，反映"新音符出现"的强度。
 * 4. 低/中/高频能量比：按声学频段划分，用于风格判断。
 *
 * 【频段划分依据】（基于声学物理，非拍脑袋）
 * 采样率 16000 Hz → 奈奎斯特频率 8000 Hz → FFT bin 宽度 = 16000/fftSize。
 * - 低频（Bass）：20-250 Hz（底鼓、贝斯基频区，AES 声学频段标准）
 * - 中频（Mid）：250-2000 Hz（人声、吉他、键盘主体区）
 * - 高频（Treble）：2000-8000 Hz（钹、齿音、空气感）
 *
 * @param sampleRate 采样率（Hz）
 * @param fftSize FFT 窗口大小（必须为 2 的幂）
 */
class MusicFeatureExtractor(
    private val sampleRate: Int,
    private val fftSize: Int
) {
    private val fft = DoubleFFT_1D(fftSize.toLong())
    private val binWidth: Double = sampleRate.toDouble() / fftSize

    // 频段边界对应的 FFT bin 索引（基于声学频段标准）
    private val bassBinEnd: Int = Math.max(1, (250.0 / binWidth).toInt())      // 250 Hz
    private val midBinEnd: Int = Math.max(bassBinEnd + 1, (2000.0 / binWidth).toInt())  // 2000 Hz
    private val halfBins: Int = fftSize / 2  // 频谱有效半边 bin 数

    /**
     * 从一帧 PCM 样本中提取音乐特征。
     *
     * @param samples 归一化到 [-1, 1] 的 PCM 样本，长度必须等于 fftSize
     * @param previousSpectrum 上一帧的频谱幅值数组（长度 = halfBins），用于计算频谱通量；
     *        首帧传 null，通量返回 0
     * @return [MusicFeatures] 特征对象
     */
    fun extract(samples: DoubleArray, previousSpectrum: DoubleArray?): MusicFeatures {
        require(samples.size == fftSize) {
            "samples 长度 ${samples.size} 必须等于 fftSize $fftSize"
        }

        // 1. 时域 RMS
        var sumSquares = 0.0
        for (s in samples) {
            sumSquares += s * s
        }
        val rms = Math.sqrt(sumSquares / fftSize)

        // 2. FFT（原地变换，samples 被修改）
        // 复制一份避免修改调用方的数组
        val fftBuffer = samples.copyOf()
        fft.realForward(fftBuffer)

        // 3. 计算频谱幅值
        // 注意：JTransforms realForward 的输出布局为：
        //   a[0] = Re[0]（DC），a[1] = Re[n/2]（奈奎斯特），
        //   a[2*k] = Re[k]，a[2*k+1] = Im[k]（k=1..n/2-1）
        // 因此 bin 0 的幅值 = |a[0]|，bin n/2 的幅值 = |a[1]|，
        // 其余 bin k 的幅值 = sqrt(a[2k]² + a[2k+1]²)。
        val spectrum = DoubleArray(halfBins)
        var totalMag = 0.0
        // bin 0（DC 分量）
        spectrum[0] = Math.abs(fftBuffer[0])
        totalMag += spectrum[0]
        // bin 1 ~ halfBins-2：标准复数幅值
        for (i in 1 until halfBins - 1) {
            val real = fftBuffer[2 * i]
            val imag = fftBuffer[2 * i + 1]
            val mag = Math.sqrt(real * real + imag * imag)
            spectrum[i] = mag
            totalMag += mag
        }
        // bin halfBins-1（奈奎斯特频率）
        spectrum[halfBins - 1] = Math.abs(fftBuffer[1])
        totalMag += spectrum[halfBins - 1]

        // 4. 频谱质心 = Σ(f * mag) / Σ(mag)
        val centroid = if (totalMag > 1e-9) {
            var weightedSum = 0.0
            for (i in 0 until halfBins) {
                weightedSum += (i * binWidth) * spectrum[i]
            }
            weightedSum / totalMag
        } else {
            0.0
        }

        // 5. 频谱通量 = Σ max(0, mag[i] - prevMag[i])
        val flux = if (previousSpectrum != null && previousSpectrum.size == halfBins) {
            var f = 0.0
            for (i in 0 until halfBins) {
                val diff = spectrum[i] - previousSpectrum[i]
                if (diff > 0) f += diff
            }
            f
        } else {
            0.0
        }

        // 6. 频段能量比
        var bassEnergy = 0.0
        var midEnergy = 0.0
        var trebleEnergy = 0.0
        for (i in 0 until halfBins) {
            when {
                i < bassBinEnd -> bassEnergy += spectrum[i]
                i < midBinEnd -> midEnergy += spectrum[i]
                else -> trebleEnergy += spectrum[i]
            }
        }
        val totalBand = bassEnergy + midEnergy + trebleEnergy
        val bassRatio = if (totalBand > 1e-9) bassEnergy / totalBand else 0.0
        val trebleRatio = if (totalBand > 1e-9) trebleEnergy / totalBand else 0.0

        return MusicFeatures(
            rms = rms,
            spectralCentroidHz = centroid,
            spectralFlux = flux,
            bassRatio = bassRatio,
            trebleRatio = trebleRatio,
            spectrum = spectrum
        )
    }

    /**
     * 返回频谱半边 bin 数（供调用方缓存上一帧频谱时分配数组用）。
     */
    fun halfBinCount(): Int = halfBins
}

/**
 * 一帧音频的音乐特征快照。
 * 这是一个纯数据类，所有字段不可变。
 *
 * @property rms 均方根音量，范围 [0, 1]
 * @property spectralCentroidHz 频谱质心频率（Hz），范围 [0, sampleRate/2]
 * @property spectralFlux 频谱通量（非负，无固定上界）
 * @property bassRatio 低频能量占比，范围 [0, 1]
 * @property trebleRatio 高频能量占比，范围 [0, 1]
 * @property spectrum 本帧频谱幅值数组（长度 = fftSize/2），供下一帧通量计算用
 */
data class MusicFeatures(
    val rms: Double,
    val spectralCentroidHz: Double,
    val spectralFlux: Double,
    val bassRatio: Double,
    val trebleRatio: Double,
    val spectrum: DoubleArray
) {
    // DoubleArray 的默认 equals 不比较内容，需手动实现以保证 data class 语义
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MusicFeatures) return false
        return rms == other.rms &&
            spectralCentroidHz == other.spectralCentroidHz &&
            spectralFlux == other.spectralFlux &&
            bassRatio == other.bassRatio &&
            trebleRatio == other.trebleRatio &&
            spectrum.contentEquals(other.spectrum)
    }

    override fun hashCode(): Int {
        var result = rms.hashCode()
        result = 31 * result + spectralCentroidHz.hashCode()
        result = 31 * result + spectralFlux.hashCode()
        result = 31 * result + bassRatio.hashCode()
        result = 31 * result + trebleRatio.hashCode()
        result = 31 * result + spectrum.contentHashCode()
        return result
    }
}
