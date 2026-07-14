# 单元测试使用说明

## 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 8+
- Android SDK 34
- Kotlin 1.9.22

## 运行测试

### 方式一：命令行

```bash
cd midebao
./gradlew test
```

### 方式二：Android Studio

1. 打开项目
2. 在 Project 视图中找到 `app/src/test/java/com/smilelight/midebao/audio/`
3. 右键点击 `audio` 文件夹 → `Run Tests`

### 方式三：运行单个测试类

```bash
./gradlew test --tests "com.smilelight.midebao.audio.ActionCatalogTest"
```

## 测试文件清单

| 文件 | 测试数量 | 测试目标 |
|------|----------|----------|
| `MockAubioProcessor.kt` | - | Mock 辅助类（非测试） |
| `ActionCatalogTest.kt` | 8 | 17 个动作注册、单调性、范围 |
| `AudioFeatureExtractorTest.kt` | 7 | 静音/正弦波/频段能量 |
| `BeatTrackerTest.kt` | 7 | 半速修正、平滑、预测 |
| `MusicStateTrackerTest.kt` | 6 | 状态聚合、归一化 |
| `ActionMapperTest.kt` | 8 | 评分函数、切换惩罚 |
| `AudioToActionPipelineTest.kt` | 7 | bytesToFloats、端到端 |
| `PipelineConfigTest.kt` | 11 | 参数校验 |

**总计：54 个测试用例**

## 测试覆盖的核心逻辑

1. **ActionCatalog**：17 个动作完整性、F3/F5 单调性、周期递减
2. **AudioFeatureExtractor**：静音检测、频段能量分配、归一化范围
3. **BeatTracker**：半速修正（30-80 BPM ×2）、BPM 平滑、节拍间隔统计
4. **MusicStateTracker**：情绪向量归一化、BPM 传播、reset
5. **ActionMapper**：距离评分、切换惩罚、选择逻辑、驻留节拍检查
6. **AudioToActionPipeline**：PCM 转换、端到端处理、多帧连续处理
7. **PipelineConfig**：权重和为 1.0、参数范围校验、非法参数异常
