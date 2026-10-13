package dev.lichen.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.lichen.core.api.ChatClient
import dev.lichen.core.api.ClientEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Owns the started ChatClient so messages keep arriving while the app is in the background. */
class LichenService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: ChatClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        val note = statusNotification("Connecting…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(STATUS_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(STATUS_ID, note)

        if (!Backend.setup.hasIdentity(Backend.dataDir(this))) { stopSelf(); return }
        val c = Backend.client(this)
        client = c
        scope.launch { runCatching { c.start() } }
        scope.launch {
            c.hubs.collect { hubs ->
                val up = hubs.count { it.connected }
                val text = when {
                    hubs.isEmpty() -> "No hubs set up"
                    up == 0 -> "Connecting…"
                    up == 1 -> "Connected to 1 hub"
                    else -> "Connected to $up hubs"
                }
                NotificationManagerCompat.from(this@LichenService).let { nm ->
                    if (canNotify()) nm.notify(STATUS_ID, statusNotification(text))
                }
            }
        }
        scope.launch {
            c.events.collect { e ->
                if (e is ClientEvent.Incoming && Backend.currentChat.value != e.contact.pubkey) notifyMessage(e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        val c = client
        client = null
        scope.cancel()
        if (c != null) CoroutineScope(Dispatchers.Default).launch { runCatching { c.stop() } }
        super.onDestroy()
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openIntent(pubkey: String?): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (pubkey != null) putExtra(EXTRA_CHAT, pubkey)
        }
        return PendingIntent.getActivity(
            this, pubkey?.hashCode() ?: 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun statusNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Lichen")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent(null))
            .build()

    private fun notifyMessage(e: ClientEvent.Incoming) {
        if (!canNotify()) return
        val n = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(e.contact.displayName)
            .setContentText(e.message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(e.message.text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openIntent(e.contact.pubkey))
            .build()
        NotificationManagerCompat.from(this).notify(e.contact.pubkey.hashCode(), n)
    }

    companion object {
        const val CHANNEL_STATUS = "connection"
        const val CHANNEL_MESSAGES = "messages"
        const val STATUS_ID = 1
        const val EXTRA_CHAT = "chat"

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "A quiet notification while Lichen is connected to your hubs"
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH),
            )
        }

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, LichenService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LichenService::class.java))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Backend.setup.hasIdentity(Backend.dataDir(context))) {
            runCatching { LichenService.start(context) }
        }
    }
}
