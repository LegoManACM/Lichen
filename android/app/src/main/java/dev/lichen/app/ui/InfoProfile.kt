package dev.lichen.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.lichen.core.api.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(client: ChatClient, pubkey: String, onBack: () -> Unit) {
    val people by client.people.collectAsState()
    val c = people.firstOrNull { it.pubkey == pubkey }
    val scope = rememberCoroutineScope()
    var nick by rememberSaveable(pubkey) { mutableStateOf(c?.nickname.orEmpty()) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Contact") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        if (c == null) return@Scaffold
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(c, 88.dp)
            Text(c.displayName, style = MaterialTheme.typography.headlineSmall)
            if (c.nickname != null) Text("Calls themselves ${c.name}", style = MaterialTheme.typography.bodyMedium)
            Text(if (c.online) "Online" else "Offline", color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                nick, { nick = it }, Modifier.fillMaxWidth(), label = { Text("Nickname (only you see this)") }, singleLine = true,
                trailingIcon = if (nick.isNotEmpty()) {
                    { IconButton({ nick = "" }) { Icon(Icons.Default.Close, "Clear") } }
                } else null,
            )
            Button(
                onClick = { scope.launch { client.setNickname(pubkey, nick.trim().ifEmpty { null }) } },
                enabled = nick.trim() != (c.nickname ?: ""),
                modifier = Modifier.align(Alignment.End),
            ) { Text("Save nickname") }

            Column(Modifier.fillMaxWidth()) {
                Text("Fingerprint", style = MaterialTheme.typography.labelLarge)
                Text(fingerprint(c.pubkey), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Compare this out loud with them to be sure you've got the right person.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Trust: " + when (c.trust) {
                    Trust.TRUSTED -> "in your contacts"
                    Trust.UNKNOWN -> "not in your contacts yet"
                    Trust.BLOCKED -> "blocked"
                }, style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (c.trust != Trust.TRUSTED) Button({ scope.launch { client.setTrust(pubkey, Trust.TRUSTED) } }) { Text("Trust") }
                    if (c.trust != Trust.BLOCKED) {
                        OutlinedButton(
                            { scope.launch { client.setTrust(pubkey, Trust.BLOCKED) } },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Block") }
                    } else OutlinedButton({ scope.launch { client.setTrust(pubkey, Trust.UNKNOWN) } }) { Text("Unblock") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(client: ChatClient, onBack: () -> Unit, onSaveHubs: (name: String, hubs: List<String>) -> Unit) {
    val profile by client.profile.collectAsState()
    val hubStatus by client.hubs.collectAsState()
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    val p = profile
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("You") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        if (p == null) return@Scaffold
        var name by rememberSaveable(p.name) { mutableStateOf(p.name) }
        val hubs = remember(p.hubs) { mutableStateListOf(*p.hubs.toTypedArray()) }
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Your name") }, singleLine = true)
            Column {
                Text("Fingerprint", style = MaterialTheme.typography.labelLarge)
                Text(p.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
            }
            Column {
                Text("This device", style = MaterialTheme.typography.labelLarge)
                Text(p.device, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
            Text("Hubs", style = MaterialTheme.typography.titleMedium)
            hubs.forEachIndexed { i, h ->
                val connected = hubStatus.firstOrNull { it.address == h }?.connected == true
                OutlinedTextField(
                    h, { hubs[i] = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("wss://…") },
                    supportingText = { Text(if (hubStatus.any { it.address == h }) (if (connected) "Connected" else "Not connected") else "Not saved yet") },
                    trailingIcon = if (hubs.size > 1) {
                        { IconButton({ hubs.removeAt(i) }) { Icon(Icons.Default.Close, "Remove hub") } }
                    } else null,
                )
            }
            TextButton(onClick = { hubs.add("") }) { Text("Add a hub") }
            val hs = hubs.map { it.trim() }.filter { it.isNotEmpty() }
            val changed = name.trim() != p.name || hs != p.hubs
            Button(onClick = { onSaveHubs(name.trim(), hs) }, enabled = changed && name.isNotBlank() && hs.isNotEmpty()) {
                Text("Save and reconnect")
            }
            HorizontalDivider()
            OutlinedButton(
                onClick = { syncing = true; scope.launch { client.syncNow(); syncing = false } }, enabled = !syncing,
            ) { Text(if (syncing) "Syncing…" else "Sync now") }
            Text(
                "Sync now retries anything waiting to be sent and catches up on chats from your other devices.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
