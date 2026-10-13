package dev.lichen.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.lichen.app.ui.*
import dev.lichen.core.api.ChatClient
import dev.lichen.core.api.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { LichenTheme { LichenApp() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i?.getStringExtra(LichenService.EXTRA_CHAT)?.let { Backend.openChat.value = it }
    }
}

@Composable
private fun LichenApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasIdentity by remember { mutableStateOf(Backend.setup.hasIdentity(Backend.dataDir(ctx))) }
    // Back stack of screens: "chat:<pk>", "info:<pk>", "people", "profile". Empty = the chat list.
    var stack by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    var client by remember { mutableStateOf<ChatClient?>(null) }

    fun push(s: String) { stack = ArrayList(stack + s) }
    fun pop() { if (stack.isNotEmpty()) stack = ArrayList(stack.dropLast(1)) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(hasIdentity) {
        if (!hasIdentity) return@LaunchedEffect
        client = Backend.client(ctx)
        LichenService.start(ctx)
        val prefs = ctx.getSharedPreferences("lichen_ui", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notifications", false) &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val open by Backend.openChat.collectAsState()
    LaunchedEffect(open, client) {
        val pk = open
        if (pk != null && client != null) {
            Backend.openChat.value = null
            stack = arrayListOf("chat:$pk")
        }
    }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    val c = client
    if (!hasIdentity || c == null) {
        if (!hasIdentity) OnboardingScreen(onDone = { hasIdentity = true })
        return
    }

    val top = stack.lastOrNull().orEmpty()
    when {
        top.startsWith("chat:") -> {
            val pk = top.removePrefix("chat:")
            ChatScreen(c, pk, onBack = ::pop, onInfo = { push("info:$pk") })
        }
        top.startsWith("info:") -> InfoScreen(c, top.removePrefix("info:"), onBack = ::pop)
        top == "people" -> PeopleScreen(c, onBack = ::pop, onOpenChat = { pk ->
            stack = arrayListOf("chat:$pk")
        })
        top == "profile" -> ProfileScreen(c, onBack = ::pop, onSaveHubs = { name, hubs ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    // Saving settings needs the client restarted: stop it, reopen, start again.
                    LichenService.stop(ctx)
                    Backend.setup.saveSettings(Backend.dataDir(ctx), Settings(name, hubs))
                    Backend.reset()
                }
                client = Backend.client(ctx)
                LichenService.start(ctx)
            }
        })
        else -> ContactsScreen(c, onOpenChat = { push("chat:$it") }, onNewChat = { push("people") }, onProfile = { push("profile") })
    }
}
