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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Owns one started ChatClient per profile so messages keep arriving (for every profile) in the background. */
@OptIn(ExperimentalCoroutinesApi::class)
class LichenService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var profiles: Profiles

    private class Running(val client: ChatClient, val job: Job)
    private val running = HashMap<String, Running>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        val note = statusNotification("Connecting…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(STATUS_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(STATUS_ID, note)

        profiles = Backend.profiles(this)
        if (profiles.clients.value.isEmpty()) { stopSelf(); return }

        // Start the client of every profile; follow profiles being added, deleted or reopened (new settings).
        scope.launch {
            profiles.clients.collect { clients ->
                synchronized(running) {
                    // A deleted/replaced client is stopped by Profiles; here only its collectors go away.
                    running.keys.filter { id -> clients[id] !== running[id]?.client }.forEach { running.remove(it)?.job?.cancel() }
                    for ((id, c) in clients) if (id !in running) running[id] = startClient(id, c)
                }
                if (clients.isEmpty()) stopSelf()
            }
        }
        scope.launch {
            profiles.clients.flatMapLatest { m ->
                if (m.isEmpty()) flowOf(emptyList()) else combine(m.values.map { it.hubs }) { it.toList() }
            }.collect { perProfile ->
                val up = perProfile.sumOf { hubs -> hubs.count { it.connected } }
                val total = perProfile.sumOf { it.size }
                val n = perProfile.size
                val text = when {
                    n > 1 -> "$n profiles · $up/$total hubs connected"
                    total == 0 -> "No hubs set up"
                    up == 0 -> "Connecting…"
                    up == 1 -> "Connected to 1 hub"
                    else -> "Connected to $up hubs"
                }
                if (canNotify()) NotificationManagerCompat.from(this@LichenService).notify(STATUS_ID, statusNotification(text))
            }
        }
    }

    private fun startClient(id: String, c: ChatClient): Running {
        val job = SupervisorJob(scope.coroutineContext[Job])
        val s = CoroutineScope(scope.coroutineContext + job)
        s.launch { runCatching { c.start() } }
        s.launch {
            c.events.collect { e ->
                if (e is ClientEvent.Incoming && Backend.currentChat.value != OpenChat(id, e.contact.pubkey)) notifyMessage(id, e)
            }
        }
        return Running(c, job)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        val clients = synchronized(running) { running.values.map { it.client }.also { running.clear() } }
        scope.cancel()
        if (clients.isNotEmpty()) CoroutineScope(Dispatchers.Default).launch { clients.forEach { runCatching { it.stop() } } }
        super.onDestroy()
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openIntent(profileId: String?, pubkey: String?): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (pubkey != null && profileId != null) {
                putExtra(EXTRA_CHAT, pubkey)
                putExtra(EXTRA_PROFILE, profileId)
            }
        }
        return PendingIntent.getActivity(
            this, if (pubkey != null) "$profileId/$pubkey".hashCode() else 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
            .setContentIntent(openIntent(null, null))
            .build()

    private fun notifyMessage(profileId: String, e: ClientEvent.Incoming) {
        if (!canNotify()) return
        // With several profiles say which one it is for: "Alice → you (work)".
        val title = if (profiles.infos.value.size > 1) {
            "${e.contact.displayName} → you (${profiles.name(profileId) ?: "?"})"
        } else e.contact.displayName
        val n = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(e.message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(e.message.text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openIntent(profileId, e.contact.pubkey))
            .build()
        NotificationManagerCompat.from(this).notify("$profileId/${e.contact.pubkey}".hashCode(), n)
    }

    companion object {
        const val CHANNEL_STATUS = "connection"
        const val CHANNEL_MESSAGES = "messages"
        const val STATUS_ID = 1
        const val EXTRA_CHAT = "chat"
        const val EXTRA_PROFILE = "profile"

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
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Backend.profiles(context).clients.value.isNotEmpty()) {
            runCatching { LichenService.start(context) }
        }
    }
}
