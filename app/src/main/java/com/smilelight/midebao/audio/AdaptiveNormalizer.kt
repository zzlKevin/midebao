package com.smilelight.midebao.audio

/**
 * 基于时间戳的滑动窗口自适应归一化器。
 *
 * 【设计依据】
 * 原项目用"固定帧数窗口"（假设 8 fps）做 z-score 归一化，一旦采样率/缓冲区变化窗口时长就失准。
 * 本类改用"真实时间戳"管理窗口：只保留最近 windowMs 毫秒内的样本，保证窗口时长恒定，
 * 与帧率解耦。这是 MIR（音乐信息检索）领域自适应归一化的标准做法。
 *
 * z-score = (x - mean) / std，其中 mean/std 基于窗口内样本计算。
 * 当窗口样本不足（< minSamples）时返回 0，避免冷启动阶段的不稳定值。
 *
 * 【参数依据】
 * - windowMs = 10000（10 秒）：音乐短句的最短时长（Lerdahl & Jackendoff, 1983），
 *   足以覆盖 2-4 个 4 拍乐句，又能快速适应段落变化。
 * - minSamples = 8：统计学上计算标准差至少需要 2 个样本，这里取 8 以获得更稳定的估计。
 *
 * 【线程安全】
 * 本类方法假定在单一音频线程内调用（AudioRecord 读取线程），不做内部同步。
 *
 * @param windowMs 窗口时长（毫秒），样本超过此时长的旧数据会被淘汰
 * @param minSamples 计算归一化值所需的最少样本数，不足时返回 0
 */
class AdaptiveNormalizer(
    private val windowMs: Long = 10_000L,
    private val minSamples: Int = 8
) {
    // 存储 (时间戳, 原始值) 对，按时间顺序追加
    private val samples = ArrayDeque<Pair<Long, Double>>()

    /**
     * 添加一个新样本并返回其归一化后的 z-score。
     *
     * @param timestampMs 样本的时间戳（System.currentTimeMillis()），用于窗口淘汰
     * @param rawValue 原始测量值
     * @return z-score；若窗口内样本不足 minSamples 或标准差为 0 则返回 0.0
     */
    fun add(timestampMs: Long, rawValue: Double): Double {
        samples.addLast(timestampMs to rawValue)
        evictOld(timestampMs)
        if (samples.size < minSamples) return 0.0

        val mean = samples.map { it.second }.average()
        val variance = samples.map { (it.second - mean) * (it.second - mean) }.average()
        val std = Math.sqrt(variance)
        if (std < 1e-9) return 0.0
        return (rawValue - mean) / std
    }

    /**
     * 清空窗口，用于重新开始采集（如切换歌曲、暂停后恢复）。
     */
    fun reset() {
        samples.clear()
    }

    /**
     * 当前窗口内样本数量（用于调试/测试）。
     */
    fun size(): Int = samples.size

    /**
     * 淘汰超出窗口的旧样本。
     * 窗口左边界 = timestampMs - windowMs。
     */
    private fun evictOld(timestampMs: Long) {
        val cutoff = timestampMs - windowMs
        while (samples.isNotEmpty() && samples.first().first < cutoff) {
            samples.removeFirst()
        }
    }
}
