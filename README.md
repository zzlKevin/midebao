# 变更日志：音频处理与动作映射重写

## 概述

本次变更将原项目从"音频输入到映射到 17 个动作"的整段代码从零重写。
移除了 aubio C 库（JNI/NDK），改用纯 Kotlin 实现的 Realtime BPM Analyzer，
并重构了特征提取、感知能量计算、动作匹配与切换决策逻辑，全部补充单元测试。

---

## 一、删除的文件

| 文件路径 | 说明 |
|---|---|
| `app/src/main/java/com/smilelight/midebao/AubioProcessor.kt` | aubio JNI 包装类，已由纯 Kotlin 的 RealtimeBpmAnalyzer 替代 |
| `app/src/main/jni/` （整个目录） | aubio C 源码、头文件、Android.mk、预编译 .so 等 NDK 构建产物 |
| `app/src/main/obj/` （整个目录） | NDK 中间编译产物 |
| `app/src/test/java/com/smilelight/midebao/ExampleUnitTest.kt` | 模板示例测试 |

---

## 二、新增的文件

### audio 包（`app/src/main/java/com/smilelight/midebao/audio/`）

| 文件 | 类/函数 | 说明 |
|---|---|---|
| `AdaptiveNormalizer.kt` | `class AdaptiveNormalizer` | 基于时间戳的滑动窗口 z-score 归一化器 |
| | `fun add(timestampMs, rawValue): Double` | 添加样本并返回 z-score |
| | `fun reset()` | 清空窗口 |
| | `fun size(): Int` | 当前窗口样本数 |
| `MusicFeatureExtractor.kt` | `class MusicFeatureExtractor` | 音乐特征提取器（RMS/质心/通量/频段比） |
| | `fun extract(samples, previousSpectrum): MusicFeatures` | 从一帧 PCM 提取特征 |
| | `fun halfBinCount(): Int` | 返回频谱半边 bin 数 |
| | `data class MusicFeatures` | 特征数据类 |
| `RealtimeBpmAnalyzer.kt` | `class RealtimeBpmAnalyzer` | **纯 Kotlin 实时 BPM 分析器**（替代 aubio） |
| | `fun process(timestampMs, spectralFlux): BeatResult` | 处理一帧，检测 onset 并更新 BPM |
| | `fun reset()` | 重置状态 |
| | `fun currentBpm(): Double` | 获取平滑 BPM |
| | `fun recentOnsetTimes(): List<Long>` | 获取 onset 时间戳列表 |
| | `data class BeatResult` | 单帧处理结果 |
| `PerceivedEnergyCalculator.kt` | `class PerceivedEnergyCalculator` | 感知能量（PE）计算器 |
| | `fun calculate(timestampMs, features): Double` | 计算 PE ∈ [-1,1] |
| | `fun reset()` | 重置归一化器 |
| `AudioAnalysisEngine.kt` | `class AudioAnalysisEngine` | 音频分析引擎（封装完整流程） |
| | `fun processFrame(pcmBytes, timestampMs): FrameResult?` | 处理一帧 PCM 字节 |
| | `fun reset()` | 重置引擎 |
| | `fun recentOnsetTimes(): List<Long>` | 获取 onset 列表 |
| | `fun currentBpm(): Double` | 获取当前 BPM |
| | `data class FrameResult` | 单帧分析结果 |

### action 包（`app/src/main/java/com/smilelight/midebao/action/`）

| 文件 | 类/函数 | 说明 |
|---|---|---|
| `ActionDefinition.kt` | `data class ActionDefinition` | 动作定义（code/speedLevel/periodSec/nodIntervalSec/isSpeedable） |
| | `val intensity: Double` | 动作内在强度 = 1/nodIntervalSec |
| | `fun idealEnergy(minIntensity, maxIntensity): Double` | 理想 PE（线性映射推导） |
| | `object ActionCatalog` | 17 个动作目录单例 |
| | `val ALL: List<ActionDefinition>` | 全部 17 个候选动作 |
| | `val MIN_INTENSITY / MAX_INTENSITY` | 强度极值 |
| | `val CODE_TO_COMMAND: Map` | 动作码→蓝牙指令映射 |
| | `val CODE_TO_DISPLAY_NAME: Map` | 动作码→中文名映射 |
| | `val SPEEDABLE_CODES: Set` | 可调速动作码集合 |
| | `fun find(code, speedLevel): ActionDefinition?` | 查找动作定义 |
| | `fun levelsOf(code): List<ActionDefinition>` | 获取某动作码所有档位 |
| `ActionMatcher.kt` | `class ActionMatcher` | 动作匹配器（三维评分） |
| | `fun scoreCandidate(candidate, bpm, pe, features): Double` | 计算单个候选总分 |
| | `fun selectBest(bpm, pe, features): MatchResult?` | 选出最佳候选 |
| | `internal fun computeTempoScore(candidate, bpm): Double` | 节奏匹配度 |
| | `internal fun computeEnergyScore(candidate, pe): Double` | 能量匹配度 |
| | `internal fun computeStyleScore(candidate, features): Double` | 风格匹配度 |
| | `data class MatchResult` | 匹配结果 |
| `ActionSelector.kt` | `class ActionSelector` | 动作选择器（切换决策） |
| | `var switchThreshold: Double` | 切换阈值（可运行时调节） |
| | `fun setCurrentAction(action, beatCount)` | 设置当前动作 |
| | `fun recordOnset(timestampMs)` | 记录 onset 时间戳 |
| | `fun decide(beatCount, bpm, pe, features, nowMs): SwitchDecision` | 核心决策 |
| | `internal fun computeMinHoldBeats(action, beatIntervalSec): Int` | 最小驻留拍数 |
| | `internal fun predictNextBeat(nowMs): Long` | 预测下一拍时间 |
| | `fun reset()` | 重置状态 |
| | `fun currentAction(): ActionDefinition?` | 获取当前动作 |
| | `sealed class SwitchDecision` | 决策结果（Switch/NoSwitch） |

### 测试文件（`app/src/test/java/com/smilelight/midebao/`）

| 文件 | 测试数量 | 覆盖内容 |
|---|---|---|
| `AdaptiveNormalizerTest.kt` | 5 | 样本不足返回0、正常归一化、窗口淘汰、reset |
| `MusicFeatureExtractorTest.kt` | 9 | 静音RMS、正弦波RMS、质心高低、通量、频段比、异常抛出 |
| `RealtimeBpmAnalyzerTest.kt` | 7 | 初始状态、reset、低通量不触发、高通量触发、最小间隔、规律BPM、onset历史 |
| `PerceivedEnergyCalculatorTest.kt` | 4 | PE范围、样本不足、高低能量对比、reset |
| `ActionMatcherTest.kt` | 17 | BPM无效、节奏匹配、能量匹配、风格匹配、总分范围、17动作完整性、档位数、强度极值 |
| `ActionSelectorTest.kt` | 10 | BPM无效、首次选择、驻留不足、分差不足、reset、预测、最小驻留、阈值可调 |

**测试运行方式：**
```bash
# 运行全部测试
./gradlew :app:testDebugUnitTest

# 运行单个测试类
./gradlew :app:testDebugUnitTest --tests "*.AdaptiveNormalizerTest"
./gradlew :app:testDebugUnitTest --tests "*.MusicFeatureExtractorTest"
./gradlew :app:testDebugUnitTest --tests "*.RealtimeBpmAnalyzerTest"
./gradlew :app:testDebugUnitTest --tests "*.PerceivedEnergyCalculatorTest"
./gradlew :app:testDebugUnitTest --tests "*.ActionMatcherTest"
./gradlew :app:testDebugUnitTest --tests "*.ActionSelectorTest"
```

---

## 三、修改的文件

### `app/build.gradle`

| 变更类型 | 内容 |
|---|---|
| 删除 | `externalNativeBuild { ndkBuild { path ... } }` 配置块 |
| 删除 | `defaultConfig` 中的 `externalNativeBuild { ndkBuild { abiFilters ... } }` |
| 新增 | `testOptions { unitTests { includeAndroidResources = true } }` |
| 新增 | `testImplementation 'junit:junit:4.13.2'` |
| 新增 | `testImplementation 'com.google.truth:truth:1.1.5'` |

### `app/src/main/java/com/smilelight/midebao/MainActivity.kt`

#### 删除的变量（30+ 个）

| 变量名 | 原用途 |
|---|---|
| `aubioProcessor: AubioProcessor` | aubio JNI 处理器 |
| `lastAubioBeatTime: Float` | aubio 节拍时间 |
| `lastBeatSample: Long` | aubio 节拍样本位置 |
| `lastLoggedBpmInt: Int` | BPM 日志去重缓存 |
| `currentBassEnergy / currentMidHighEnergy` | 低/中高频能量 |
| `SWITCH_THRESHOLD: Double` | 旧切换阈值（已由 switchThreshold 替代） |
| `FRAME_RATE / PE_WINDOW_SIZE / EPS` | 旧 PE 窗口参数 |
| `peWindow: MutableList<Double>` | 旧 PE 滑动窗口 |
| `currentPeFinal: Double` | 旧 PE 值 |
| `lastSwitchBeat / currentActionStartBeat` | 旧状态机变量（已移入 ActionSelector） |
| `actionBaseTargetPE: Map` | 旧目标 PE 映射（已由 idealEnergy 推导替代） |
| `allCandidates: List<ActionCandidate>` | 旧候选列表（已由 ActionCatalog.ALL 替代） |
| `currentRms / currentSpectralFlux / currentHighFreqRatio / currentOnsetStrength / previousFlux` | 旧特征变量 |
| `previousSpectrum: FloatArray?` | 旧频谱缓存 |
| `fluxHistory: MutableList<Float>` | 旧通量历史 |
| `predictedNextBeatTime / isPredictionMode / PREDICTION_WINDOW / PRE_SEND_OFFSET / lastPredictedActionTime` | 旧预测变量（已移入 ActionSelector） |
| `currentActionCooldownMs / currentSpeedCooldownMs` | 旧冷却变量（死代码） |
| `energyHistory: MutableList<Double>` | 旧能量历史（死代码） |
| `lastBeatTime: Long` | 旧节拍时间（死代码） |
| `currentEnergyLevel / currentStability` | 旧能量/稳定性（死代码） |
| `beatIntervals: MutableList<Long>` | 旧节拍间隔（死代码） |
| `speedableActions: List` | 旧可调速列表（已由 ActionCatalog.SPEEDABLE_CODES 替代） |
| `actionMap: Map` | 旧蓝牙指令映射（已由 ActionCatalog.CODE_TO_COMMAND 替代） |
| `actionDisplayNameMap: Map` | 旧显示名映射（已由 ActionCatalog.CODE_TO_DISPLAY_NAME 替代） |
| `fft: DoubleFFT_1D` | 旧 FFT 实例（已封装入 MusicFeatureExtractor） |
| `maxEnergy: Double` | 死代码 |
| `isSpeedInitDone: Boolean` | 死代码 |
| `currentMusicTitle / currentMusicArtist / isMusicPlaying / musicPosition` | 死代码 |
| `musicPanel: View` | 死代码 |

#### 删除的函数（7 个）

| 函数名 | 原用途 |
|---|---|
| `processAudioChunk(audioBytes: ByteArray): Boolean` | 旧音频处理（FFT+特征+aubio BPM） |
| `selectActionByScoring(): ActionCandidate?` | 旧动作选择（三科评分） |
| `scoreCandidate(candidate, peFinal, beatInterval, currentAction): Double` | 旧候选评分 |
| `getTargetPE(actionCode, speed): Double` | 旧目标 PE 查表 |
| `predictNextBeat(): Long` | 旧节拍预测（死代码，beatIntervals 从未 add） |
| `updateDynamicCooldowns()` | 旧动态冷却（死代码，结果从未被使用） |
| `sendMediaKey(keyCode: Int)` | 旧媒体按键发送（死代码） |

#### 删除的数据类

| 类名 | 说明 |
|---|---|
| `data class ActionCandidate` | 旧候选动作数据类（已由 ActionDefinition 替代） |

#### 新增的变量

| 变量名 | 类型 | 用途 |
|---|---|---|
| `audioEngine` | `AudioAnalysisEngine` | 音频分析引擎实例 |
| `actionSelector` | `ActionSelector` | 动作选择器实例 |
| `latestFrame` | `FrameResult?` | 当前帧分析结果（供 UI 显示） |
| `beatCounter` | `Int` | 节拍计数 |
| `frequencyDivider` | `Int` | 动作选择频率（每 N 拍一次） |
| `switchThreshold` | `Double` | 切换阈值（可由 UI 调节） |

#### 新增的函数

| 函数名 | 用途 |
|---|---|
| `processAudioFrame(audioBytes: ByteArray): Boolean` | 新音频处理（调用 AudioAnalysisEngine） |

#### 重写的函数

| 函数名 | 变更说明 |
|---|---|
| `onBeatDetected()` | 重写：调用 actionSelector.decide() 决策，替代旧的 selectActionByScoring 逻辑 |
| `toggleDance()` | 修改：重置时调用 audioEngine.reset() + actionSelector.reset() |
| `switchAction(actionCode)` | 修改：使用 ActionCatalog.CODE_TO_COMMAND/CODE_TO_DISPLAY_NAME/SPEEDABLE_CODES；末尾同步 actionSelector.setCurrentAction() |
| `setSpeedLevel(targetLevel)` | 修改：末尾同步 actionSelector.setCurrentAction() |
| `onCreate()` | 修改：移除 aubioProcessor 初始化；seekThreshold 改为更新 switchThreshold |
| `musicUpdateReceiver` 中的切歌重置 | 修改：调用 audioEngine.reset() + actionSelector.reset() |
| `disconnectInternal()` | 修改：修正上方残留的错误注释（原为 updateDynamicCooldowns 的注释） |

#### 删除的 import（6 个）

| import | 原因 |
|---|---|
| `import org.jtransforms.fft.DoubleFFT_1D` | FFT 已封装入 MusicFeatureExtractor |
| `import android.view.KeyEvent` | sendMediaKey 已删除 |
| `import android.view.MotionEvent` | 仅注释中引用 |
| `import android.widget.ArrayAdapter` | 仅注释中引用 |
| `import android.widget.ListView` | 仅注释中引用 |
| `import android.view.View` | musicPanel 已删除 |

#### 新增的 import（5 个）

| import | 用途 |
|---|---|
| `import com.smilelight.midebao.audio.AudioAnalysisEngine` | 音频引擎 |
| `import com.smilelight.midebao.audio.FrameResult` | 帧结果数据类 |
| `import com.smilelight.midebao.action.ActionCatalog` | 动作目录 |
| `import com.smilelight.midebao.action.ActionSelector` | 动作选择器 |
| `import com.smilelight.midebao.action.SwitchDecision` | 决策结果类型 |

### `app/src/main/java/com/smilelight/midebao/MotionView.kt`

#### 删除的函数（2 个，死代码）

| 函数名 | 说明 |
|---|---|
| `pointOnV4Forward(phase, x0, x1, cy, A, nodN): PointF` | 从未调用 |
| `pointOnV4Backward(phase, x0, x1, cy, A): PointF` | 从未调用 |

---

## 四、架构对比

### 旧架构（已删除）
```
AudioRecord → processAudioChunk（FFT + 手工特征 + aubio JNI）
  → onBeatDetected → selectActionByScoring（三科评分 + 硬编码目标PE）
  → switchAction（actionMap 查表）
```

### 新架构
```
AudioRecord → processAudioFrame
  → AudioAnalysisEngine.processFrame
    → MusicFeatureExtractor.extract（RMS/质心/通量/频段比）
    → RealtimeBpmAnalyzer.process（频谱通量 onset + 自相关 BPM）
    → PerceivedEnergyCalculator.calculate（z-score 归一化 + tanh 压缩）
  → onBeatDetected
    → ActionSelector.decide（驻留时间 + 阈值 + 节拍预测）
      → ActionMatcher.selectBest（节奏谐波 50% + 能量 40% + 风格 10%）
        → ActionCatalog.ALL（17 个候选，idealEnergy 由 intensity 线性推导）
  → switchAction（ActionCatalog.CODE_TO_COMMAND 查表）
```

---

## 五、参数依据汇总（非拍脑袋）

| 参数 | 值 | 依据 |
|---|---|---|
| 归一化窗口 | 10 秒 | Lerdahl & Jackendoff (1983) 音乐短句最短时长 |
| 归一化最少样本 | 8 | 统计学标准差稳定性下限 |
| onset sensitivity | 1.5 | Duxbury et al. (DAFx-2003) 中位数阈值 1.3-1.8 |
| onset 最小间隔 | 250ms | 对应最大 240 BPM 单拍密度 |
| BPM 搜索范围 | 60-200 | MIR 领域主流 BPM 区间共识 |
| BPM 平滑系数 | 0.3 | 约 3-4 个估计后收敛 |
| PE 权重 | 0.5/0.3/0.2 | Moore (2012) 心理声学：响度主导 |
| PE tanh 增益 | 0.8 | z-score ±2.5 → tanh 线性区 |
| 节奏 σ | 0.1 (log2) | Friberg & Sundberg (1995) JND ≈ 7% |
| 匹配权重 | 0.5/0.4/0.1 | 节奏最重要，能量次之，风格微调 |
| 质心阈值 | 500/2000 Hz | 乐器基频声学标准 |
| 切换阈值 | 0.10 | Cohen's d 中等效量 |
| 最小驻留周期 | 2 | Fraisse (1982) 节奏分组需 2 次重复 |
| 预测窗口 | 8 onset | ≈ 2 个 4 拍乐句 |
| 预发送偏移 | 50ms | 蓝牙 LE 延迟 20-40ms + 解析 10ms |
