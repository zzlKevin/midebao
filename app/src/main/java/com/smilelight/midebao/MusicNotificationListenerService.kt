package com.smilelight.midebao

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.media.session.MediaSession

/**
 * 音乐通知监听服务
 * 解析主流音乐APP的通知，提取歌曲信息、播放状态、进度等
 * 通过 LocalBroadcast 发送给主 Activity
 */
class MusicNotificationListenerService : NotificationListenerService() {


    private val EXTRA_IS_PLAYING = "android.intent.extra.PLAYING"
    private val EXTRA_DURATION = "android.intent.extra.DURATION"
    private val EXTRA_POSITION = "android.intent.extra.POSITION"
    companion object {
        const val ACTION_MUSIC_UPDATE = "com.smilelight.midebao.MUSIC_UPDATE"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_POSITION = "position"

        // 主流音乐APP包名（可根据需要增删）
        private val MUSIC_PACKAGES = setOf(
            "com.tencent.qqmusic",
            "com.netease.cloudmusic",
            "com.kugou.android",
            "com.kuwo.player",
            "com.spotify.music",
            "com.android.music",
            "com.google.android.music"
        )
    }

    private lateinit var localBroadcastManager: LocalBroadcastManager

    override fun onCreate() {
        super.onCreate()
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        Log.d("MusicListener", "MusicNotificationListenerService created")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("MusicListener", "Notification listener connected")
        // 主动请求一次当前所有通知，获取正在播放的音乐
        val activeNotifications = activeNotifications
        if (activeNotifications != null) {
            for (sbn in activeNotifications) {
                val musicInfo = extractMusicInfo(sbn)
                if (musicInfo != null) {
                    sendMusicBroadcast(musicInfo)
                    break // 只处理第一个匹配的音乐通知
                }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val musicInfo = extractMusicInfo(sbn)
        if (musicInfo != null) {
            sendMusicBroadcast(musicInfo)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 通知被移除时，可能音乐停止或切换，我们可以通知主界面重置状态
        // 但为了避免误判，只有当所有音乐通知都移除时才认为停止
        // 这里简化处理：如果移除的通知是音乐通知，发送一个空数据
        if (sbn != null && isMusicPackage(sbn.packageName)) {
            // 检查是否还有其他音乐通知存在
            val remaining = activeNotifications?.any { isMusicPackage(it.packageName) } ?: false
            if (!remaining) {
                sendMusicBroadcast(null) // 通知主界面无音乐播放
            }
        }
    }

    /**
     * 判断是否为已知的音乐APP包名
     */
    private fun isMusicPackage(pkg: String): Boolean {
        return MUSIC_PACKAGES.any { pkg.startsWith(it) }  // 支持子包名
    }

    /**
     * 从 StatusBarNotification 中提取音乐信息
     * @return MusicInfo 对象，包含歌名、歌手、播放状态、进度等，如果不是音乐通知则返回 null
     */
    private fun extractMusicInfo(sbn: StatusBarNotification): MusicInfo? {
        val pkg = sbn.packageName
        if (!isMusicPackage(pkg)) return null

        val notification = sbn.notification
        val extras = notification.extras

        val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        } else null

        // 获取标题和文本
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""

        // 判断是否为音乐通知（通常标题包含歌曲名，文本包含歌手名）
        if (title.isEmpty() && text.isEmpty()) return null

        // 提取播放状态（部分APP支持）
        val isPlaying = extras.getBoolean(EXTRA_IS_PLAYING, false)

        // 提取进度（部分APP可能放在 extra 中）
        var duration = extras.getLong(EXTRA_DURATION, -1)
        var position = extras.getLong(EXTRA_POSITION, -1)

        // 某些APP使用自定义key，尝试解析
        if (duration == -1L && extras.containsKey("duration")) {
            duration = extras.getLong("duration", -1)
        }
        if (position == -1L && extras.containsKey("position")) {
            position = extras.getLong("position", -1)
        }

        // 如果都没有，尝试从 MediaSession 获取（较复杂，暂不实现）

        // 额外：尝试从 BigTextStyle 或 InboxStyle 获取更多文本（但多数音乐通知用标准格式）

        // 如果标题或文本为空，可能不是音乐通知
        if (title.isEmpty() || text.isEmpty()) return null

        // 特殊情况：某些APP将歌名和歌手放在同一字段，尝试拆分
        var artist = text
        var songTitle = title
        // 如果标题包含 "-" 分割，可能是 "歌名 - 歌手"
        if (title.contains(" - ")) {
            val parts = title.split(" - ")
            if (parts.size >= 2) {
                songTitle = parts[0]
                artist = parts[1]
            }
        }

        return MusicInfo(
            packageName = pkg,
            title = songTitle,
            artist = artist,
            isPlaying = isPlaying,
            duration = if (duration > 0) duration else -1,
            position = if (position >= 0) position else -1,
            mediaSessionToken = token
        )
    }

    /**
     * 发送音乐信息广播
     */
    private fun sendMusicBroadcast(info: MusicInfo?) {
        val intent = Intent(ACTION_MUSIC_UPDATE)
        if (info != null) {
            intent.putExtra(EXTRA_TITLE, info.title)
            intent.putExtra(EXTRA_ARTIST, info.artist)
            intent.putExtra(EXTRA_IS_PLAYING, info.isPlaying)
            intent.putExtra(EXTRA_DURATION, info.duration)
            intent.putExtra(EXTRA_POSITION, info.position)
            intent.putExtra("media_session_token", info?.mediaSessionToken)
        } else {
            // 发送空数据表示无音乐
            intent.putExtra(EXTRA_TITLE, "")
            intent.putExtra(EXTRA_ARTIST, "")
            intent.putExtra(EXTRA_IS_PLAYING, false)
            intent.putExtra(EXTRA_DURATION, -1L)
            intent.putExtra(EXTRA_POSITION, -1L)

        }
        localBroadcastManager.sendBroadcast(intent)
        Log.d("MusicListener", "Broadcast sent: $info")
    }

    /**
     * 数据类，用于传递音乐信息
     */
    data class MusicInfo(
        val packageName: String,
        val title: String,
        val artist: String,
        val isPlaying: Boolean,
        val duration: Long,   // -1 表示未知
        val position: Long,    // -1 表示未知
        val mediaSessionToken: MediaSession.Token? = null  // 新增
    ) {
        override fun toString(): String {
            return "MusicInfo(title='$title', artist='$artist', isPlaying=$isPlaying, duration=$duration, position=$position)"
        }
    }
}