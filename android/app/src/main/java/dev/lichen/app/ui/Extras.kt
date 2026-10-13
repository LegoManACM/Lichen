package dev.lichen.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.lichen.core.api.ChatClient
import dev.lichen.core.api.Trust
import kotlinx.coroutines.launch

/** Strangers who wrote to us: tap one to read it, or accept / block right here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestsScreen(client: ChatClient, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val requests by client.requests.collectAsState()
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Requests") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        if (requests.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize().padding(32.dp)) { Text("No requests.") }
        } else {
            LazyColumn(Modifier.padding(pad)) {
                items(requests, key = { it.pubkey }) { c ->
                    Column(Modifier.fillMaxWidth().clickable { onOpenChat(c.pubkey) }.padding(16.dp)) {
                        Text(c.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(c.lastMessage?.text.orEmpty(), maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ scope.launch { client.setTrust(c.pubkey, Trust.TRUSTED) } }) { Text("Accept") }
                            TextButton({ scope.launch { client.setTrust(c.pubkey, Trust.BLOCKED) } }) { Text("Block") }
                        }
                    }
                }
            }
        }
    }
}

/** The node's recent log lines (last 200). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(client: ChatClient, onBack: () -> Unit) {
    val lines by client.log.collectAsState()
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Debug log") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 8.dp), state = state) {
            items(lines.size) { i -> Text(lines[i], fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
