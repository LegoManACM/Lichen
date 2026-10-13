package dev.lichen.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lichen.app.ui.theme.*
import dev.lichen.core.api.ChatClient
import dev.lichen.core.api.Trust
import kotlinx.coroutines.launch

/** Strangers who wrote to us: tap one to read it, or accept / block right here. */
@Composable
fun RequestsScreen(client: ChatClient, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val p = Slab.colors
    val requests by client.requests.collectAsState()
    val scope = rememberCoroutineScope()
    Scaffold(containerColor = p.ground, topBar = {
        SlabTopBar("Requests", onBack = onBack, rule = p.alert, actions = {
            if (requests.isNotEmpty()) MonoTag(requests.size.toString(), p.alert, p.onAlert, fontSize = 13.sp)
        })
    }) { pad ->
        if (requests.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("NO REQUESTS")
                    Text(
                        "When someone who isn't in your contacts writes to you, it lands here first.",
                        color = p.subtle, fontSize = 15.sp, textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.padding(pad), contentPadding = PaddingValues(start = 14.dp, end = 18.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(requests, key = { it.pubkey }) { c ->
                    SlabCard(Modifier.fillMaxWidth().clickable { onOpenChat(c.pubkey) }) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            MonoTag("NEW PERSON", p.alert, p.onAlert, fontSize = 10.sp)
                            Spacer(Modifier.weight(1f))
                            c.lastMessage?.let { Text(relativeTime(it.ts), fontFamily = Slab.mono, fontSize = 11.sp, color = p.muted) }
                        }
                        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Someone new: \"${c.displayName}\"", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = p.text)
                            c.lastMessage?.let {
                                Text(it.text, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 20.sp, color = p.subtle)
                            }
                            Text("key ${fingerprint(c.pubkey)} · not in your contacts", fontFamily = Slab.mono, fontSize = 10.sp, color = p.dim)
                        }
                        Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SlabButton("Block", { scope.launch { client.setTrust(c.pubkey, Trust.BLOCKED) } }, Modifier.weight(1f), SlabButtonStyle.Secondary)
                            SlabButton("Accept", { scope.launch { client.setTrust(c.pubkey, Trust.TRUSTED) } }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** The node's recent log lines (last 200). */
@Composable
fun DebugLogScreen(client: ChatClient, onBack: () -> Unit) {
    val p = Slab.colors
    val lines by client.log.collectAsState()
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }
    Scaffold(containerColor = p.bar, topBar = { SlabTopBar("Debug log", onBack = onBack) }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 10.dp), state = state, contentPadding = PaddingValues(vertical = 8.dp)) {
            items(lines.size) { i -> Text(lines[i], fontFamily = Slab.mono, fontSize = 11.sp, lineHeight = 16.sp, color = p.subtle) }
        }
    }
}
