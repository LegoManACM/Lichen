package dev.lichen.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lichen.app.ProfileInfo
import dev.lichen.core.api.ChatClient
import dev.lichen.core.api.Trust
import dev.lichen.core.api.Contact

@Composable
private fun ProfileAvatar(id: String, name: String, size: Dp = 32.dp) =
    Avatar(Contact(pubkey = id, name = name, trust = Trust.TRUSTED), size, showOnline = false)

/** The main screen's top bar title: who you are right now. Tap to switch. */
@Composable
fun ProfileTitle(name: String, id: String, onClick: () -> Unit) {
    Row(
        Modifier.clickable(onClick = onClick).padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(id, name, 32.dp)
        Spacer(Modifier.width(10.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Default.ArrowDropDown, "Switch profile")
    }
}

@Composable
private fun unreadOf(client: ChatClient?): Int {
    val c = client ?: return 0
    val contacts by c.contacts.collectAsState()
    return contacts.sumOf { it.unread }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSwitcherSheet(
    profiles: List<ProfileInfo>, clients: Map<String, ChatClient>, activeId: String?,
    onSwitch: (String) -> Unit, onAdd: () -> Unit, onManage: () -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            profiles.forEach { p ->
                val unread = unreadOf(clients[p.id])
                ListItem(
                    modifier = Modifier.clickable { onSwitch(p.id) },
                    leadingContent = { ProfileAvatar(p.id, p.name, 40.dp) },
                    headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(if (unread > 0) "$unread unread" else "No unread messages") },
                    trailingContent = {
                        if (p.id == activeId) Icon(Icons.Default.Check, "Current profile", tint = MaterialTheme.colorScheme.primary)
                        else if (unread > 0) Badge { Text(unread.toString()) }
                    },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ListItem(
                modifier = Modifier.clickable(onClick = onAdd),
                leadingContent = { Icon(Icons.Default.Add, null) },
                headlineContent = { Text("Add profile") },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onManage),
                leadingContent = { Icon(Icons.Default.Settings, null) },
                headlineContent = { Text("Manage profiles") },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageProfilesScreen(
    profiles: List<ProfileInfo>, clients: Map<String, ChatClient>, activeId: String?,
    onBack: () -> Unit, onSwitch: (String) -> Unit, onAdd: () -> Unit, onDelete: (ProfileInfo) -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Profiles") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(profiles, key = { it.id }) { p ->
                val profile = clients[p.id]?.profile?.collectAsState()?.value
                ListItem(
                    modifier = Modifier.clickable { onSwitch(p.id) },
                    leadingContent = { ProfileAvatar(p.id, p.name, 40.dp) },
                    headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            if (p.id == activeId) Text("Current profile", color = MaterialTheme.colorScheme.primary)
                            profile?.let { Text(it.fingerprint, fontFamily = FontFamily.Monospace) }
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = { onDelete(p) }) {
                            Icon(Icons.Default.Delete, "Delete ${p.name}", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(onClick = onAdd),
                    leadingContent = { Icon(Icons.Default.Add, null) },
                    headlineContent = { Text("Add profile") },
                )
            }
        }
    }
}

@Composable
fun DeleteProfileDialog(profile: ProfileInfo, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"${profile.name}\"?") },
        text = {
            Text(
                if (profile.linked) "Your other devices keep this identity; this only removes it from this phone."
                else "This identity exists only on this phone. If you delete it, the identity, its contacts and all its " +
                    "chats are gone for good, and nobody can recover them.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Delete")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
