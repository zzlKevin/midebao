# 修改函数清单 (CHANGES.md)

## 概述

本次重构将 `MainActivity.kt` 中约 800 行的音频处理逻辑拆分为 6 个独立模块，采用"特征提取 → 状态聚合 → 评分选择"的管道架构，替代原有的"指纹表硬查表"方案。

---

## 一、新增文件（6 个模块 + 7 个测试）

### 1. `app/src/main/java/com/smilelight/midebao/audio/ActionConfig.kt`

| 类型 | 名称 | 说明 |
|------|------|------|
| data class | `ActionDefinition` | 单个动作的定义（动作码、速度档位、周期、目标特征向量） |
| object | `ActionCatalog` | 17 个动作的静态注册表（F3×7 + F4×1 + F5×7 + F6×1 + F7×1） |
| data class | `PipelineConfig` | 管道可调参数集中管理（评分权重、切换阈值、平滑因子等），含 `init {}` 参数校验 |

### 2. `app/src/main/java/com/smilelight/midebao/audio/AudioFeatureExtractor.kt`

| 类型 | 名称 | 说明 |
|------|------|------|
| class | `AudioFeatureExtractor` | 从一帧 PCM 浮点采样提取多维特征 |
| method | `extract(samples: FloatArray): AudioFeatures` | FFT → RMS / 频谱质心 / 通量 / onset / 三频段能量 |
| data class | `AudioFeatures` | 一帧音频的多维特征（8 个字段，全部归一化） |

### 3. `app/src/main/java/com/smilelight/midebao/audio/BeatTracker.kt`

| 类型 | 名称 | 说明 |
|------|------|------|
| class | `BeatTracker` | 封装 aubio tempo 检测，半速修正 + 平滑 + 间隔统计 |
| method | `process(samples: FloatArray, nowMs: Long): BeatInfo` | 处理一帧采样，返回节拍信息 |
| method | `predictNextBeatTimeMs(nowMs: Long): Long` | 预测下一拍时间（毫秒） |
| method | `reset()` | 重置内部状态 |
| data class | `BeatInfo` | 节拍信息（isBeat / bpm / rawBpm / beatSample） |
| interface | `AubioProcessorLike` | aubio 处理器抽象接口（便于 mock） |

### 4. `app/src/main/java/com/smilelight/midebao/audio/MusicStateTracker.kt`

| 类型 | 名称 | 说明 |
|------|------|------|
| class | `MusicStateTracker` | 逐帧特征聚合为稳定的音乐情绪向量 |
| method | `update(features: AudioFeatures, beatInfo: BeatInfo)` | 用一帧特征更新状态 |
| val | `currentState: MusicState` | 获取当前音乐状态 |
| method | `reset()` | 重置状态 |
| data class | `MusicState` | 音乐情绪状态向量（bpm / energy / brightness / complexity / stability 等） |

### 5. `app/src/main/java/com/smilelight/midebao/audio/ActionMapper.kt`

| 类型 | 名称 | 说明 |
|------|------|------|
| class | `ActionMapper` | 多维评分模型选最佳动作（替代指纹表） |
| method | `scoreAction(action, state, currentActionCode): Double` | 计算单个候选动作得分 |
| method | `selectBest(state, currentActionCode, currentHoldBeats): SelectionResult` | 从 17 个动作中选最佳 |
| method | `computeBeatScore(action, bpm): Double` | 节拍匹配度（余弦相似度） |
| method | `computeDistanceScore(target, actual): Double` | 距离评分（1 - |Δ|/2） |
| method | `computeSwitchingPenalty(candidateCode, currentCode): Double` | 切换惩罚 |
| data class | `SelectionResult` | 动作选择结果（bestAction / bestScore / shouldSwitch / reason） |

### 6. `app/src/main/java/com/smilelight/midebao/audio/AudioToActionPipeline.kt`

| 类型 | 名称 | 说明 |
|------|------|------|
| class | `AudioToActionPipeline` | 完整管道编排（特征提取 → 节拍跟踪 → 状态聚合 → 动作选择） |
| method | `processAudioChunk(audioBytes: ByteArray): PipelineResult` | 处理一段 PCM 字节流 |
| method | `updateCurrentAction(actionCode, speedLevel)` | 同步当前动作状态 |
| method | `reset()` | 重置管道 |
| companion | `bytesToFloats(audioBytes: ByteArray): FloatArray` | 16-bit PCM → [-1,1] 浮点 |
| data class | `PipelineResult` | 单次管道处理结果 |

### 7. 测试文件（`app/src/test/java/com/smilelight/midebao/audio/`）

| 文件 | 测试目标 |
|------|----------|
| `MockAubioProcessor.kt` | Mock 实现 `AubioProcessorLike` |
| `ActionCatalogTest.kt` | 17 个动作注册、单调性、范围校验 |
| `AudioFeatureExtractorTest.kt` | 静音/正弦波/频段能量 |
| `BeatTrackerTest.kt` | 半速修正、平滑、节拍间隔、预测 |
| `MusicStateTrackerTest.kt` | 状态聚合、归一化、reset |
| `ActionMapperTest.kt` | 评分函数、切换惩罚、选择逻辑 |
| `AudioToActionPipelineTest.kt` | bytesToFloats、端到端处理 |
| `PipelineConfigTest.kt` | 参数校验、权重和、范围检查 |

---

## 二、修改文件

### `app/src/main/java/com/smilelight/midebao/AubioProcessor.kt`

| 修改内容 | 说明 |
|----------|------|
| 实现 `AubioProcessorLike` 接口 | `class AubioProcessor : AubioProcessorLike` |
| `getTempo()` → `override fun getTempo()` | 对外暴露纯 Kotlin 接口 |
| `getLastBeatSample()` → `override fun getLastBeatSample()` | 同上 |

### `app/src/main/java/com/smilelight/midebao/MainActivity.kt`

#### 删除的变量（约 30 个）

| 删除的变量 | 替代方案 |
|------------|----------|
| `currentBassEnergy` | `pipeline.stateTracker.currentState.bassEnergy` |
| `currentMidHighEnergy` | `pipeline.stateTracker.currentState.midEnergy + highEnergy` |
| `currentRms` | `pipeline.extractor` 内部管理 |
| `currentSpectralFlux` | `pipeline.extractor` 内部管理 |
| `currentHighFreqRatio` | `pipeline.stateTracker.currentState.brightness` |
| `currentOnsetStrength` | `pipeline.extractor` 内部管理 |
| `previousFlux` | `pipeline.extractor` 内部管理 |
| `previousSpectrum` | `pipeline.extractor.lastSpectrum` |
| `fluxHistory` | `pipeline.extractor` 内部管理 |
| `currentPeFinal` | `pipeline.stateTracker.currentState.rawPe` |
| `peWindow` | `pipeline.stateTracker` 内部管理 |
| `maxEnergy` | `pipeline.stateTracker` 内部管理 |
| `energyHistory` | `pipeline.stateTracker` 内部管理 |
| `lastBeatTime` | `pipeline.beatTracker` 内部管理 |
| `currentBpmEstimate` | `pipeline.beatTracker.smoothedBpm` |
| `currentEnergyLevel` | `pipeline.stateTracker.currentState.energy` |
| `currentStability` | `pipeline.stateTracker.currentState.stability` |
| `beatIntervals` | `pipeline.beatTracker` 内部管理 |
| `actionBaseTargetPE` | 删除（不再使用 PE 阈值切换） |
| `allCandidates` | `pipeline.mapper.catalog.allActions` |
| `lastSwitchBeat` | `pipeline.mapper` 内部管理 |
| `currentActionStartBeat` | `pipeline.mapper` 内部管理 |
| `lastAubioBeatTime` | `pipeline.beatTracker` 内部管理 |
| `lastBeatSample` | `pipeline.beatTracker` 内部管理 |
| `predictedNextBeatTime` | `pipeline.beatTracker.predictNextBeatTimeMs()` |
| `isPredictionMode` | 删除（预测逻辑移入 BeatTracker） |
| `lastPredictedActionTime` | 删除 |
| `currentAction` | `pipeline.currentActionCode` |
| `currentSpeedLevel` | `pipeline.currentSpeedLevel` |
| `DoubleFFT_1D` 实例 | 移入 `AudioFeatureExtractor` |

#### 删除的函数（4 个）

| 删除的函数 | 替代方案 |
|------------|----------|
| `selectActionByScoring()` | `pipeline.mapper.selectBest()` |
| `scoreCandidate()` | `pipeline.mapper.scoreAction()` |
| `predictNextBeat()` | `pipeline.beatTracker.predictNextBeatTimeMs()` |
| `getTargetPE()` | 删除（不再使用 PE 阈值，改用评分模型） |

#### 删除的常量（约 10 个）

| 删除的常量 | 替代方案 |
|------------|----------|
| `SWITCH_THRESHOLD` | `PipelineConfig.switchThreshold` |
| `FRAME_RATE` | 删除（不再硬编码） |
| `PE_WINDOW_SIZE` | `PipelineConfig.peWindowSizeFrames` |
| `PREDICTION_WINDOW` | `PipelineConfig.predictionWindowBeats` |
| `PRE_SEND_OFFSET` | `PipelineConfig.preSendOffsetMs` |
| `FLUX_HISTORY_SIZE` | 删除（移入 AudioFeatureExtractor） |
| `MIN_HOLD_BEATS` | `PipelineConfig.minHoldBeats` |
| `MAX_HOLD_BEATS` | `PipelineConfig.maxHoldBeats` |
| `BPM_SMOOTHING_FACTOR` | `PipelineConfig.bpmSmoothingFactor` |
| `HALF_SPEED_RANGE` | `PipelineConfig.bpmHalfSpeedRange` |

#### 新增的变量

| 新增的变量 | 类型 | 说明 |
|------------|------|------|
| `pipeline` | `AudioToActionPipeline` | 管道实例 |
| `pipelineConfig` | `PipelineConfig` | 管道配置 |

#### 修改的函数

| 函数名 | 修改内容 |
|--------|----------|
| `processAudioChunk()` | 改为调用 `pipeline.processAudioChunk()`，删除内联特征提取和评分逻辑 |
| `onBeatDetected()` | 改为从 `PipelineResult.selection` 读取动作选择结果 |
| `startAudioCapture()` | 改为使用 `pipeline` 初始化 |
| `toggleDance()` | 改为使用 `pipeline.reset()` |
| `updateDynamicCooldowns()` | 改为从 `pipeline.stateTracker.currentState` 读取能量 |
| 切歌检测逻辑 | 改为从 `pipeline.stateTracker.currentState` 读取 BPM 变化 |

### `app/build.gradle`

| 修改内容 | 说明 |
|----------|------|
| 新增 `testImplementation 'junit:junit:4.13.2'` | JUnit 4 测试框架 |
| 新增 `testImplementation 'org.jetbrains.kotlin:kotlin-test:1.9.22'` | Kotlin 测试断言 |
| 新增 `testImplementation 'org.jetbrains.kotlin:kotlin-test-junit:1.9.22'` | Kotlin JUnit 集成 |

---

## 三、架构变化总结

### 重构前

```
MainActivity.kt (800+ 行)
├── 音频特征提取（内联 FFT、RMS、频谱分析）
├── 节拍跟踪（内联 aubio 调用、半速修正、平滑）
├── 状态管理（内联滑动窗口、PE 计算）
├── 动作选择（指纹表硬查表 + PE 阈值切换）
└── 蓝牙通信 + UI 更新
```

### 重构后

```
MainActivity.kt (精简)
├── pipeline.processAudioChunk()  →  委托给管道
├── 蓝牙通信
└── UI 更新

audio/
├── ActionConfig.kt        ← 17 个动作定义 + 参数集中管理
├── AudioFeatureExtractor.kt ← PCM → 多维特征（纯函数式）
├── BeatTracker.kt          ← aubio 封装 + 半速修正 + 平滑
├── MusicStateTracker.kt    ← 逐帧特征 → 稳定情绪向量
├── ActionMapper.kt         ← 多维评分模型（替代指纹表）
└── AudioToActionPipeline.kt ← 管道编排
```

### 核心改进

1. **消除魔法数字**：所有阈值、权重、周期集中到 `PipelineConfig`，含 `init {}` 校验。
2. **消除指纹表**：用"目标特征向量 + 距离评分"替代硬编码查表。
3. **可测试性**：6 个模块均不依赖 Android API，可在 JVM 单元测试中直接运行。
4. **可维护性**：每个模块职责单一，修改一个维度不影响其他模块。
