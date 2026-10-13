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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.lichen.app.ui.*
import dev.lichen.app.ui.theme.LichenTheme
import dev.lichen.core.api.ChatClient
import dev.lichen.core.api.Settings
import dev.lichen.core.api.Strangers
import kotlinx.coroutines.launch

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
        val pk = i?.getStringExtra(LichenService.EXTRA_CHAT) ?: return
        val profile = i.getStringExtra(LichenService.EXTRA_PROFILE) ?: return
        Backend.openChat.value = OpenChat(profile, pk)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LichenApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles = remember { Backend.profiles(ctx) }
    val activeId by profiles.active.collectAsState()
    val infos by profiles.infos.collectAsState()
    val clients by profiles.clients.collectAsState()
    // Back stack of screens: "chat:<pk>", "info:<pk>", "group:<id>", "ginfo:<id>", "newgroup", "people", "profile",
    // "colors", "manage", "add:<new profile id>".
    // Empty = the chat list.
    var stack by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    var switcher by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProfileInfo?>(null) }

    fun push(s: String) { stack = ArrayList(stack + s) }
    fun pop() {
        val top = stack.lastOrNull() ?: return
        if (top.startsWith("add:")) profiles.cancelAdd(top.removePrefix("add:"))
        stack = ArrayList(stack.dropLast(1))
    }
    fun switchTo(id: String) {
        switcher = false
        if (id != activeId) profiles.switchTo(id)
        stack = ArrayList()
    }
    fun startAdd() {
        switcher = false
        push("add:" + profiles.beginAdd())
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(activeId != null) {
        if (activeId == null) return@LaunchedEffect
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
    LaunchedEffect(open, infos) {
        val o = open
        if (o != null && infos.any { it.id == o.profileId }) {
            Backend.openChat.value = null
            if (o.profileId != profiles.active.value) profiles.switchTo(o.profileId)
            stack = arrayListOf(if (o.pubkey.startsWith("group:")) o.pubkey else "chat:${o.pubkey}")
        }
    }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    // The very first profile: onboarding, no way back.
    if (activeId == null) {
        val pending = rememberSaveable { profiles.beginAdd() }
        OnboardingScreen(profiles.dir(pending), onDone = { profiles.finishAdd(pending) })
        return
    }
    val id = activeId!!
    val top = stack.lastOrNull().orEmpty()
    if (top.startsWith("add:")) {
        val pending = top.removePrefix("add:")
        OnboardingScreen(profiles.dir(pending), onDone = {
            if (profiles.finishAdd(pending)) stack = ArrayList()
        }, onCancel = ::pop)
        return
    }

    deleting?.let { p ->
        DeleteProfileDialog(p, onDismiss = { deleting = null }, onConfirm = {
            deleting = null
            scope.launch {
                profiles.delete(p.id)
                stack = ArrayList()
            }
        })
    }

    val c = clients[id]
    if (c == null) {
        ManageProfilesScreen(
            infos, clients, id, onBack = ::pop, onSwitch = ::switchTo, onAdd = ::startAdd, onDelete = { deleting = it },
        )
        return
    }

    // Chats / Requests / Settings along the bottom of those three pages (not the full-screen pages opened from them).
    // It steps aside while the keyboard is up.
    val requests by c.requests.collectAsState()
    val tab = when {
        stack.isEmpty() -> 0
        stack == listOf("requests") -> 1
        stack == listOf("profile") -> 2
        else -> -1
    }
    fun openTab(root: String?) { stack = if (root == null) ArrayList() else arrayListOf(root) }
    val nav = tab >= 0 && !WindowInsets.isImeVisible
    Column(Modifier.fillMaxSize()) {
        // The nav bar takes the system navigation inset, so the screens above it mustn't pad for it again.
        Box(Modifier.weight(1f).then(if (nav) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)) {
            key(id) { Screen(c, id, top, stack.size == 1, infos, ::push, ::pop, ::openTab, onSetStack = { stack = it },
                onSwitcher = { switcher = true }, onDeleting = { deleting = it }, onSwitch = ::switchTo, onAdd = ::startAdd,
                onSaveSettings = { s -> scope.launch { profiles.saveSettings(id, s) } }, clients = clients) }
        }
        if (nav) SlabNav(
            selected = tab, requests = requests.size, onChats = { openTab(null) }, onRequests = { openTab("requests") },
            onSettings = { openTab("profile") },
        )
    }

    if (switcher) {
        ProfileSwitcherSheet(
            infos, clients, id, onSwitch = ::switchTo, onAdd = ::startAdd,
            onManage = { switcher = false; push("manage") }, onDismiss = { switcher = false },
        )
    }
}

@Composable
private fun Screen(
    c: ChatClient, id: String, top: String, atRoot: Boolean, infos: List<ProfileInfo>,
    push: (String) -> Unit, pop: () -> Unit, openTab: (String?) -> Unit, onSetStack: (ArrayList<String>) -> Unit,
    onSwitcher: () -> Unit, onDeleting: (ProfileInfo?) -> Unit, onSwitch: (String) -> Unit, onAdd: () -> Unit,
    onSaveSettings: (Settings) -> Unit, clients: Map<String, ChatClient>,
) {
    when {
        top == "manage" -> ManageProfilesScreen(
            infos, clients, id, onBack = pop, onSwitch = onSwitch, onAdd = onAdd, onDelete = { onDeleting(it) },
        )
        top.startsWith("chat:") -> {
            val pk = top.removePrefix("chat:")
            ChatScreen(c, id, pk, onBack = pop, onInfo = { push("info:$pk") })
        }
        top.startsWith("group:") -> {
            val gid = top.removePrefix("group:")
            GroupChatScreen(c, id, gid, onBack = pop, onInfo = { push("ginfo:$gid") })
        }
        top.startsWith("ginfo:") -> GroupInfoScreen(c, top.removePrefix("ginfo:"), onBack = pop, onGone = { onSetStack(ArrayList()) })
        top == "newgroup" -> NewGroupScreen(c, onBack = pop, onCreated = { gid -> onSetStack(arrayListOf("group:$gid")) })
        top.startsWith("info:") -> InfoScreen(c, top.removePrefix("info:"), onBack = pop)
        top == "requests" -> RequestsScreen(c, onBack = pop.takeUnless { atRoot }, onOpenChat = { pk -> push("chat:$pk") })
        top == "debuglog" -> DebugLogScreen(c, onBack = pop)
        top == "colors" -> PaletteScreen(onBack = pop)
        top == "people" -> PeopleScreen(c, onBack = pop, onOpenChat = { pk -> onSetStack(arrayListOf("chat:$pk")) })
        top == "profile" -> ProfileScreen(c, onBack = pop.takeUnless { atRoot }, onSaveHubs = { name, hubs ->
            // The profile's client is stopped, its settings saved and a new client opened; the service starts it.
            val strangers = c.profile.value?.strangers ?: Strangers.REQUESTS
            onSaveSettings(Settings(name, hubs, strangers))
        }, onDelete = { onDeleting(infos.firstOrNull { it.id == id }) }, onDebugLog = { push("debuglog") },
            onColors = { push("colors") }, onSwitchProfile = onSwitcher)
        else -> ContactsScreen(
            c, infos.firstOrNull { it.id == id }?.name ?: "Lichen", id, onSwitcher = onSwitcher,
            onOpenChat = { push("chat:$it") }, onNewChat = { push("people") },
            onOpenGroup = { push("group:$it") }, onNewGroup = { push("newgroup") },
            onRequests = { openTab("requests") },
        )
    }
}
