package dev.lichen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lichen.app.ProfileInfo
import dev.lichen.app.ui.theme.*
import dev.lichen.core.api.ChatClient

/** The home top bar's profile button: who you are right now (initial + name). Tap to switch. */
@Composable
fun ProfileTitle(name: String, id: String, onClick: () -> Unit) {
    val p = Slab.colors
    Row(
        Modifier.height(44.dp).slabBorder(p.line).clickable(role = Role.Button, onClickLabel = "Switch profile", onClick = onClick)
            .padding(start = 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SquareAvatar(initialOf(name), p.accent, 32.dp, color = p.onAccent, shadow = p.shadow)
        Text(
            name, Modifier.widthIn(max = 96.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = p.text,
        )
        Icon(Icons.Default.KeyboardArrowDown, "Switch profile", Modifier.size(18.dp), tint = p.text)
    }
}

@Composable
private fun unreadOf(client: ChatClient?): Int {
    val c = client ?: return 0
    val contacts by c.contacts.collectAsState()
    return contacts.sumOf { it.unread }
}

@Composable
private fun fingerprintOf(client: ChatClient?): String? = client?.profile?.collectAsState()?.value?.fingerprint

/**
 * The profile switcher: a panel that drops down under the top bar over a dark scrim. The active
 * profile is filled accent; others show their unread count.
 */
@Composable
fun ProfileSwitcherSheet(
    profiles: List<ProfileInfo>, clients: Map<String, ChatClient>, activeId: String?,
    onSwitch: (String) -> Unit, onAdd: () -> Unit, onManage: () -> Unit, onDismiss: () -> Unit,
) {
    val p = Slab.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().background(Color(0xB8050605))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        ) {
            Column(
                Modifier.statusBarsPadding().padding(start = 14.dp, end = 20.dp, top = 80.dp, bottom = 24.dp).fillMaxWidth()
                    .hardShadow(p.shadow, 6.dp).background(p.bar).slabBorder(p.accent)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "PROFILES ON THIS PHONE", Modifier.weight(1f), fontFamily = Slab.mono, fontSize = 11.sp,
                        letterSpacing = 0.14.em, color = p.muted,
                    )
                    SlabIconButton(Icons.Default.Close, "Close", onDismiss, bordered = false)
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(p.line))
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    profiles.forEach { pr -> ProfileTile(pr, pr.id == activeId, unreadOf(clients[pr.id]), fingerprintOf(clients[pr.id])) { onSwitch(pr.id) } }
                    SlabButton("New profile", onAdd, Modifier.fillMaxWidth().padding(end = 4.dp), SlabButtonStyle.Dashed, icon = Icons.Default.Add, height = 52.dp)
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(p.line))
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onManage).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Manage profiles & keys", Modifier.weight(1f), fontSize = 14.sp, color = p.subtle)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = p.subtle)
                }
            }
        }
    }
}

@Composable
private fun ProfileTile(pr: ProfileInfo, active: Boolean, unread: Int, key: String?, onClick: () -> Unit) {
    val p = Slab.colors
    val fg = if (active) p.onAccent else p.text
    var m = Modifier.fillMaxWidth().padding(end = 4.dp)
    m = if (active) m.hardShadow(p.shadow).background(p.accent).slabBorder(p.accent) else m.background(p.card).slabBorder(p.edge)
    Row(
        m.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SquareAvatar(
            initialOf(pr.name), if (active) p.text else avatarTint(pr.id), 44.dp,
            color = if (active) p.ground else Color(0xFF0B0D0A), border = if (active) p.onAccent else p.card,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(pr.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            key?.let { Text(it, fontFamily = Slab.mono, fontSize = 10.sp, color = if (active) fg.copy(alpha = 0.7f) else p.muted, maxLines = 1) }
        }
        if (active) Icon(Icons.Default.Check, "Current profile", Modifier.size(22.dp), tint = fg)
        else if (unread > 0) MonoTag("$unread new", p.alert, p.onAlert)
    }
}

@Composable
fun ManageProfilesScreen(
    profiles: List<ProfileInfo>, clients: Map<String, ChatClient>, activeId: String?,
    onBack: () -> Unit, onSwitch: (String) -> Unit, onAdd: () -> Unit, onDelete: (ProfileInfo) -> Unit,
) {
    val p = Slab.colors
    Scaffold(containerColor = p.ground, topBar = { SlabTopBar("Profiles", onBack = onBack) }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 18.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionLabel("PROFILES ON THIS PHONE") }
            items(profiles, key = { it.id }) { pr ->
                val active = pr.id == activeId
                val fp = fingerprintOf(clients[pr.id])
                SlabCard(border = if (active) p.accent else p.edge) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onSwitch(pr.id) }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SquareAvatar(initialOf(pr.name), if (active) p.accent else avatarTint(pr.id), 44.dp, color = if (active) p.onAccent else Color(0xFF0B0D0A))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(pr.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (active) Text("IN USE", fontFamily = Slab.mono, fontSize = 10.sp, letterSpacing = 0.1.em, color = p.accent)
                            fp?.let { Text(it, fontFamily = Slab.mono, fontSize = 10.sp, color = p.muted) }
                        }
                        SlabIconButton(Icons.Default.Delete, "Delete ${pr.name}", { onDelete(pr) }, bordered = false)
                    }
                }
            }
            item { SlabButton("New profile", onAdd, Modifier.fillMaxWidth(), SlabButtonStyle.Dashed, icon = Icons.Default.Add, height = 52.dp) }
        }
    }
}

/** A Slab confirmation: a card over a scrim, destructive action outlined in the error color. */
@Composable
fun SlabDialog(
    title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    dismiss: String = "Cancel", destructive: Boolean = false,
) {
    val p = Slab.colors
    Dialog(onDismissRequest = onDismiss) {
        SlabCard(Modifier.fillMaxWidth().padding(end = 6.dp), border = if (destructive) MaterialTheme.colorScheme.error else p.edge, shadow = p.bar, shadowOffset = 6.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = p.text)
                Text(text, fontSize = 14.sp, color = p.subtle, lineHeight = 20.sp)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SlabButton(dismiss, onDismiss, Modifier.weight(1f), SlabButtonStyle.Secondary)
                    SlabButton(confirm, onConfirm, Modifier.weight(1f), if (destructive) SlabButtonStyle.Danger else SlabButtonStyle.Primary)
                }
            }
        }
    }
}

@Composable
fun DeleteProfileDialog(profile: ProfileInfo, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    SlabDialog(
        title = "Delete \"${profile.name}\"?",
        text = if (profile.linked) "Your other devices keep this identity; this only removes it from this phone."
        else "This identity exists only on this phone. If you delete it, the identity, its contacts and all its " +
            "chats are gone for good, and nobody can recover them.",
        confirm = "Delete", onConfirm = onConfirm, onDismiss = onDismiss, destructive = true,
    )
}
