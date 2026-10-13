package dev.lichen.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.lichen.app.ui.theme.*
import dev.lichen.core.api.*
import dev.lichen.core.unb64
import kotlinx.coroutines.launch

/** Hubs up / hubs configured, as a small mono tag with a status square. */
@Composable
fun HubChip(hubs: List<HubStatus>) {
    val p = Slab.colors
    val up = hubs.count { it.connected }
    val color = when {
        hubs.isEmpty() || up == 0 -> MaterialTheme.colorScheme.error
        up < hubs.size -> p.alert
        else -> p.accent
    }
    Row(
        Modifier.height(28.dp).slabBorder(p.line).padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(color))
        Text("$up/${hubs.size} HUBS", fontFamily = Slab.mono, fontSize = 10.sp, letterSpacing = 0.08.em, color = p.muted)
    }
}

@Composable
fun ContactsScreen(
    client: ChatClient, profileName: String, profileId: String, onSwitcher: () -> Unit,
    onOpenChat: (String) -> Unit, onNewChat: () -> Unit, onProfile: () -> Unit, onRequests: () -> Unit = {},
    onOpenGroup: (String) -> Unit = {}, onNewGroup: () -> Unit = {},
) {
    val p = Slab.colors
    val contacts by client.contacts.collectAsState()
    val groups by client.groups.groups.collectAsState()
    val scope = rememberCoroutineScope()
    val requests by client.requests.collectAsState()
    val hubs by client.hubs.collectAsState()
    Scaffold(
        containerColor = p.ground,
        topBar = {
            SlabTopBar(content = {
                LichenMark()
                Text("Lichen", fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = (-0.01).em, color = p.text)
                Spacer(Modifier.weight(1f))
                HubChip(hubs)
                ProfileTitle(profileName, profileId, onSwitcher)
            })
        },
        bottomBar = {
            SlabNav(selected = 0, requests = requests.size, onChats = {}, onRequests = onRequests, onSettings = onProfile)
        },
        floatingActionButton = { AddMenu(onAddSomeone = onNewChat, onNewGroup = onNewGroup) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            BatteryCard()
            if (requests.isNotEmpty()) RequestsBanner(requests.size, onRequests)
            if (contacts.isEmpty() && groups.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("NO CHATS YET")
                        Text("Tap + to add someone or start a group.", color = p.subtle, fontSize = 15.sp, textAlign = TextAlign.Center)
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)) {
                    groupRows(groups, onOpenGroup,
                        onAccept = { id -> scope.launch { client.groups.accept(id) } },
                        onDecline = { id -> scope.launch { client.groups.decline(id) } })
                    items(contacts, key = { it.pubkey }) { c -> ContactRow(c) { onOpenChat(c.pubkey) } }
                }
            }
        }
    }
}

/**
 * The + button and what it opens: a panel just above it (bar background, 2dp accent border, 6dp shadow,
 * like the profile switcher) with Add someone and New group. Tapping outside or on the X closes it.
 */
@Composable
private fun AddMenu(onAddSomeone: () -> Unit, onNewGroup: () -> Unit) {
    val p = Slab.colors
    var open by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    Box(
        Modifier.padding(end = 4.dp, bottom = 4.dp).size(58.dp).hardShadow(p.shadow, 5.dp).background(p.accent)
            .clickable(role = Role.Button) { open = !open },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (open) Icons.Default.Close else Icons.Default.Add, if (open) "Close" else "Add", Modifier.size(28.dp), tint = p.onAccent)
        if (open) {
            // Placed against the button box (which has 4dp around the button for its shadow); the panel keeps 6dp
            // at its end and bottom for its own shadow, and sits 12dp above the button.
            val offset = with(density) { IntOffset(2.dp.roundToPx(), (-68).dp.roundToPx()) }
            Popup(
                alignment = Alignment.BottomEnd, offset = offset, onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.padding(end = 6.dp, bottom = 6.dp).width(240.dp).hardShadow(p.shadow, 6.dp)
                        .background(p.bar).slabBorder(p.accent),
                ) {
                    AddMenuItem(SlabIcons.Person, "Add someone", "Show your code, or find them") { open = false; onAddSomeone() }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(p.line))
                    AddMenuItem(SlabIcons.Group, "New group", "Pick people from your contacts") { open = false; onNewGroup() }
                }
            }
        }
    }
}

@Composable
private fun AddMenuItem(icon: ImageVector, label: String, sub: String, onClick: () -> Unit) {
    val p = Slab.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = p.accent)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = p.text)
            Text(sub, fontSize = 12.sp, color = p.muted)
        }
    }
}

/** Chats / Requests / Settings along the bottom: the current one filled accent, the others outlined. */
@Composable
fun SlabNav(selected: Int, requests: Int, onChats: () -> Unit, onRequests: () -> Unit, onSettings: () -> Unit) {
    SlabBottomBar {
        NavButton("Chats", selected == 0, onChats, Modifier.weight(1f))
        NavButton(if (requests > 0) "Requests $requests" else "Requests", selected == 1, onRequests, Modifier.weight(1f))
        NavButton("Settings", selected == 2, onSettings, Modifier.weight(1f))
    }
}

@Composable
private fun NavButton(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val p = Slab.colors
    val m = if (on) modifier.hardShadow(p.shadow, 3.dp).background(p.accent) else modifier.slabBorder(p.line)
    Box(m.height(48.dp).clickable(role = Role.Tab, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            label, color = if (on) p.onAccent else p.text, fontSize = 14.sp, maxLines = 1,
            fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RequestsBanner(count: Int, onClick: () -> Unit) {
    val p = Slab.colors
    Row(
        Modifier.padding(start = 14.dp, end = 18.dp, top = 14.dp, bottom = 10.dp).fillMaxWidth()
            .hardShadow(p.alertShadow).background(p.alertGround).slabBorder(p.alert)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MonoTag(count.toString(), p.alert, p.onAlert, fontSize = 13.sp)
        Text(
            if (count == 1) "Requests: 1 new person" else "Requests: $count new people",
            Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 14.sp, color = p.text,
        )
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp), tint = p.alert)
    }
}

@Composable
private fun ContactRow(c: Contact, onClick: () -> Unit) {
    val p = Slab.colors
    val conn = connectionOf(c)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(c)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            c.displayName, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold, fontSize = 16.sp, color = p.text,
                        )
                        Text(conn.label, fontFamily = Slab.mono, fontSize = 10.sp, letterSpacing = 0.08.em, color = conn.color, maxLines = 1)
                    }
                    c.lastMessage?.let {
                        Text(
                            relativeTime(it.ts), fontFamily = Slab.mono, fontSize = 11.sp,
                            color = if (c.unread > 0) p.accent else p.muted,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val last = c.lastMessage
                    Text(
                        last?.let { (if (it.outgoing) "You: " else "") + it.text } ?: "",
                        Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = p.subtle,
                    )
                    if (c.unread > 0) MonoTag(c.unread.toString(), p.accent, p.onAccent, shadow = p.shadow)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(p.card))
    }
}

@Composable
private fun BatteryCard() {
    val ctx = LocalContext.current
    val p = Slab.colors
    val prefs = remember { ctx.getSharedPreferences("lichen_ui", Context.MODE_PRIVATE) }
    val pm = remember { ctx.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var dismissed by remember { mutableStateOf(prefs.getBoolean("battery_dismissed", false)) }
    if (dismissed || pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
    SlabCard(Modifier.fillMaxWidth().padding(start = 14.dp, end = 18.dp, top = 14.dp, bottom = 4.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("BACKGROUND")
            Text("Keep messages arriving promptly", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = p.text)
            Text(
                "Android can put Lichen to sleep to save battery, which delays messages. Allowing Lichen to run in the background keeps your connection to the hub alive.",
                fontSize = 14.sp, color = p.subtle, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SlabButton("Not now", {
                    prefs.edit().putBoolean("battery_dismissed", true).apply(); dismissed = true
                }, Modifier.weight(1f), SlabButtonStyle.Secondary)
                SlabButton("Allow", {
                    prefs.edit().putBoolean("battery_dismissed", true).apply(); dismissed = true
                    runCatching {
                        ctx.startActivity(
                            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                        )
                    }
                }, Modifier.weight(1f))
            }
        }
    }
}

/** A key someone pasted, cleaned up, if it is one: 32 bytes of url-safe base64. */
private fun pastedKey(text: String): String? {
    val k = text.trim().removePrefix("lichen:").filterNot { it.isWhitespace() }.trimEnd('=')
    return k.takeIf { runCatching { unb64(it).size == 32 }.getOrDefault(false) }
}

/** Your key as a QR code: dark modules on a light square, drawn module by module. */
@Composable
private fun KeyQr(key: String, modifier: Modifier = Modifier) {
    val p = Slab.colors
    val matrix = remember(key) {
        runCatching {
            QRCodeWriter().encode(
                key, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
        }.getOrNull()
    }
    Box(modifier.size(228.dp).hardShadow(p.accent, 6.dp).background(p.text).slabBorder(p.text).padding(14.dp)) {
        if (matrix != null) Canvas(Modifier.fillMaxSize().semantics { contentDescription = "QR code of your key" }) {
            val cell = size.width / matrix.width
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) drawRect(p.bar, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/**
 * "Add someone" (the canvas's SlabAdd): your key as a QR code with your name and fingerprint, Copy key and
 * Share key, then the ways to add them: find them by name in your hubs' phonebook, or paste their key.
 */
@Composable
fun PeopleScreen(client: ChatClient, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val p = Slab.colors
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val people by client.people.collectAsState()
    val me by client.profile.collectAsState()
    var mode by rememberSaveable { mutableStateOf<String?>(null) }  // null, "find" or "key"
    var query by rememberSaveable { mutableStateOf("") }
    var keyText by rememberSaveable { mutableStateOf("") }
    val shown = remember(people, query) {
        people.filter { query.isBlank() || it.displayName.contains(query, true) || it.name.contains(query, true) }
    }
    Scaffold(containerColor = p.ground, topBar = { SlabTopBar("Add someone", onBack = onBack, close = true) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            me?.let { m ->
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("Have them scan this. That's it.", fontSize = 15.sp, color = p.subtle, textAlign = TextAlign.Center)
                        KeyQr(m.pubkey)
                        Column(
                            Modifier.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(m.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = p.text)
                            Text(m.fingerprint, fontFamily = Slab.mono, fontSize = 11.sp, letterSpacing = 0.06.em, color = p.muted)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SlabButton("Copy key", { clipboard.setText(AnnotatedString(m.pubkey)) }, Modifier.weight(1f), SlabButtonStyle.Secondary)
                            SlabButton("Share key", {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, m.pubkey)
                                runCatching { ctx.startActivity(Intent.createChooser(send, "Share your Lichen key")) }
                            }, Modifier.weight(1f), SlabButtonStyle.Secondary)
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(start = 16.dp, end = 20.dp, top = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionLabel("OR ADD THEM")
                    AddWay(Icons.Default.Search, "Find by name on your hub", mode == "find") { mode = if (mode == "find") null else "find" }
                    AddWay(SlabIcons.Paste, "Paste a key", mode == "key") {
                        if (mode == "key") mode = null else {
                            mode = "key"
                            if (keyText.isEmpty()) clipboard.getText()?.text?.let { pastedKey(it) }?.let { keyText = it }
                        }
                    }
                }
            }
            if (mode == "key") {
                item {
                    val key = pastedKey(keyText)
                    val mine = key != null && key == me?.pubkey
                    Column(Modifier.padding(start = 16.dp, end = 20.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            keyText, { keyText = it }, Modifier.fillMaxWidth(), placeholder = { Text("Their key") },
                            colors = slabFieldColors(), textStyle = LocalTextStyle.current.copy(fontFamily = Slab.mono, fontSize = 14.sp),
                            isError = keyText.isNotBlank() && (key == null || mine),
                            supportingText = {
                                val (label, color) = when {
                                    keyText.isBlank() -> "THE KEY THEY COPIED OR SHARED" to p.muted
                                    key == null -> "NOT A LICHEN KEY" to MaterialTheme.colorScheme.error
                                    mine -> "THAT'S YOUR OWN KEY" to MaterialTheme.colorScheme.error
                                    else -> "KEY ${fingerprint(key)}" to p.accent
                                }
                                Text(label, fontFamily = Slab.mono, fontSize = 10.sp, letterSpacing = 0.1.em, color = color)
                            },
                        )
                        SlabButton("Add and say hello", {
                            val k = key ?: return@SlabButton
                            scope.launch { client.setTrust(k, Trust.TRUSTED); onOpenChat(k) }
                        }, Modifier.fillMaxWidth(), enabled = key != null && !mine, height = 52.dp, shadow = 4.dp)
                    }
                }
            }
            if (mode == "find") {
                item {
                    OutlinedTextField(
                        query, { query = it }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                        placeholder = { Text("Search by name") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = p.muted) }, colors = slabFieldColors(),
                    )
                }
                if (shown.isEmpty()) {
                    item { Text("No one found.", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = p.muted) }
                }
                items(shown, key = { it.pubkey }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenChat(c.pubkey) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(c, 40.dp, showOnline = false)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(c.displayName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when (c.trust) {
                                    Trust.BLOCKED -> "BLOCKED"
                                    Trust.UNKNOWN -> "NOT IN YOUR CONTACTS"
                                    Trust.TRUSTED -> fingerprint(c.pubkey)
                                },
                                fontFamily = Slab.mono, fontSize = 10.sp, letterSpacing = 0.06.em, color = p.muted,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = p.muted)
                    }
                }
            }
        }
    }
}

/** One of the "OR ADD THEM" actions: a 56dp card button; the open one gets an accent border. */
@Composable
private fun AddWay(icon: ImageVector, label: String, open: Boolean, onClick: () -> Unit) {
    val p = Slab.colors
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(p.card).slabBorder(if (open) p.accent else p.edge)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = p.text)
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = p.text)
    }
}
