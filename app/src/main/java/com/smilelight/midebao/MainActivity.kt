package com.smilelight.midebao

import android.Manifest
import android.app.Notification
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.session.MediaSession
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import java.util.UUID
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import com.smilelight.midebao.audio.AudioToActionPipeline
import com.smilelight.midebao.audio.AudioFeatureExtractor
import com.smilelight.midebao.audio.BeatTracker
import com.smilelight.midebao.audio.MusicStateTracker
import com.smilelight.midebao.audio.ActionMapper
import com.smilelight.midebao.audio.PipelineConfig
import com.smilelight.midebao.audio.ActionCatalog


class MainActivity : AppCompatActivity() {

    private lateinit var mediaSessionManager: MediaSessionManager

    private var isDragging = false
    private var progressUpdateRunnable: Runnable? = null

    private var lastLoggedBpmInt = Int.MIN_VALUE

    private lateinit var aubioProcessor: AubioProcessor

    private lateinit var motionView: MotionView

    @Volatile
    private var lastPlayingState = false

    private var lastMusicTitle = ""

    private lateinit var seekThreshold: SeekBar
    private lateinit var tvThresholdValue: TextView
    private lateinit var tvPE: TextView
    private lateinit var tvBPM: TextView
    private lateinit var tvActionStatus: TextView

    /**
     * 音频到 17 动作映射管道。
     * 由 [startAudioCapture] 初始化，负责特征提取、节拍跟踪、状态聚合、动作选择。
     * 所有"拍脑袋"参数集中在 [PipelineConfig] 中，业务逻辑不出现魔法数字。
     */
    private var pipeline: AudioToActionPipeline? = null

    /** 管道配置（集中管理所有可调参数）。 */
    private val pipelineConfig = PipelineConfig()

    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 3

    private var mediaController: MediaController? = null
    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            updateUIFromController()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            updateUIFromController()
        }
    }

    // MediaController 回调类（用于接收播放状态和元数据更新）



    private lateinit var musicPanel: View
    private lateinit var tvMusicTitle: TextView
    private lateinit var tvMusicArtist: TextView
    private lateinit var tvMusicPosition: TextView
    private lateinit var tvMusicDuration: TextView
    private lateinit var musicProgressBar: SeekBar
    private lateinit var btnPrev: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    // 音乐信息（用于UI更新）
    private var currentMusicTitle = ""
    private var currentMusicArtist = ""
    private var isMusicPlaying = false
    private var musicDuration = 0L
    private var musicPosition = 0L

    // 广播接收器
    private val musicUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MusicNotificationListenerService.ACTION_MUSIC_UPDATE) {
                val title = intent.getStringExtra(MusicNotificationListenerService.EXTRA_TITLE) ?: ""
                val artist = intent.getStringExtra(MusicNotificationListenerService.EXTRA_ARTIST) ?: ""
                val isPlaying = intent.getBooleanExtra(MusicNotificationListenerService.EXTRA_IS_PLAYING, false)
                val duration = intent.getLongExtra(MusicNotificationListenerService.EXTRA_DURATION, -1)
                val position = intent.getLongExtra(MusicNotificationListenerService.EXTRA_POSITION, -1)
                val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    intent.getParcelableExtra<MediaSession.Token>("media_session_token")
                } else null
                appendLog("📡 收到广播: title=$title, lastMusicTitle=$lastMusicTitle, artist=$artist, isPlaying=$isPlaying, duration=$duration")
                runOnUiThread {
                    // 1. 处理切歌（检测到新歌名）
                    if (title.isNotEmpty() && title != lastMusicTitle) {
                        appendLog("切歌: title=$title, lastMusicTitle=$lastMusicTitle")
                        // 重置所有动态状态
                        pipeline?.reset()
                        beatCounter = 0
                        appendLog("🔄 切歌检测: 已重置所有状态")

                        if (isConnected && isDancing) {
                            // 先停止律动
                            sendCommand("FE 55 10 F2 55 FE")
                            tvPE.text = "PE: ----"
                            tvBPM.text = "BPM: ----"
                            tvActionStatus.text = "动作: ----"
                            Thread.sleep(100)
                            sendCommand("FE 55 10 F3 55 FE") //重置动作1
                            initSpeed()
                            tvActionStatus.text = "动作: 半圆摆动 (档1)"
                            // 延迟 1 秒后重新启动
                            Handler(Looper.getMainLooper()).postDelayed({
                                sendCommand("FE 55 10 F0 55 FE")
                                appendLog("▶️ 切歌后重新启动律动")
                            }, 1000) // 1000ms = 1秒
                        }
                        // 更新标题（但不更新 lastPlayingState，留到下面统一处理）
                        lastMusicTitle = title
                        // 不要 return，继续执行下面的状态更新
                    }

                    // 2. 获取真实的播放状态（优先从 token 获取）
                    val realIsPlaying = if (token != null) {
                        val controller = MediaController(this@MainActivity, token)
                        controller.playbackState?.state == PlaybackState.STATE_PLAYING
                    } else {
                        isPlaying
                    }
                    appendLog("musicUpdateReceiver ：MusicReceiver, realIsPlaying=$realIsPlaying, isPlaying=$isPlaying")

                    // 3. 统一更新播放状态（只在此处修改 lastPlayingState）
                    if (realIsPlaying != lastPlayingState) {
                        lastPlayingState = realIsPlaying
                        appendLog(if (realIsPlaying) "▶️ 音乐恢复" else "⏸️ 音乐暂停")
                        motionView.setPaused(!realIsPlaying)
                        if (isConnected && isDancing) {
                            sendCommand(if (realIsPlaying) "FE 55 10 F0 55 FE" else "FE 55 10 F2 55 FE")
                        }
                    }

                    // 4. 更新 UI（歌曲信息、进度等）
                    // 🔥 检查数据是否有效，避免空数据覆盖UI
                    val hasValidData = title.isNotEmpty() || duration > 0
                    if (hasValidData) {
                        updateMusicUI(title, artist, realIsPlaying, duration, position, token)
                    } else {
                        appendLog("⏳ 收到空数据广播，跳过UI更新，仅状态已处理")
                    }
                }
            }

        }
    }

    private lateinit var tvCurrentAction: TextView

    // 动作码 → 显示名称映射
    private val actionDisplayNameMap = mapOf(
        "F3" to "半圆摆动",
        "F4" to "快速点头",
        "F5" to "W形摇头",
        "F6" to "中速左右快速点头",
        "F7" to "快速左右极慢点头",
        "F8" to "随机"
    )
    private lateinit var scrollLog: NestedScrollView
    // 自定义模式相关
    private var currentActionCode = "F8"  // 默认为随机池
    private var currentSpeedLevel = 2      // 1~7
    private var isSpeedInitDone = false

    // 动作码映射
    private val actionMap = mapOf(
        "F3" to "FE 55 10 F3 55 FE",
        "F4" to "FE 55 10 F4 55 FE",
        "F5" to "FE 55 10 F5 55 FE",
        "F6" to "FE 55 10 F6 55 FE",
        "F7" to "FE 55 10 F7 55 FE",
        "F8" to "FE 55 10 F8 55 FE"
    )


    // 自适应模式相关
    private var isAdaptiveMode = true  // 默认为自适应模式，可加UI开关
    private var lastActionSwitchTime = 0L
    private var lastSpeedChangeTime = 0L


    // 新增动态冷却变量
    private var currentActionCooldownMs = 7000L   // 初始默认 7 秒
    private var currentSpeedCooldownMs = 2600L   // 初始默认 2.6 秒

    // 可调速动作列表
    private val speedableActions = listOf("F3", "F5")

//    private var listScanCallback: ScanCallback? = null
//    private lateinit var deviceListView: ListView
//    private lateinit var btnSelectDevice: Button
//    private val deviceList = mutableListOf<BluetoothDevice>()
//    private lateinit var deviceListAdapter: ArrayAdapter<String>
//    private var isDeviceListVisible = false

    // 重连相关
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private val PREFS_NAME = "ble_prefs"
    private val KEY_LAST_ADDRESS = "last_address"

    // 扫描相关
    private var isScanning = false
    private val scanResultMap = mutableMapOf<String, BluetoothDevice>() // address -> device
    private val scanRssiMap = mutableMapOf<String, Int>() // address -> rssi
    private var scanCallback: ScanCallback? = null
    private var leScanner: BluetoothLeScanner? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvBeatLog: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnDance: Button

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnected = false
    private var characteristic: BluetoothGattCharacteristic? = null

    private val serviceUuid = UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb")
    private val charUuid = UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb")

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var isDancing = false
    private var beatCounter = 0
    private var frequencyDivider = 2

    private val handler = Handler(Looper.getMainLooper())
    private var audioJob: Job? = null

    private lateinit var energyBar: ProgressBar


    private val MEDIA_PROJECTION_REQUEST_CODE = 1001
    private var mediaProjection: MediaProjection? = null
    private var projectionManager: MediaProjectionManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 注册音乐更新广播
        LocalBroadcastManager.getInstance(this).registerReceiver(
            musicUpdateReceiver,
            IntentFilter(MusicNotificationListenerService.ACTION_MUSIC_UPDATE)
        )

        // 音乐控制面板
        musicPanel = findViewById(R.id.musicPanel)
        tvMusicTitle = findViewById(R.id.tvMusicTitle)
        tvMusicArtist = findViewById(R.id.tvMusicArtist)
        tvMusicPosition = findViewById(R.id.tvMusicPosition)
        tvMusicDuration = findViewById(R.id.tvMusicDuration)
        musicProgressBar = findViewById(R.id.musicProgressBar)
        btnPrev = findViewById(R.id.btnPrev)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnNext = findViewById(R.id.btnNext)
        tvPE = findViewById(R.id.tvPE)
        tvBPM = findViewById(R.id.tvBPM)
        tvActionStatus = findViewById(R.id.tvActionStatus)
        seekThreshold = findViewById(R.id.seekThreshold)
        tvThresholdValue = findViewById(R.id.tvThresholdValue)
        // 在 onCreate 中
        motionView = findViewById(R.id.motionView)

        // 主动查询当前播放状态
        fetchCurrentMediaInfo()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        mediaSessionManager.addOnActiveSessionsChangedListener(sessionCallback, ComponentName(this, MusicNotificationListenerService::class.java))

        // 初始设置为动作1，档位2，不暂停
        motionView.setAction("F3", 2, paused = false)

        lastMusicTitle = ""
        lastPlayingState = false

        aubioProcessor = AubioProcessor()
        aubioProcessor.initTempo(16000, 2048)  // 采样率和帧大小

        seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = 0.05 + progress * 0.05
                pipelineConfig.switchThreshold = threshold
                tvThresholdValue.text = String.format("%.2f", threshold)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        // 检查通知监听权限
        if (!isNotificationListenerEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要通知监听权限")
                .setMessage("为了获取音乐播放信息（歌名、进度、控制等），需要开启通知监听权限。\n\n点击“去设置”后，请找到并允许 “${getString(R.string.app_name)}” 的通知监听。")
                .setPositiveButton("去设置") { _, _ ->
                    openNotificationAccessSettings()
                }
                .setNegativeButton("稍后", null)
                .setCancelable(false)
                .show()
        }
        // 设置按钮点击事件
        btnPrev.setOnClickListener {
            mediaController?.transportControls?.skipToPrevious()
        }
        btnPlayPause.setOnClickListener {
            mediaController?.let { controller ->
                val state = controller.playbackState
                if (state?.state == PlaybackState.STATE_PLAYING) {
                    controller.transportControls.pause()
                } else {
                    controller.transportControls.play()
                }
            }
        }
        btnNext.setOnClickListener {
            mediaController?.transportControls?.skipToNext()
        }


        scrollLog = findViewById(R.id.scrollLog)
        tvStatus = findViewById(R.id.tvStatus)
        tvBeatLog = findViewById(R.id.tvBeatLog)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnDance = findViewById(R.id.btnDance)
        energyBar = findViewById(R.id.energyBar)
//        deviceListView = findViewById(R.id.deviceListView)
//        btnSelectDevice = findViewById(R.id.btnSelectDevice)
        tvCurrentAction = findViewById(R.id.tvCurrentAction)
        // 初始显示“随机”
        tvCurrentAction.text = "当前：随机"

//        scrollLog.setOnTouchListener { _, event ->
//            if (event.action == MotionEvent.ACTION_DOWN) {
//                scrollLog.parent.requestDisallowInterceptTouchEvent(true)
//            }
//            false // 让子控件（TextView）继续处理滚动
//        }
        btnDisconnect.setOnClickListener {
            disconnect()
        }

        musicProgressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // 如果 fromUser == true，可以在这里更新一个临时预览文本，但不执行 seekTo
                if (fromUser) {
                    // 可选的：显示当前拖动的进度预览
                    tvMusicPosition.text = formatTime((progress / 100f * musicDuration).toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isDragging = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isDragging = false
                if (musicDuration > 0 && mediaController != null) {
                    val progress = seekBar?.progress ?: 0
                    val position = (progress / 100f * musicDuration).toLong()
                    mediaController?.transportControls?.seekTo(position)
                    // 立即刷新一次进度显示，避免延迟
                    updateProgressFromController()
                }
            }
        })


        val rgMode = findViewById<RadioGroup>(R.id.rgMode)
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            isAdaptiveMode = (checkedId == R.id.rbAdaptive)
            if (isAdaptiveMode) {
                appendLog("🎯 切换到自适应模式")
                // 自适应模式：禁用自定义按钮，启用自动选择
                // 可以灰显动作按钮
            } else {
                appendLog("🎯 切换到自定义模式")
                // 自定义模式：恢复手动选择
            }
        }
        // 动作按钮
        val btnAction1 = findViewById<Button>(R.id.btnAction1)
        val btnAction2 = findViewById<Button>(R.id.btnAction2)
        val btnAction3 = findViewById<Button>(R.id.btnAction3)
        val btnAction4 = findViewById<Button>(R.id.btnAction4)
        val btnAction5 = findViewById<Button>(R.id.btnAction5)
        val btnActionRandom = findViewById<Button>(R.id.btnActionRandom)
        val btnActionStop = findViewById<Button>(R.id.btnActionStop)

        // 请求忽略电池优化
        requestIgnoreBatteryOptimization()
        // 设置点击事件
        btnAction1.setOnClickListener { switchAction("F3") }
        btnAction2.setOnClickListener { switchAction("F4") }
        btnAction3.setOnClickListener { switchAction("F5") }
        btnAction4.setOnClickListener { switchAction("F6") }
        btnAction5.setOnClickListener { switchAction("F7") }
        btnActionRandom.setOnClickListener { switchAction("F8") }
        btnActionStop.setOnClickListener {
            sendCommand("FE 55 10 F2 55 FE")
            tvPE.text = "PE: ----"
            tvBPM.text = "BPM: ----"
            tvActionStatus.text = "动作: ----"
            appendLog("⏹ 已停止律动")
        }

        // 速度滑块
        val speedSeekBar = findViewById<SeekBar>(R.id.speedSeekBar)
        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val level = progress + 1  // progress 0~6 -> 档位 1~7
                    if (currentActionCode in speedableActions) {
                        setSpeedLevel(level)
                    } else {
                        Toast.makeText(this@MainActivity, "当前动作不可调速", Toast.LENGTH_SHORT).show()
                        seekBar?.progress = currentSpeedLevel - 1
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 初始默认启用速度滑块（因为默认F8随机池不可调速，可置灰）
        speedSeekBar.isEnabled = false

        // 快速控制按钮
        findViewById<Button>(R.id.btnUp).setOnClickListener {
            sendCommand("FE 55 10 01 55 FE")
            appendLog("📤 上移")
        }
        findViewById<Button>(R.id.btnDown).setOnClickListener {
            sendCommand("FE 55 10 02 55 FE")
            appendLog("📤 下移")
        }
        findViewById<Button>(R.id.btnLeft).setOnClickListener {
            sendCommand("FE 55 10 03 55 FE")
            appendLog("📤 左移")
        }
        findViewById<Button>(R.id.btnRight).setOnClickListener {
            sendCommand("FE 55 10 04 55 FE")
            appendLog("📤 右移")
        }
        findViewById<Button>(R.id.btnCenter).setOnClickListener {
            sendCommand("FE 55 10 05 55 FE")
            appendLog("📤 回中")
        }
        findViewById<Button>(R.id.btnHappy).setOnClickListener {
            sendCommand("FE 55 10 0A 55 FE")
            appendLog("😄 开心表情")
        }
        findViewById<Button>(R.id.btnStartMusic).setOnClickListener {
            sendCommand("FE 55 10 F0 55 FE")
            appendLog("🎵 启动音乐律动")
        }
        findViewById<Button>(R.id.btnStopMusic).setOnClickListener {
            sendCommand("FE 55 10 F2 55 FE")
            tvPE.text = "PE: ----"
            tvBPM.text = "BPM: ----"
            tvActionStatus.text = "动作: ----"
            appendLog("⏹ 关闭音乐律动")
        }


//        deviceListView.setOnTouchListener { _, event ->
//            if (event.action == MotionEvent.ACTION_DOWN) {
//                deviceListView.parent.requestDisallowInterceptTouchEvent(true)
//            }
//            false // 返回 false 让 ListView 自己处理
//        }
//        tvBeatLog.setOnTouchListener { _, event ->
//            if (event.action == MotionEvent.ACTION_DOWN) {
//                tvBeatLog.parent.requestDisallowInterceptTouchEvent(true)
//            }
//            false
//        }
        // 启用 TextView 的滚动功能
        tvBeatLog.movementMethod = ScrollingMovementMethod.getInstance()

//        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
//        deviceListView.adapter = deviceListAdapter

        // 手动选择按钮点击事件
//        btnSelectDevice.setOnClickListener {
//            if (isDeviceListVisible) {
//                deviceListView.visibility = View.GONE
//                isDeviceListVisible = false
//                stopListScan()
//            } else {
//                if (deviceList.isEmpty()) {
//                    scanForDeviceList()
//                }
//                deviceListView.visibility = View.VISIBLE
//                isDeviceListVisible = true
//            }
//        }


        // 列表项点击事件
//        deviceListView.setOnItemClickListener { _, _, position, _ ->
//            if (position < deviceList.size) {
//                val device = deviceList[position]
//                appendLog("👉 手动选择设备: ${device.name} (${device.address})")
//                stopListScan()
//                saveLastAddress(device.address)
//                connectDevice(device)
//                deviceListView.visibility = View.GONE
//                isDeviceListVisible = false
//            }
//        }


        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        requestPermissionsIfNeeded()

        btnConnect.setOnClickListener { connectToLastDevice() }
        btnConnect.setOnClickListener {
            reconnectAttempts = 0  // 重置计数
            connectToLastDevice()
        }
        btnDance.setOnClickListener { toggleDance() }
        // 清空日志按钮
        val btnClearLog = findViewById<Button>(R.id.btnClearLog)
        btnClearLog.setOnClickListener {
            tvBeatLog.text = "等待节拍...\n"
            scrollLog.post {
                scrollLog.fullScroll(ScrollView.FOCUS_UP)
            }
        }
        findViewById<Button>(R.id.btnSpeedSlow).setOnClickListener {
            sendCommand("FE 55 10 E0 55 FE")
            appendLog("发送: 减速(E0)")
        }
        findViewById<Button>(R.id.btnSpeedFast).setOnClickListener {
            sendCommand("FE 55 10 E1 55 FE")
            appendLog("发送: 加速(E1)")
        }
        findViewById<Button>(R.id.btnPoolAdjustable).setOnClickListener {
            sendCommand("FE 55 10 F7 55 FE")
            appendLog("发送: 切换到可调速池(1-5)")
        }
        findViewById<Button>(R.id.btnPoolAll).setOnClickListener {
            sendCommand("FE 55 10 F8 55 FE")
            appendLog("发送: 切换到全部动作池")
        }

        findViewById<RadioGroup>(R.id.rgFrequency).setOnCheckedChangeListener { _, checkedId ->
            frequencyDivider = when (checkedId) {
                R.id.rbSlow -> 3
                R.id.rbMedium -> 2
                R.id.rbFast -> 1
                else -> 2
            }
            appendLog("切换频率: 每${frequencyDivider}拍执行一次")
        }
        // 启动时自动连接上次设备
        connectToLastDevice()
        // 初始化音乐面板（无歌曲）
        //updateMusicUI("", "", false, -1, -1,null)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioCapture()
        disconnect()
        cancelReconnect()
//        stopListScan()
        // 移除 MediaSession 监听器，避免内存泄漏
        mediaSessionManager.removeOnActiveSessionsChangedListener(sessionCallback)
        // 释放 MediaProjection
        mediaProjection?.stop()
        stopProgressUpdates()
        mediaProjection = null
        val serviceIntent = Intent(this, MediaProjectionService::class.java)
        stopService(serviceIntent)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(musicUpdateReceiver)
        mediaController?.unregisterCallback(mediaControllerCallback)
        mediaController = null

    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.RECORD_AUDIO)

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        }
    }


    private fun connectDevice(device: BluetoothDevice, isManual: Boolean = true) {
        if (isManual) {
            // 只有手动连接才重置重连计数器
            reconnectAttempts = 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "缺少蓝牙连接权限", Toast.LENGTH_SHORT).show()
            return
        }
        // 取消之前的重连任务
        cancelReconnect()
        // 保存地址
        saveLastAddress(device.address)
        // 断开已有连接（不触发停止音频捕获）
        disconnectInternal()
        // 发起新连接
        tvStatus.text = "连接中..."
        appendLog("🔗 正在连接 ${device.name}...")
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                isConnected = true
                // 连接成功，重置重连计数器
                reconnectAttempts = 0
                val deviceName = gatt.device.name ?: "未知设备"
                val deviceAddress = gatt.device.address
                runOnUiThread {
                    tvStatus.text = "已连接: $deviceName"
                    btnDisconnect.isEnabled = true
                    btnConnect.isEnabled = false
                }
                // ... 发现服务
                appendLog("✅ 连接成功: $deviceName ($deviceAddress)")
                // 延迟一下再发现服务，确保设备稳定
                Handler(Looper.getMainLooper()).postDelayed({
                    gatt.discoverServices()
                }, 300)
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                val deviceName = gatt.device.name ?: "未知设备"
                val deviceAddress = gatt.device.address
                isConnected = false
                runOnUiThread {
                    tvStatus.text = "已断开"
                    btnDisconnect.isEnabled = false
                    btnConnect.isEnabled = true
                    // 不要在这里停止舞蹈！让用户手动决定是否停止
                }
                appendLog("❌ 连接断开：$deviceName ($deviceAddress)")
                gatt.close()
                // 启动重连（但不会停止音频捕获）
                if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    scheduleReconnect()
                } else {
                    appendLog("🔒 用户主动断开或重连已禁用，不自动重连")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                appendLog("✅ 服务发现成功")
                // 遍历所有服务，寻找 AE30 服务
                val service = gatt.getService(serviceUuid)
                if (service != null) {
                    val char = service.getCharacteristic(charUuid)
                    if (char != null) {
                        characteristic = char
                        appendLog("✅ 找到 AE01 特征值")
                        // 连接成功后，初始化速度（设置基准档位2）
                        initSpeed()
                        // 默认启动随机池
                        switchAction("F8")

                    } else {
                        appendLog("❌ 未找到 AE01 特征值")
                    }
                } else {
                    appendLog("❌ 未找到 AE30 服务")
                    // 打印所有服务供调试
                    gatt.services?.forEach { s ->
                        appendLog("  服务: ${s.uuid}")
                    }
                }
            } else {
                appendLog("❌ 服务发现失败，状态码: $status")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                appendLog("✅ 指令发送成功")
            } else {
                appendLog("❌ 指令发送失败，状态码: $status")
            }
        }
    }

    private fun sendCommand(hexString: String) {
        if (!isConnected || bluetoothGatt == null) {
            appendLog("⚠️ 未连接，指令未发送")
            return
        }

        // 如果特征值为空，尝试重新获取
        if (characteristic == null) {
            appendLog("⚠️ 特征值为空，尝试重新获取...")
            val service = bluetoothGatt?.getService(serviceUuid)
            if (service != null) {
                characteristic = service.getCharacteristic(charUuid)
                if (characteristic != null) {
                    appendLog("✅ 重新获取特征值成功")
                } else {
                    appendLog("❌ 重新获取特征值失败")
                    return
                }
            } else {
                appendLog("❌ 无法获取服务")
                return
            }
        }

        try {
            val clean = hexString.replace(" ", "")
            val data = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            characteristic?.setValue(data)
            characteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            @Suppress("DEPRECATION")
            bluetoothGatt?.writeCharacteristic(characteristic)
            appendLog("📤 发送: $hexString")
        } catch (e: Exception) {
            appendLog("指令错误: ${e.message}")
        }
    }



    private fun disconnect() {
        //尝试断开
        appendLog("断开蓝牙")
        // 断开连接并停止重连
        cancelReconnect()
        // 重置重连计数器（用户主动断开）
        reconnectAttempts = MAX_RECONNECT_ATTEMPTS
        disconnectInternal()
        // 只有在舞蹈状态下才停止音频捕获
        // 重置 UI 状态
        runOnUiThread {
            tvStatus.text = "已断开"
            btnDisconnect.isEnabled = false
            btnConnect.isEnabled = true
        }
        // 如果正在舞蹈，停止音频捕获
        if (isDancing) {
            stopAudioCapture()
            btnDance.text = "▶ 启动随乐起舞"
            isDancing = false
        }
    }

    private fun startAudioCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请授予麦克风权限", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "内录功能需要 Android 10 或更高版本", Toast.LENGTH_SHORT).show()
            return
        }
        if (mediaProjection == null) {
            appendLog("错误：未获取 MediaProjection")
            return
        }

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val finalBufferSize = maxOf(bufferSize, 4096)

        val playbackCaptureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        // 注意：不要再设置 .setAudioSource()
        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(playbackCaptureConfig)
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build())
            .setBufferSizeInBytes(finalBufferSize)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            appendLog("内录初始化失败")
            return
        }

        isRecording = true
        audioRecord?.startRecording()
        appendLog("🎵 内录已开启，正在捕获系统音乐...")

        // 初始化音频到 17 动作映射管道
        if (!::aubioProcessor.isInitialized) {
            aubioProcessor = AubioProcessor()
        }
        val extractor = AudioFeatureExtractor(sampleRate = sampleRate)
        val beatTracker = BeatTracker(aubioProcessor, pipelineConfig)
        val stateTracker = MusicStateTracker(pipelineConfig)
        val mapper = ActionMapper(ActionCatalog, pipelineConfig)
        pipeline = AudioToActionPipeline(extractor, beatTracker, stateTracker, mapper, pipelineConfig)
        pipeline?.setCurrentAction(currentActionCode, currentSpeedLevel)
        appendLog("🔧 音频管道已初始化 (sampleRate=$sampleRate, fftSize=${extractor.fftSize})")

        audioJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(4096)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read >= 4096) {
                    val audioBytes = ByteArray(4096)
                    System.arraycopy(buffer, 0, audioBytes, 0, 4096)
                    val isBeat = processAudioChunk(audioBytes)
                    if (isBeat) {
                        withContext(Dispatchers.Main) {
                            onBeatDetected()
                        }
                    }
                }
            }
        }
        // 在 startAudioCapture 的末尾（音频捕获已启动）
        // 延迟500ms，等待 MediaController 准备好
        Handler(Looper.getMainLooper()).postDelayed({
            if (isConnected && isDancing) {
                val controller = mediaController
                if (controller != null) {
                    val state = controller.playbackState
                    val isPlaying = state?.state == PlaybackState.STATE_PLAYING
                    if (isPlaying) {
                        sendCommand("FE 55 10 F0 55 FE")
                        // 直接设置 lastPlayingState，不打印日志（避免启动时额外输出）
                        lastPlayingState = true
                        appendLog("▶️ 启动律动（自动检测到音乐播放）")
                    }
                } else {
                    if (lastPlayingState) {
                        sendCommand("FE 55 10 F0 55 FE")
                        appendLog("▶️ 启动律动（根据通知状态）")
                    }
                }
            }
        }, 500)
    }


    private fun stopAudioCapture() {
        isRecording = false
        audioJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        appendLog("⏹ 音频停止")
    }

    /**
     * 处理一帧音频数据：调用 [AudioToActionPipeline] 完成特征提取、节拍跟踪、
     * 状态聚合与动作选择，并更新 UI（能量条、PE、BPM、动作状态）。
     *
     * @param audioBytes 16-bit PCM little-endian 字节流（长度 >= 4096 字节 = 2048 采样）。
     * @return 本帧是否检测到新节拍。
     */
    private fun processAudioChunk(audioBytes: ByteArray): Boolean {
        if (!lastPlayingState) {
            return false
        }
        val pipe = pipeline ?: return false
        val result = pipe.processAudioChunk(audioBytes)

        // 更新能量条（低频能量占比）
        val progress = (result.state.bassEnergy * 100).coerceIn(0.0, 100.0).toInt()
        handler.post { energyBar.progress = progress }

        // 更新 BPM 日志（仅整数部分变化时打印，避免刷屏）
        val bpmInt = result.beatInfo.bpm.toInt()
        if (bpmInt != lastLoggedBpmInt && bpmInt > 0) {
            lastLoggedBpmInt = bpmInt
            if (result.beatInfo.bpm !in 20.0..250.0 && beatCounter % 10 == 0) {
                appendLog("⏳ 等待 BPM 稳定... (当前: ${result.beatInfo.bpm})")
            }
        }

        return result.isBeat
    }

    /**
     * 节拍回调：每检测到一拍调用一次。
     * 1. 递增节拍计数，按 [frequencyDivider] 周期更新 UI 显示。
     * 2. 自适应模式下，根据管道输出的 [SelectionResult] 决定是否切换动作。
     */
    private fun onBeatDetected() {
        if (!isDancing) return
        beatCounter++

        if (beatCounter % frequencyDivider == 0) {
            val state = pipeline?.lastResult?.state ?: MusicState.EMPTY
            val peStr = String.format("%.2f", state.energy)
            tvPE.text = "PE: $peStr"
            tvBPM.text = "BPM: ${state.bpm.toInt()}"
            val actionName = actionDisplayNameMap[currentActionCode] ?: currentActionCode
            tvActionStatus.text = "动作: $actionName (档${currentSpeedLevel})"
        }

        // 自适应模式：根据管道选择结果切换动作
        if (isAdaptiveMode && beatCounter % frequencyDivider == 0) {
            val pipe = pipeline ?: return
            val selection = pipe.lastResult?.selection ?: return
            if (selection.shouldSwitch) {
                appendLog("⚡ 切换动作: ${selection.bestAction.actionCode} 档${selection.bestAction.speedLevel} " +
                    "(得分差: ${String.format("%.3f", selection.scoreDiff)}, 原因: ${selection.reason})")
                switchAction(selection.bestAction.actionCode)
                if (selection.bestAction.isSpeedable) {
                    setSpeedLevel(selection.bestAction.speedLevel)
                }
                pipe.setCurrentAction(selection.bestAction.actionCode, selection.bestAction.speedLevel)
            } else {
                appendLog("⏭️ 不切换 (${selection.reason})")
            }
        }
    }

    private fun toggleDance() {
        if (!isDancing) {
            // 重置进度条与管道状态
            energyBar.progress = 0
            pipeline?.reset()
            // 🆕 确保动画播放
            motionView.setPaused(false)
            // 如果已连接，切换为随机池并启动（switchAction 会处理停止→F8→启动）
            if (isConnected) {
                switchAction("F8")
            } else {
                Toast.makeText(this, "未连接机器人，仅显示频谱动画", Toast.LENGTH_SHORT).show()
            }

            // 检查 MediaProjection
            if (mediaProjection == null) {
                Toast.makeText(this, "请在弹出的窗口中选择“共享整个屏幕”", Toast.LENGTH_LONG).show()
                requestMediaProjection()
                return
            }

            startAudioCapture()
            isDancing = true
            btnDance.text = "⏹ 停止舞蹈"
            beatCounter = 0
            appendLog("🎵 开始随乐起舞")
        } else {
            // 停止舞蹈时，可以选择暂停动画或继续显示最后一帧
            // 建议暂停动画，但保持最后一帧可见
            motionView.setPaused(true)
            stopDance()
        }
    }

    private fun stopDance() {
        isDancing = false
        // 先停止律动
        sendCommand("FE 55 10 F2 55 FE")
        tvPE.text = "PE: ----"
        tvBPM.text = "BPM: ----"
        tvActionStatus.text = "动作: ----"
        btnDance.text = "▶ 启动随乐起舞"
        stopAudioCapture()
        // 🆕 暂停动画
        motionView.setPaused(true)
        // 释放 MediaProjection 并停止前台服务
        mediaProjection?.stop()
        mediaProjection = null
        val serviceIntent = Intent(this, MediaProjectionService::class.java)
        stopService(serviceIntent)
        appendLog("⏹ 停止律动")
    }



    private fun appendLog(msg: String) {
        handler.post {
            tvBeatLog.append("$msg\n")
//            scrollLog.post {
//                scrollLog.fullScroll(ScrollView.FOCUS_DOWN)
//            }
        }
    }




    // 在 onCreate 或点击按钮时调用
    private fun requestMediaProjection() {
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager!!.createScreenCaptureIntent(), MEDIA_PROJECTION_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE && resultCode == RESULT_OK) {
            // 1. 启动前台服务
            val serviceIntent = Intent(this, MediaProjectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            // 2. 延迟 300ms 获取 MediaProjection，确保服务已完全启动
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    mediaProjection = projectionManager!!.getMediaProjection(resultCode, data!!)
                    startAudioCapture()          // 内录开始
                    // 更新UI状态
                    isDancing = true
                    btnDance.text = "⏹ 停止舞蹈"
                    beatCounter = 0
                    appendLog("✅ 屏幕共享授权成功，内录已启动")
                } catch (e: SecurityException) {
                    appendLog("❌ 错误: ${e.message}")
                    Toast.makeText(this, "请重新点击“启动随乐起舞”", Toast.LENGTH_SHORT).show()
                    // 重置，以便下次重试
                    mediaProjection = null
                    isDancing = false
                    btnDance.text = "▶ 启动随乐起舞"
                }
            }, 300)   // 300ms 延迟
        }
    }

//    private fun playDanceAnimation() {
//        dancerView.animate()
//            .scaleX(1.3f)
//            .scaleY(1.3f)
//            .rotationBy(15f)
//            .setDuration(150)
//            .withEndAction {
//                dancerView.animate()
//                    .scaleX(1.0f)
//                    .scaleY(1.0f)
//                    .rotationBy(-15f)
//                    .setDuration(150)
//                    .start()
//            }
//            .start()
//    }

    /**
     * 检查蓝牙是否可用（已开启 + 必要权限）
     */
    private fun checkBluetoothEnabled(): Boolean {
        // 1. 检查蓝牙适配器
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_SHORT).show()
            return false
        }
        // 2. 检查蓝牙是否开启
        if (!bluetoothAdapter!!.isEnabled) {
            Toast.makeText(this, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
            return false
        }
        // 3. 检查蓝牙权限（Android 12+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "请授予蓝牙扫描权限", Toast.LENGTH_SHORT).show()
                return false
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "请授予蓝牙连接权限", Toast.LENGTH_SHORT).show()
                return false
            }
        } else {
            // Android 6-11 需要位置权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "请授予位置权限", Toast.LENGTH_SHORT).show()
                return false
            }
        }
        return true
    }

    private fun connectToLastDevice() {
        if (!checkBluetoothEnabled()) return
        val lastAddr = getLastAddress()
        if (!lastAddr.isNullOrEmpty()) {
            tvStatus.text = "尝试连接上次设备 $lastAddr..."
            appendLog("🔗 尝试连接上次设备")
            try {
                val device = bluetoothAdapter?.getRemoteDevice(lastAddr)
                if (device != null) {
                    connectDevice(device)
                    // 设置一个超时，如果3秒后仍未连接成功，则清除地址并扫描
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isConnected) {
                            appendLog("⚠️ 上次设备连接超时，清除地址并扫描新设备")
                            saveLastAddress("")  // 清除保存的地址
                            startScan()
                        }
                    }, 3000)
                    return
                }
            } catch (e: Exception) {
                appendLog("❌ 获取上次设备失败: ${e.message}")
            }
            // 如果走到这里，说明上次设备无效
            saveLastAddress("")  // 清除无效地址
        }
        // 无保存地址或获取失败，启动扫描
        startScan()
    }

    private fun saveLastAddress(address: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_ADDRESS, address).apply()
    }

    private fun getLastAddress(): String? {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ADDRESS, null)
    }

    private fun startScan() {
        if (isScanning) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "请授予蓝牙扫描权限", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "请授予位置权限", Toast.LENGTH_SHORT).show()
                return
            }
        }

        tvStatus.text = "扫描中..."
        appendLog("🔍 开始扫描设备...")
        isScanning = true
        scanResultMap.clear()
        scanRssiMap.clear()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device
                    val name = device.name ?: "未知设备"
                    val address = device.address
                    val rssi = result.rssi
                    // 过滤：名称包含 "MIDBOW1S"（不区分大小写）
                    if (name.uppercase().contains("MIDBOW1S")) {
                        appendLog("📡 发现目标: $name ($address) RSSI=$rssi")
                        // 如果当前没有连接任何设备，立即连接第一个发现的
                        if (!isConnected && bluetoothGatt == null) {
                            appendLog("✅ 立即连接发现的设备: $name")
                            // 停止扫描
                            bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                            isScanning = false
                            // 保存地址并连接
                            saveLastAddress(address)
                            connectDevice(device)
                        } else {
                            // 如果已经有连接尝试，缓存其他设备
                            scanResultMap[address] = device
                            scanRssiMap[address] = rssi
                        }
                    } else {
                        // 可选：打印所有发现设备用于调试
//                        appendLog("📡 发现: $name ($address)")
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    appendLog("❌ 扫描失败，错误码: $errorCode")
                    isScanning = false
                    tvStatus.text = "扫描失败"
                }
            }
            leScanner = bluetoothAdapter?.bluetoothLeScanner
            leScanner?.startScan(null, settings, scanCallback)

            // 扫描超时 10 秒后选择最强信号设备连接
            Handler(Looper.getMainLooper()).postDelayed({
                stopScanAndConnect()
            }, 10000)
        } else {
            // 低版本使用旧 API
            @Suppress("DEPRECATION")
            bluetoothAdapter?.startLeScan(object : BluetoothAdapter.LeScanCallback {
                override fun onLeScan(device: BluetoothDevice, rssi: Int, scanRecord: ByteArray?) {
                    val name = device.name ?: "未知设备"
                    if (name.uppercase().contains("MIDBOW1S")) {
                        appendLog("📡 发现目标: $name (${device.address}) RSSI=$rssi")
                        scanResultMap[device.address] = device
                        scanRssiMap[device.address] = rssi
                    }
                }
            })
            // 超时处理
            Handler(Looper.getMainLooper()).postDelayed({
                stopScanAndConnect()
            }, 10000)
        }
    }

    private fun stopScanAndConnect() {
        if (!isScanning) return
        isScanning = false
        // 停止扫描
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            scanCallback?.let { leScanner?.stopScan(it) }
        } else {
            @Suppress("DEPRECATION")
            bluetoothAdapter?.stopLeScan(null)
        }
        // 如果已经通过立即连接建立了连接，就不再处理
        if (isConnected) {
            appendLog("✅ 已建立连接，忽略扫描超时")
            return
        }
        // 选择 RSSI 最强的设备
        if (scanResultMap.isEmpty()) {
            tvStatus.text = "未找到设备"
            appendLog("⚠️ 未找到任何 MIDBOW1S 设备")
            return
        }
        val bestEntry = scanRssiMap.maxByOrNull { it.value }

        if (bestEntry != null) {
            val bestAddress = bestEntry.key
            val bestDevice = scanResultMap[bestAddress]
            if (bestDevice != null) {
                tvStatus.text = "选择最强信号: ${bestDevice.name} (${bestDevice.address})"
                appendLog("✅ 选择信号最强的设备: ${bestDevice.name} RSSI=${bestEntry.value}")
                connectDevice(bestDevice)
            } else {
                tvStatus.text = "设备不可用"
                appendLog("❌ 设备对象丢失")
            }
        } else {
            tvStatus.text = "未找到有效设备"
            appendLog("❌ 无有效设备")
        }


    }

    private fun scheduleReconnect() {
        // 如果已经达到最大重连次数，不再重连
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            runOnUiThread {
                tvStatus.text = "重连失败，请手动连接"
                btnConnect.isEnabled = true
            }
            appendLog("⚠️ 已达到最大重连次数($MAX_RECONNECT_ATTEMPTS)，请手动点击“连接设备”")
            return
        }

        cancelReconnect()
        reconnectAttempts++
        val delay = 3000L // 3秒后重试

        val runnable = Runnable {
            if (!isConnected) {
                appendLog("🔄 尝试重连... (第${reconnectAttempts}次)")
                // 传入 isManual = false，避免重置计数器
                val lastAddr = getLastAddress()
                if (!lastAddr.isNullOrEmpty()) {
                    try {
                        val device = bluetoothAdapter?.getRemoteDevice(lastAddr)
                        if (device != null) {
                            connectDevice(device, isManual = false)
                        } else {
                            reconnectAttempts++
                            appendLog("⚠️ 设备地址无效，重试次数+1")
                        }
                    } catch (e: IllegalArgumentException) {
                        // 捕获地址格式异常
                        reconnectAttempts++
                        appendLog("⚠️ 蓝牙地址格式错误: ${e.message}")
                    }
                } else {
                    // 无保存地址，直接增加计数
                    reconnectAttempts++
                    appendLog("⚠️ 无保存地址，重试次数+1")
                }
            }
        }
        reconnectRunnable = runnable
        reconnectHandler.postDelayed(runnable, delay)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }


//    private fun scanForDeviceList() {
//        if (!checkBluetoothEnabled()) return
//
//        // 清空旧列表
//        deviceList.clear()
//        deviceListAdapter.clear()
//
//        // 权限检查
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
//                Toast.makeText(this, "请授予蓝牙扫描权限", Toast.LENGTH_SHORT).show()
//                return
//            }
//        } else {
//            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
//                Toast.makeText(this, "请授予位置权限", Toast.LENGTH_SHORT).show()
//                return
//            }
//        }
//
//        appendLog("🔍 开始扫描设备列表...")
//        tvStatus.text = "扫描列表中..."
//
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//            listScanCallback = object : ScanCallback() {
//                override fun onScanResult(callbackType: Int, result: ScanResult) {
//                    val device = result.device
//                    val name = device.name ?: "未知设备"
//                    val address = device.address
//                    val type = device.type
//
//                    // 规则2：设备类型过滤，接受 CLASSIC、DUAL、UNKNOWN，拒绝 LE
//                    when (type) {
//                        BluetoothDevice.DEVICE_TYPE_CLASSIC,
//                        BluetoothDevice.DEVICE_TYPE_DUAL,
//                        BluetoothDevice.DEVICE_TYPE_UNKNOWN -> {
//                            // 接受
//                        }
//                        BluetoothDevice.DEVICE_TYPE_LE -> {
//                            return  // 拒绝纯 BLE
//                        }
//                        else -> return  // 其他未知类型也拒绝
//                    }
//
//                    // 不再排除 MIDBOW1S，改为接受 MIDBOW1S 开头的设备
//                    if (name.uppercase().contains("MIDBOW1S")) {
//                        // 接受并添加到列表
//                        if (!deviceList.any { it.address == address }) {
//                            deviceList.add(device)
//                            val displayName = if (name == "未知设备") "$name ($address)" else name
//                            deviceListAdapter.add(displayName)
//                            deviceListAdapter.notifyDataSetChanged()
//                            appendLog("📡 列表发现: $displayName (类型:$type)")
//                        }
//                    }
//                }
//
//                override fun onScanFailed(errorCode: Int) {
//                    appendLog("❌ 列表扫描失败，错误码: $errorCode")
//                    runOnUiThread {
//                        tvStatus.text = "扫描失败"
//                    }
//                }
//            }
//
//            val settings = ScanSettings.Builder()
//                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
//                .build()
//            bluetoothAdapter?.bluetoothLeScanner?.startScan(null, settings, listScanCallback)
//
//            // 15秒后停止扫描
//            Handler(Looper.getMainLooper()).postDelayed({
//                stopListScan()
//                runOnUiThread {
//                    if (deviceList.isEmpty()) {
//                        tvStatus.text = "未发现可用设备"
//                        appendLog("⚠️ 未发现符合条件的设备")
//                    } else {
//                        tvStatus.text = "扫描完成，点击列表项连接"
//                    }
//                }
//            }, 15000)
//        } else {
//            // Android 4.3-4.4 使用旧 API
//            @Suppress("DEPRECATION")
//            bluetoothAdapter?.startLeScan(object : BluetoothAdapter.LeScanCallback {
//                override fun onLeScan(device: BluetoothDevice, rssi: Int, scanRecord: ByteArray?) {
//                    val name = device.name ?: "未知设备"
//                    val address = device.address
//                    val type = device.type
//
//                    if (name.uppercase().contains("MIDBOW1S")) return
//
//                    when (type) {
//                        BluetoothDevice.DEVICE_TYPE_CLASSIC,
//                        BluetoothDevice.DEVICE_TYPE_DUAL,
//                        BluetoothDevice.DEVICE_TYPE_UNKNOWN -> { /* 接受 */ }
//                        BluetoothDevice.DEVICE_TYPE_LE -> return
//                        else -> return
//                    }
//
//                    if (!deviceList.any { it.address == address }) {
//                        deviceList.add(device)
//                        val displayName = if (name == "未知设备") "$name ($address)" else name
//                        deviceListAdapter.add(displayName)
//                        deviceListAdapter.notifyDataSetChanged()
//                        appendLog("📡 列表发现: $displayName")
//                    }
//                }
//            })
//            // 超时处理
//            Handler(Looper.getMainLooper()).postDelayed({
//                runOnUiThread {
//                    // 旧 API 无法直接停止单个扫描，但我们可以标记结束
//                    if (deviceList.isEmpty()) {
//                        tvStatus.text = "未发现可用设备"
//                        appendLog("⚠️ 未发现符合条件的设备")
//                    } else {
//                        tvStatus.text = "扫描完成，点击列表项连接"
//                    }
//                }
//            }, 15000)
//        }
//    }


//    private fun stopListScan() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//            listScanCallback?.let {
//                try {
//                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
//                        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
//                        // 无权限无法停止，但通常有权限
//                    }
//                    bluetoothAdapter?.bluetoothLeScanner?.stopScan(it)
//                } catch (e: Exception) {
//                    appendLog("停止扫描异常: ${e.message}")
//                }
//            }
//            listScanCallback = null
//        } else {
//            @Suppress("DEPRECATION")
//            bluetoothAdapter?.stopLeScan(null)
//        }
//    }


    /**
     * 速度初始化：7次E0 + 1次E1，稳定在2档
     */
    private fun initSpeed() {
        if (isConnected) {
            appendLog("⚡ 初始化速度：7次减速 + 1次加速")
            repeat(7) {
                sendCommand("FE 55 10 E0 55 FE")
                Thread.sleep(50)
            }
            sendCommand("FE 55 10 E1 55 FE")
        } else {
            appendLog("⚠️ 未连接，模拟速度初始化（档位设为2）")
        }
        currentSpeedLevel = 2
        isSpeedInitDone = true
        runOnUiThread {
            findViewById<SeekBar>(R.id.speedSeekBar).progress = 1
            findViewById<TextView>(R.id.tvSpeedLevel).text = "当前档位：2 / 7"
        }
    }

    /**
     * 调节速度档位（1~7）
     */
    private fun setSpeedLevel(targetLevel: Int) {
        val clampedTarget = targetLevel.coerceIn(1, 7)
        val diff = clampedTarget - currentSpeedLevel
        if (diff == 0) return

        // 始终更新内部状态和 UI
        currentSpeedLevel = clampedTarget
        runOnUiThread {
            findViewById<TextView>(R.id.tvSpeedLevel).text = "当前档位：$clampedTarget / 7"
            findViewById<SeekBar>(R.id.speedSeekBar).progress = clampedTarget - 1
        }

        // 更新动画速度（无论是否连接）
        motionView.setAction(currentActionCode, clampedTarget, paused = false)

        // 只有连接时才发送指令
        if (isConnected) {
            val command = if (diff > 0) "FE 55 10 E1 55 FE" else "FE 55 10 E0 55 FE"
            repeat(kotlin.math.abs(diff)) {
                sendCommand(command)
                Thread.sleep(80)
            }
            appendLog("⚡ 速度切换至 $clampedTarget 档")
        } else {
            appendLog("⚠️ 未连接，仅模拟速度变化")
        }
    }

    /**
     * 切换律动动作
     * @param actionCode 例如 "F3", "F4" ...
     */
    private fun switchAction(actionCode: String) {
        // 1. 始终更新当前动作（无论是否连接）
        currentActionCode = actionCode
        val displayName = actionDisplayNameMap[actionCode] ?: actionCode
        tvCurrentAction.text = "当前：$displayName"
        appendLog("🎵 切换到动作 $actionCode")

        // 🆕 更新动画（无论是否连接）
        motionView.setAction(actionCode, currentSpeedLevel, paused = false)

        // 2. 如果已连接，发送指令
        if (isConnected) {
            sendCommand("FE 55 10 F2 55 FE")
            tvPE.text = "PE: ----"
            tvBPM.text = "BPM: ----"
            tvActionStatus.text = "动作: ----"
            Thread.sleep(100)
            sendCommand(actionMap[actionCode] ?: return)
            Thread.sleep(100)
            sendCommand("FE 55 10 F0 55 FE")
        } else {
            appendLog("⚠️ 未连接，仅模拟切换")
        }

        // 3. 速度滑块启用状态
        if (actionCode in speedableActions) {
            runOnUiThread {
                findViewById<SeekBar>(R.id.speedSeekBar).isEnabled = true
            }
            // 如果已连接，恢复速度档位（否则只更新UI）
            if (isConnected) {
                setSpeedLevel(currentSpeedLevel)
            }
        } else {
            runOnUiThread {
                findViewById<SeekBar>(R.id.speedSeekBar).isEnabled = false
            }
        }
    }







//    private fun updateMusicUI(
//        title: String,
//        artist: String,
//        isPlaying: Boolean,
//        duration: Long,
//        position: Long,
//        token: MediaSession.Token?
//    ) {
//        runOnUiThread {
//            // 1. 更新标题和艺术家（广播数据优先）
//            if (title.isNotEmpty()) {
//                tvMusicTitle.text = title
//            } else {
//                // 如果标题为空，根据播放状态显示占位符
//                tvMusicTitle.text = if (isPlaying) "未知歌曲" else "未播放"
//            }
//            tvMusicArtist.text = if (artist.isNotEmpty()) " - $artist" else ""
//
//            // 2. 更新播放按钮（必须立即更新）
//            btnPlayPause.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
//
//            // 3. 更新进度（如果有 duration 和 position）
//            if (duration > 0) {
//                tvMusicDuration.text = formatTime(duration)
//                musicDuration = duration
//                musicProgressBar.max = 100
//                val prog = if (position >= 0 && duration > 0) (position.toFloat() / duration * 100).toInt().coerceIn(0, 100) else 0
//                musicProgressBar.progress = prog
//                tvMusicPosition.text = if (position >= 0) formatTime(position) else "--:--"
//                musicProgressBar.isEnabled = true
//            } else {
//                tvMusicDuration.text = "--:--"
//                musicProgressBar.progress = 0
//                tvMusicPosition.text = "--:--"
//                musicProgressBar.isEnabled = false
//            }
//
//            // 4. 处理 MediaController（异步获取精确数据）
//            if (token != null) {
//                mediaController?.unregisterCallback(mediaControllerCallback)
//                mediaController = MediaController(this, token)
//                mediaController?.registerCallback(mediaControllerCallback)
//                // 立即刷新一次补充数据（但不会覆盖标题）
//                updateUIFromController()
//                startProgressUpdates()
//            } else {
//                // 无 token 且未播放时，重置UI
//                if (!isPlaying && title.isEmpty()) {
//                    mediaController?.unregisterCallback(mediaControllerCallback)
//                    mediaController = null
//                    resetUI()
//                    stopProgressUpdates()
//                }
//            }
//        }
//    }

    private fun updateMusicUI(
        title: String,
        artist: String,
        isPlaying: Boolean,
        duration: Long,
        position: Long,
        token: MediaSession.Token?
    ) {
        // 更新状态（仅在有效数据时更新）
        if (title.isNotEmpty() || isPlaying) {
            lastPlayingState = isPlaying
            lastMusicTitle = title
        }
        appendLog("updateMusicUI:MusicUI,updateMusicUI: title=$title, artist=$artist, isPlaying=$isPlaying, duration=$duration, position=$position, token=$token")
        // 🔥 防覆盖：如果当前已有有效的播放信息，且新传入的数据为空或无效，则忽略
        if (lastMusicTitle.isNotEmpty() && lastPlayingState) {
            if (title.isEmpty() && !isPlaying && duration <= 0) {
                appendLog("⚠️ 忽略空数据广播，保留当前UI")
                return
            }
        }
        // 1. 立即更新 UI（使用传入的数据）
        runOnUiThread {
            if (title.isNotEmpty()) {
                tvMusicTitle.text = title
            } else {
                // 如果标题为空，但播放状态为 true，显示“未知歌曲”
                tvMusicTitle.text = if (isPlaying) "未知歌曲" else "未播放"
            }
            tvMusicArtist.text = if (artist.isNotEmpty()) " - $artist" else ""
            btnPlayPause.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)

            if (duration > 0) {
                tvMusicDuration.text = formatTime(duration)
                musicDuration = duration
                musicProgressBar.max = 100
                val prog = if (position >= 0 && duration > 0) (position.toFloat() / duration * 100).toInt().coerceIn(0, 100) else 0
                musicProgressBar.progress = prog
                tvMusicPosition.text = if (position >= 0) formatTime(position) else "--:--"
                musicProgressBar.isEnabled = true
            } else {
                tvMusicDuration.text = "--:--"
                musicProgressBar.progress = 0
                tvMusicPosition.text = "--:--"
                musicProgressBar.isEnabled = false
            }
        }

        // 2. 处理 MediaController（用于后续精确数据和回调）
        if (token != null) {
            mediaController?.unregisterCallback(mediaControllerCallback)
            mediaController = MediaController(this, token)
            mediaController?.registerCallback(mediaControllerCallback)
            // 立即刷新一次补充精确数据（但不会覆盖标题）
            updateUIFromController()
            startProgressUpdates()
            // 记录状态，避免广播重复更新
//            lastPlayingState = isPlaying
//            lastMusicTitle = title
        } else {
            // 无 token 且未播放时，重置 UI（但不会覆盖已经显示的内容）
            if (!isPlaying && title.isEmpty()) {
                mediaController?.unregisterCallback(mediaControllerCallback)
                mediaController = null
                resetUI()
                stopProgressUpdates()
            }
        }

    }


    private fun startProgressUpdates() {
        stopProgressUpdates() // 先停止旧的
        progressUpdateRunnable = object : Runnable {
            override fun run() {
                updateProgressFromController()
                // 每隔 500ms 刷新一次
                handler.postDelayed(this, 500)
            }
        }
        handler.post(progressUpdateRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressUpdateRunnable?.let { handler.removeCallbacks(it) }
        progressUpdateRunnable = null
    }

    private fun updateProgressFromController() {
        val controller = mediaController ?: return
        val playbackState = controller.playbackState
        val metadata = controller.metadata
        if (playbackState != null && metadata != null) {
            val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            val position = playbackState.position
            val isPlaying = playbackState.state == PlaybackState.STATE_PLAYING
            runOnUiThread {
                // 只在非拖动状态下更新进度条
                if (!isDragging) {
                    if (duration > 0) {
                        musicProgressBar.max = 100
                        val progress = (position.toFloat() / duration * 100).toInt().coerceIn(0, 100)
                        musicProgressBar.progress = progress
                        tvMusicPosition.text = formatTime(position)
                        tvMusicDuration.text = formatTime(duration)
                        musicProgressBar.isEnabled = true
                    } else {
                        musicProgressBar.progress = 0
                        tvMusicPosition.text = "--:--"
                        tvMusicDuration.text = "--:--"
                        musicProgressBar.isEnabled = false
                    }
                }
                // 按钮状态总是更新
                btnPlayPause.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            }
        }
    }


    private fun updateUIFromController() {
        val controller = mediaController ?: return
        val playbackState = controller.playbackState
        val metadata = controller.metadata

        runOnUiThread {
            // 更新元数据
            if (metadata != null) {
                val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                tvMusicTitle.text = title
                tvMusicArtist.text = if (artist.isNotEmpty()) " - $artist" else ""
                if (duration > 0) {
                    tvMusicDuration.text = formatTime(duration)
                    musicDuration = duration
                }
            }

            // 更新播放状态和进度（仅 UI，不修改 lastPlayingState）
            if (playbackState != null) {
                val isPlaying = playbackState.state == PlaybackState.STATE_PLAYING
                // 移除对 lastPlayingState 的修改
                btnPlayPause.setImageResource(
                    if (isPlaying) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play
                )
                val position = playbackState.position
                if (position >= 0 && musicDuration > 0) {
                    val progress = (position.toFloat() / musicDuration * 100).toInt().coerceIn(0, 100)
                    musicProgressBar.progress = progress
                    tvMusicPosition.text = formatTime(position)
                } else {
                    musicProgressBar.progress = 0
                    tvMusicPosition.text = "--:--"
                }
            } else {
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                musicProgressBar.progress = 0
                tvMusicPosition.text = "--:--"
            }
        }
    }

    private fun resetUI() {
        runOnUiThread {
            tvMusicTitle.text = "未播放"
            tvMusicArtist.text = ""
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            musicProgressBar.progress = 0
            tvMusicPosition.text = "0:00"
            tvMusicDuration.text = "0:00"
            musicProgressBar.isEnabled = false
        }
    }
    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val seconds = ms / 1000
        val min = seconds / 60
        val sec = seconds % 60
        return "$min:${"%02d".format(sec)}"
    }
    private fun sendMediaKey(keyCode: Int) {
        try {
            // 模拟媒体按键事件
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent)
            sendOrderedBroadcast(intent, null)
            appendLog("🎛 发送媒体按键: $keyCode")
        } catch (e: Exception) {
            appendLog("❌ 发送媒体按键失败: ${e.message}")
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = packageName
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }
    private fun openNotificationAccessSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabledListeners?.contains(packageName) == true
    }


    /**
     * 根据当前音乐强度动态更新冷却时间
     * 音乐越激烈，冷却时间越短（切换更频繁）
     * 音乐越舒缓，冷却时间越长（切换更稳定）
     */
    private fun updateDynamicCooldowns() {
        // 从管道状态获取归一化能量 [-1,1]，映射到 1~10 档位
        val rawEnergy = pipeline?.lastResult?.state?.energy ?: 0.0
        val energy = ((rawEnergy + 1.0) / 2.0 * 9.0 + 1.0).toInt().coerceIn(1, 10)

        // 根据能量档位计算动作冷却时间（4~12秒）
        val actionCooldownMs = when {
            energy >= 8 -> 4000L      // 高能：4秒
            energy >= 5 -> 7000L      // 中能：7秒（默认）
            else -> 12000L            // 低能：12秒
        }

        // 根据能量档位计算速度冷却时间（1.5~4秒）
        val speedCooldownMs = when {
            energy >= 8 -> 1500L      // 高能：1.5秒
            energy >= 5 -> 2600L      // 中能：2.6秒（默认）
            else -> 4000L             // 低能：4秒
        }

        // 如果发生了变化，更新并打印日志
        if (currentActionCooldownMs != actionCooldownMs || currentSpeedCooldownMs != speedCooldownMs) {
            currentActionCooldownMs = actionCooldownMs
            currentSpeedCooldownMs = speedCooldownMs
            appendLog("⚡ 冷却更新: 动作=${actionCooldownMs}ms, 速度=${speedCooldownMs}ms (能量=$energy)")
        }
    }
    private fun disconnectInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isConnected = false
        runOnUiThread {
            tvStatus.text = "已断开"
            btnDisconnect.isEnabled = false
            btnConnect.isEnabled = true
        }
    }

    private fun updatePlayState(isPlaying: Boolean) {
        if (isPlaying == lastPlayingState) return
        lastPlayingState = isPlaying
        appendLog(if (isPlaying) "▶️ 音乐恢复" else "⏸️ 音乐暂停")
        motionView.setPaused(!isPlaying)
        if (isConnected && isDancing) {
            sendCommand(if (isPlaying) "FE 55 10 F0 55 FE" else "FE 55 10 F2 55 FE")
        }
    }

    private fun fetchCurrentMediaInfo() {
        try {
            val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val activeSessions = mediaSessionManager.getActiveSessions(
                ComponentName(this, MusicNotificationListenerService::class.java)
            )
            if (activeSessions.isNotEmpty()) {
                val controller = activeSessions.first()
                val metadata = controller.metadata
                val playbackState = controller.playbackState
                if (metadata != null) {
                    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                    val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                    val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                    val position = playbackState?.position ?: 0
                    val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
                    val token = controller.sessionToken

                    // 立即更新 UI
                    updateMusicUI(title, artist, isPlaying, duration, position, token)

                    // 保存 controller 并注册回调
                    mediaController = controller
                    mediaController?.registerCallback(mediaControllerCallback)
                    startProgressUpdates()

                    // 记录状态
                    lastPlayingState = isPlaying
                    lastMusicTitle = title
                    appendLog("🎵 主动拉取音乐信息: $title - $artist")
                }
            } else {
                appendLog("⏳ 无活跃 MediaSession，等待通知广播")
            }
        } catch (e: Exception) {
            appendLog("❌ 主动拉取失败: ${e.message}")
        }
    }
    private val sessionCallback = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        controllers?.firstOrNull()?.let { controller ->
            val token = controller.sessionToken
            val metadata = controller.metadata
            if (metadata != null) {
                updateMusicUI(
                    metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
                    controller.playbackState?.state == PlaybackState.STATE_PLAYING,
                    metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
                    controller.playbackState?.position ?: 0,
                    token
                )
                mediaController = controller
                mediaController?.registerCallback(mediaControllerCallback)
                startProgressUpdates()
            }
        }
    }
}