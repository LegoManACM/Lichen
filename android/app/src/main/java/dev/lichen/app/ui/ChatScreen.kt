package dev.lichen.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import dev.lichen.app.Backend
import dev.lichen.core.api.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(client: ChatClient, profileId: String, pubkey: String, onBack: () -> Unit, onInfo: () -> Unit) {
    val messages by client.messages(pubkey).collectAsState()
    val people by client.people.collectAsState()
    val contact = people.firstOrNull { it.pubkey == pubkey }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)

    // While this chat is on screen the user has seen everything in it.
    LaunchedEffect(pubkey, resumed, messages.size) { if (resumed) client.markRead(pubkey) }
    DisposableEffect(profileId, pubkey, resumed) {
        val me = dev.lichen.app.OpenChat(profileId, pubkey)
        if (resumed) Backend.currentChat.value = me
        onDispose { if (Backend.currentChat.value == me) Backend.currentChat.value = null }
    }
    // Direct-message file offers share the timeline with the messages.
    val transfers by client.files.transfers.collectAsState()
    val me = client.profile.collectAsState().value?.pubkey
    val timeline = remember(messages, transfers) {
        (messages.map<ChatMessage, TimelineItem> { TimelineItem.Msg(it) } +
            transfers.filter { it.group == null && me != null && it.peer(me) == pubkey }.map { TimelineItem.File(it) })
            .sortedBy { it.ts }
    }
    LaunchedEffect(timeline.size) { if (timeline.isNotEmpty()) listState.animateScrollToItem(timeline.size - 1) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) offerFile(context, client, pubkey, uri)
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Row(Modifier.clickable(onClick = onInfo), verticalAlignment = Alignment.CenterVertically) {
                        contact?.let { Avatar(it, 36.dp); Spacer(Modifier.width(10.dp)) }
                        Column {
                            Text(contact?.displayName ?: pubkey.take(8), style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (contact?.online == true) "online" else "offline",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = { Composer({ picker.launch(arrayOf("*/*")) }) { text -> scope.launch { client.send(pubkey, text) } } },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (contact?.trust) {
                Trust.UNKNOWN -> Banner("Not in your contacts", "Accept", "Block", {
                    scope.launch { client.setTrust(pubkey, Trust.TRUSTED) }
                }, { scope.launch { client.setTrust(pubkey, Trust.BLOCKED) } })
                Trust.BLOCKED -> Banner("You blocked ${contact.displayName}", "Unblock", null, {
                    scope.launch { client.setTrust(pubkey, Trust.UNKNOWN) }
                }, null)
                else -> {}
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(timeline, key = { it.key }) {
                    when (it) {
                        is TimelineItem.Msg -> Bubble(it.m)
                        is TimelineItem.File -> FileBubble(client.files, it.t)
                    }
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, primary: String, secondary: String?, onPrimary: () -> Unit, onSecondary: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (secondary != null && onSecondary != null) TextButton(onClick = onSecondary, colors = bannerColors()) { Text(secondary) }
            TextButton(onClick = onPrimary, colors = bannerColors()) { Text(primary) }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val out = m.outgoing
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (out) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (out) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(18.dp, 18.dp, if (out) 4.dp else 18.dp, if (out) 18.dp else 4.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(m.text, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    if (out && m.status == MessageStatus.WAITING) {
                        Text("waiting for them to come online  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(clockTime(m.ts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (out) {
                        Spacer(Modifier.width(4.dp))
                        StatusMark(m.status)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusMark(s: MessageStatus?) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val (glyph, color) = when (s) {
        MessageStatus.SENDING -> "🕒" to dim      // clock
        MessageStatus.WAITING -> "⏳" to dim            // hourglass
        MessageStatus.DELIVERED -> "✓" to dim
        MessageStatus.READ -> "✓✓" to MaterialTheme.colorScheme.primary
        MessageStatus.FAILED -> "!" to MaterialTheme.colorScheme.error
        null -> "" to dim
    }
    Text(glyph, style = MaterialTheme.typography.labelMedium, color = color)
}

@Composable
private fun Composer(onAttach: () -> Unit, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = onAttach) { Icon(Icons.Filled.Add, "Attach a file") }
            OutlinedTextField(
                text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Spacer(Modifier.width(4.dp))
            FilledIconButton(
                onClick = { val t = text.trim(); if (t.isNotEmpty()) { onSend(t); text = "" } },
                enabled = text.isNotBlank(),
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}

@Composable
private fun bannerColors() = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
