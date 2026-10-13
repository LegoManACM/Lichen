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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
) {
    val p = Slab.colors
    val contacts by client.contacts.collectAsState()
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
        floatingActionButton = {
            Box(
                Modifier.padding(end = 4.dp, bottom = 4.dp).size(58.dp).hardShadow(p.shadow, 5.dp).background(p.accent)
                    .clickable(role = Role.Button, onClick = onNewChat),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Add, "Add someone", Modifier.size(28.dp), tint = p.onAccent) }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            BatteryCard()
            if (requests.isNotEmpty()) RequestsBanner(requests.size, onRequests)
            if (contacts.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("NO CHATS YET")
                        Text("Tap + to say hello to someone.", color = p.subtle, fontSize = 15.sp, textAlign = TextAlign.Center)
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)) {
                    items(contacts, key = { it.pubkey }) { c -> ContactRow(c) { onOpenChat(c.pubkey) } }
                }
            }
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

/**
 * "Add someone": your own key to hand out (copy / share), then everyone in your hubs' phonebook to
 * search and start a chat with.
 */
@Composable
fun PeopleScreen(client: ChatClient, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val p = Slab.colors
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val people by client.people.collectAsState()
    val me by client.profile.collectAsState()
    var query by remember { mutableStateOf("") }
    val shown = remember(people, query) {
        people.filter { query.isBlank() || it.displayName.contains(query, true) || it.name.contains(query, true) }
    }
    Scaffold(containerColor = p.ground, topBar = { SlabTopBar("Add someone", onBack = onBack, close = true) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            me?.let { m ->
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Give them your key. That's it.", fontSize = 15.sp, color = p.subtle)
                        SlabCard(Modifier.fillMaxWidth().padding(end = 6.dp), shadow = p.accent, shadowOffset = 6.dp, border = p.text, background = p.text) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(m.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = p.ground)
                                Text(m.fingerprint, fontFamily = Slab.mono, fontSize = 12.sp, letterSpacing = 0.06.em, color = p.ground)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SlabButton("Copy key", { clipboard.setText(AnnotatedString(m.pubkey)) }, Modifier.weight(1f), SlabButtonStyle.Secondary)
                            SlabButton("Share key", {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, m.pubkey)
                                runCatching { ctx.startActivity(Intent.createChooser(send, "Share your Lichen key")) }
                            }, Modifier.weight(1f), SlabButtonStyle.Secondary, icon = Icons.Default.Share)
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("OR FIND THEM ON YOUR HUB")
                    OutlinedTextField(
                        query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search by name") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = p.muted) }, colors = slabFieldColors(),
                    )
                }
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
