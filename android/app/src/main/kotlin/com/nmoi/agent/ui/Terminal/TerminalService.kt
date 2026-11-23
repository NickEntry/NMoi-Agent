package com.nmoi.agent.ui.Terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 终端在后台跑命令时的常驻通知（D400）。
 *
 * 用户 2026-11-24 的要求：有会话在跑时后台有通知（照 Termux 那种），
 * 展开后有「退出」，点了自动退出所有会话。
 *
 * # 为什么必须是前台服务
 *
 * 命令跑在进程里的一个协程上。应用切到后台之后，没有前台服务的话系统随时可以
 * 收掉这个进程——命令就跟着没了，而用户以为它还在跑。前台服务是唯一能让
 * "我还在干活"这件事被系统承认的方式，那颗通知就是它的凭据。
 *
 * # 通知上那两个动作
 *
 * - 点通知本体：回到终端页（`MainActivity`，带一个"打开终端"的标记）。
 * - 「退出」：关掉全部会话并停掉这个服务（`closeAll` 会先停掉跑着的命令）。
 *
 * 不做"逐个会话退出"：通知栏里放一排会话名，点错一个就是停错一条命令，
 * 而那种错没有撤销。要精细操作就回应用里（会话胶囊长按）。
 */
internal class TerminalService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            TerminalSessions.closeAll()
            stopSelf()
            return START_NOT_STICKY
        }
        val count = TerminalSessions.runningCount()
        if (count == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(count)
        return START_NOT_STICKY
    }

    private fun startInForeground(count: Int) {
        createChannel(this)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, Class.forName("com.nmoi.agent.MainActivity")).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_OPEN_TERMINAL, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val quit = PendingIntent.getService(
            this,
            1,
            Intent(this, TerminalService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("终端")
            .setContentText(if (count == 1) "1 个会话在跑" else "$count 个会话在跑")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "退出", quit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
    }

    companion object {
        const val CHANNEL = "nmoi.terminal"
        const val ID = 4101
        const val ACTION_QUIT = "com.nmoi.agent.TERMINAL_QUIT"
        const val EXTRA_OPEN_TERMINAL = "open_terminal"

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "终端", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "终端里有命令在跑时常驻"
                    setShowBadge(false)
                },
            )
        }
    }
}

/**
 * 通知的开关（D400）：跟着"有没有命令在跑"走。
 *
 * 每一条命令结束时调一次 `sync`：还在跑就更新那颗通知（会话数变了），
 * 都跑完了就停掉服务。**不在命令开始时同步**——那时会话的 `busy` 还没置上，
 * 会得到"0 个在跑"然后立刻停掉。
 */
internal object TerminalNotifications {

    fun sync(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, TerminalService::class.java)
        if (TerminalSessions.runningCount() == 0) {
            runCatching { app.stopService(intent) }
            return
        }
        // 通知权限没给时 startForegroundService 会抛（Android 13+ 且用户拒绝）。
        // 那种情况下不弹红字：命令照跑，只是后台没有那颗通知。
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }
    }
}
