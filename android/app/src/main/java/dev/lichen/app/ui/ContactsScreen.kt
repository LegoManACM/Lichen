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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lichen.core.api.*
import kotlinx.coroutines.launch

@Composable
fun HubChip(hubs: List<HubStatus>) {
    val up = hubs.count { it.connected }
    val color = when {
        hubs.isEmpty() || up == 0 -> MaterialTheme.colorScheme.error
        up < hubs.size -> Color(0xFFE0A100)
        else -> Color(0xFF2EB85C)
    }
    AssistChip(
        onClick = {},
        label = { Text("$up/${hubs.size} hubs connected", style = MaterialTheme.typography.labelMedium) },
        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(color)) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    client: ChatClient, profileName: String, profileId: String, onSwitcher: () -> Unit,
    onOpenChat: (String) -> Unit, onNewChat: () -> Unit, onProfile: () -> Unit,
    onOpenGroup: (String) -> Unit = {}, onNewGroup: () -> Unit = {},
) {
    val contacts by client.contacts.collectAsState()
    val groups by client.groups.groups.collectAsState()
    val scope = rememberCoroutineScope()
    val hubs by client.hubs.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { ProfileTitle(profileName, profileId, onSwitcher) },
                actions = {
                    HubChip(hubs)
                    IconButton(onClick = onProfile) { Icon(Icons.Default.Settings, "Profile and settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNewChat, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New chat") })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            BatteryCard()
            TextButton(onClick = onNewGroup, Modifier.padding(horizontal = 8.dp)) { Text("New group") }
            if (contacts.isEmpty() && groups.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No chats yet. Tap New chat to say hello.", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    groupRows(groups, onOpenGroup,
                        onAccept = { id -> scope.launch { client.groups.accept(id) } },
                        onDecline = { id -> scope.launch { client.groups.decline(id) } })
                    items(contacts, key = { it.pubkey }) { c -> ContactRow(c) { onOpenChat(c.pubkey) } }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(c: Contact, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.displayName, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium, fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.Medium,
                )
                if (c.trust == Trust.UNKNOWN) {
                    Spacer(Modifier.width(8.dp))
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                        Text("? Request", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            val last = c.lastMessage
            Text(
                last?.let { (if (it.outgoing) "You: " else "") + it.text } ?: "",
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            c.lastMessage?.let {
                Text(
                    relativeTime(it.ts), style = MaterialTheme.typography.labelSmall,
                    color = if (c.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (c.unread > 0) {
                Spacer(Modifier.height(4.dp))
                Badge { Text(c.unread.toString()) }
            }
        }
    }
}

@Composable
private fun BatteryCard() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("lichen_ui", Context.MODE_PRIVATE) }
    val pm = remember { ctx.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var dismissed by remember { mutableStateOf(prefs.getBoolean("battery_dismissed", false)) }
    if (dismissed || pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
    Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("Keep messages arriving promptly", style = MaterialTheme.typography.titleSmall)
            Text(
                "Android can put Lichen to sleep to save battery, which delays messages. Allowing Lichen to run in the background keeps your connection to the hub alive.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { prefs.edit().putBoolean("battery_dismissed", true).apply(); dismissed = true }) { Text("Not now") }
                TextButton(onClick = {
                    prefs.edit().putBoolean("battery_dismissed", true).apply(); dismissed = true
                    runCatching {
                        ctx.startActivity(
                            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                        )
                    }
                }) { Text("Allow") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(client: ChatClient, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val people by client.people.collectAsState()
    var query by remember { mutableStateOf("") }
    val shown = remember(people, query) {
        people.filter { query.isBlank() || it.displayName.contains(query, true) || it.name.contains(query, true) }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("New chat") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search people") }, singleLine = true,
            )
            if (shown.isEmpty()) {
                Text("No one found.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn {
                items(shown, key = { it.pubkey }) { c ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenChat(c.pubkey) },
                        leadingContent = { Avatar(c, 40.dp) },
                        headlineContent = { Text(c.displayName) },
                        supportingContent = {
                            Text(
                                when (c.trust) {
                                    Trust.BLOCKED -> "Blocked"
                                    Trust.UNKNOWN -> "Not in your contacts"
                                    Trust.TRUSTED -> fingerprint(c.pubkey)
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}
