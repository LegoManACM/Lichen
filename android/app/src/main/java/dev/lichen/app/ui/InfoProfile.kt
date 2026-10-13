package dev.lichen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.lichen.app.ui.theme.*
import dev.lichen.core.api.*
import kotlinx.coroutines.launch

/** A labelled block of mono text: fingerprint, safety code, device id. */
@Composable
private fun KeyBlock(label: String, value: String, note: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val p = Slab.colors
    SlabCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel(label)
            Text(value, fontFamily = Slab.mono, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.06.em, color = p.text)
            note?.let { Text(it, fontSize = 13.sp, color = p.muted, lineHeight = 18.sp) }
            trailing?.invoke()
        }
    }
}

@Composable
fun InfoScreen(client: ChatClient, pubkey: String, onBack: () -> Unit) {
    val p = Slab.colors
    val people by client.people.collectAsState()
    val c = people.firstOrNull { it.pubkey == pubkey }
    val scope = rememberCoroutineScope()
    var nick by rememberSaveable(pubkey) { mutableStateOf(c?.nickname.orEmpty()) }
    Scaffold(containerColor = p.ground, topBar = { SlabTopBar("Contact", onBack = onBack) }) { pad ->
        if (c == null) return@Scaffold
        val conn = connectionOf(c)
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 18.dp, top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Avatar(c, 72.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.displayName, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = p.text)
                    if (c.nickname != null) Text("Calls themselves ${c.name}", fontSize = 14.sp, color = p.subtle)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(conn.label, fontFamily = Slab.mono, fontSize = 10.sp, letterSpacing = 0.1.em, color = conn.color)
                        if (c.verified) Text("VERIFIED", fontFamily = Slab.mono, fontSize = 10.sp, letterSpacing = 0.1.em, color = p.accent)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("NICKNAME · ONLY YOU SEE THIS")
                OutlinedTextField(
                    nick, { nick = it }, Modifier.fillMaxWidth(), singleLine = true, colors = slabFieldColors(),
                    placeholder = { Text(c.name) },
                    trailingIcon = if (nick.isNotEmpty()) {
                        { IconButton({ nick = "" }) { Icon(Icons.Default.Close, "Clear", tint = p.muted) } }
                    } else null,
                )
                SlabButton(
                    "Save nickname", { scope.launch { client.setNickname(pubkey, nick.trim().ifEmpty { null }) } },
                    Modifier.align(Alignment.End), enabled = nick.trim() != (c.nickname ?: ""),
                )
            }

            KeyBlock("FINGERPRINT", fingerprint(c.pubkey), "Compare this out loud with them to be sure you've got the right person.")
            KeyBlock("SAFETY CODE", client.safetyCode(pubkey), "You and ${c.displayName} should see the same digits.") {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Verified", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = p.text)
                    SlabSwitch(c.verified, { v -> scope.launch { client.setVerified(pubkey, v) } })
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("TRUST")
                Text(
                    when (c.trust) {
                        Trust.TRUSTED -> "In your contacts"
                        Trust.UNKNOWN -> "Not in your contacts yet"
                        Trust.BLOCKED -> "Blocked"
                    },
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = p.text,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (c.trust != Trust.TRUSTED) {
                        SlabButton("Trust", { scope.launch { client.setTrust(pubkey, Trust.TRUSTED) } }, Modifier.weight(1f))
                    }
                    if (c.trust != Trust.BLOCKED) {
                        SlabButton("Block", { scope.launch { client.setTrust(pubkey, Trust.BLOCKED) } }, Modifier.weight(1f), SlabButtonStyle.Danger)
                    } else {
                        SlabButton("Unblock", { scope.launch { client.setTrust(pubkey, Trust.UNKNOWN) } }, Modifier.weight(1f), SlabButtonStyle.Secondary)
                    }
                }
            }
        }
    }
}

/**
 * What happens when someone who isn't in your contacts writes: three tiles like the nav (the chosen one
 * filled accent with a shadow, the others outlined) and a line saying what the choice does.
 */
@Composable
private fun StrangersSection(current: Strangers, onChange: (Strangers) -> Unit) {
    val p = Slab.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("WHEN SOMEONE NEW WRITES")
        Row(Modifier.fillMaxWidth().selectableGroup().padding(end = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((s, label) in listOf(Strangers.REQUESTS to "Requests", Strangers.CHATS to "Chats", Strangers.IGNORE to "Ignore")) {
                val on = s == current
                val m = if (on) Modifier.hardShadow(p.shadow, 3.dp).background(p.accent) else Modifier.slabBorder(p.line)
                Box(
                    Modifier.weight(1f).then(m).height(48.dp).selectable(on, role = Role.RadioButton) { onChange(s) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, color = if (on) p.onAccent else p.text, fontSize = 14.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                    )
                }
            }
        }
        Text(
            when (current) {
                Strangers.REQUESTS -> "People who aren't in your contacts wait in Requests until you accept them."
                Strangers.CHATS -> "People who aren't in your contacts go straight into your chats. Their files and invites still ask first."
                Strangers.IGNORE -> "Messages, files and invites from people who aren't in your contacts are dropped. They aren't told."
            },
            fontSize = 13.sp, color = p.muted, lineHeight = 18.sp,
        )
    }
}

/**
 * Settings for the active profile: who you are, colors, hubs, sync, and the advanced / destructive bits.
 */
@Composable
fun ProfileScreen(
    client: ChatClient, onBack: (() -> Unit)?, onSaveHubs: (name: String, hubs: List<String>) -> Unit,
    onDelete: () -> Unit, onDebugLog: () -> Unit = {}, onColors: () -> Unit = {}, onSwitchProfile: (() -> Unit)? = null,
) {
    val p = Slab.colors
    val profile by client.profile.collectAsState()
    val hubStatus by client.hubs.collectAsState()
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    val pr = profile
    Scaffold(containerColor = p.ground, topBar = { SlabTopBar("Settings", onBack = onBack) }) { pad ->
        if (pr == null) return@Scaffold
        var name by rememberSaveable(pr.name) { mutableStateOf(pr.name) }
        val hubs = remember(pr.hubs) { mutableStateListOf(*pr.hubs.toTypedArray()) }
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 18.dp, top = 16.dp, bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SlabCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SquareAvatar(initialOf(pr.name), p.accent, 54.dp, color = p.onAccent, shadow = p.shadow)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(pr.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = p.text)
                        Text(pr.fingerprint, fontFamily = Slab.mono, fontSize = 10.sp, color = p.muted)
                    }
                    if (onSwitchProfile != null) SlabButton("Switch", onSwitchProfile, style = SlabButtonStyle.Secondary, height = 40.dp)
                }
            }

            ColorsSection(onAll = onColors)

            StrangersSection(pr.strangers) { s -> scope.launch { client.setStrangers(s) } }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("YOUR NAME")
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, colors = slabFieldColors())
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("HUBS")
                hubs.forEachIndexed { i, h ->
                    val known = hubStatus.firstOrNull { it.address == h }
                    OutlinedTextField(
                        h, { hubs[i] = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("wss://…") },
                        colors = slabFieldColors(), textStyle = LocalTextStyle.current.copy(fontFamily = Slab.mono, fontSize = 14.sp),
                        supportingText = {
                            val (label, color) = when {
                                known == null -> "NOT SAVED YET" to p.muted
                                known.connected -> "■ CONNECTED" to p.accent
                                else -> "■ NOT CONNECTED" to MaterialTheme.colorScheme.error
                            }
                            Text(label, fontFamily = Slab.mono, fontSize = 10.sp, letterSpacing = 0.1.em, color = color)
                        },
                        trailingIcon = if (hubs.size > 1) {
                            { IconButton({ hubs.removeAt(i) }) { Icon(Icons.Default.Close, "Remove hub", tint = p.muted) } }
                        } else null,
                    )
                }
                SlabButton("Add a hub", { hubs.add("") }, Modifier.fillMaxWidth(), SlabButtonStyle.Dashed, icon = Icons.Default.Add)
            }
            val hs = hubs.map { it.trim() }.filter { it.isNotEmpty() }
            val changed = name.trim() != pr.name || hs != pr.hubs
            SlabButton(
                "Save and reconnect", { onSaveHubs(name.trim(), hs) }, Modifier.fillMaxWidth(),
                enabled = changed && name.isNotBlank() && hs.isNotEmpty(), height = 52.dp, shadow = 4.dp,
            )

            SlabCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("THIS DEVICE")
                    Text(pr.device, fontFamily = Slab.mono, fontSize = 13.sp, color = p.text)
                    Text(
                        "Sync retries anything waiting to be sent and catches up on chats from your other devices.",
                        fontSize = 13.sp, color = p.muted, lineHeight = 18.sp,
                    )
                    SlabButton(
                        if (syncing) "Syncing…" else "Sync now",
                        { syncing = true; scope.launch { client.syncNow(); syncing = false } },
                        Modifier.fillMaxWidth(), SlabButtonStyle.Secondary, enabled = !syncing,
                    )
                }
            }

            Box(
                Modifier.fillMaxWidth().height(46.dp).dashedBorder(p.strongLine).clickable(role = Role.Button, onClick = onDebugLog),
                contentAlignment = Alignment.Center,
            ) {
                Text("ADVANCED · DEBUG LOG", fontFamily = Slab.mono, fontSize = 11.sp, letterSpacing = 0.12.em, color = p.muted)
            }

            SlabButton("Delete this profile", onDelete, Modifier.fillMaxWidth(), SlabButtonStyle.Danger)
        }
    }
}
