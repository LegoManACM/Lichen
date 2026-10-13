package dev.lichen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import dev.lichen.app.Backend
import dev.lichen.app.ui.theme.*
import dev.lichen.core.api.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun ChatScreen(client: ChatClient, profileId: String, pubkey: String, onBack: () -> Unit, onInfo: () -> Unit) {
    val messages by client.messages(pubkey).collectAsState()
    val people by client.people.collectAsState()
    val typingNow by client.typing.collectAsState()
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
    val rows = remember(messages) { withDays(messages) }
    LaunchedEffect(rows.size) { if (rows.isNotEmpty()) listState.animateScrollToItem(rows.size - 1) }

    val p = Slab.colors
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = p.ground,
        topBar = {
            SlabTopBar(onBack = onBack, content = {
                Row(
                    Modifier.weight(1f).clickable(onClick = onInfo), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    contact?.let { Avatar(it, 40.dp, showOnline = false) }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            contact?.displayName ?: pubkey.take(8), fontWeight = FontWeight.Bold, fontSize = 17.sp,
                            letterSpacing = (-0.01).em, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val (status, color) = when {
                            pubkey in typingNow -> "TYPING…" to p.accent
                            contact?.online == true -> "■ ONLINE · ENCRYPTED" to p.accent
                            else -> "■ OFFLINE · ENCRYPTED" to p.muted
                        }
                        Text(status, fontFamily = Slab.mono, fontSize = 10.sp, letterSpacing = 0.1.em, color = color, maxLines = 1)
                    }
                }
                SlabIconButton(Icons.Outlined.Info, "Chat info", onInfo)
            })
        },
        bottomBar = { Composer(onTyping = { client.typing(pubkey) }) { text -> scope.launch { client.send(pubkey, text) } } },
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
                contentPadding = PaddingValues(start = 14.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rows, key = { it.key }) { r ->
                    when (r) {
                        is ChatRow.Day -> DayChip(r.label)
                        is ChatRow.Msg -> Bubble(r.m)
                    }
                }
            }
        }
    }
}

/** The chat list: messages with a day chip wherever the day changes. */
private sealed interface ChatRow {
    val key: String
    data class Day(val label: String, override val key: String) : ChatRow
    data class Msg(val m: ChatMessage) : ChatRow { override val key: String get() = m.id }
}

private fun withDays(messages: List<ChatMessage>): List<ChatRow> {
    val out = ArrayList<ChatRow>(messages.size + 4)
    var last: String? = null
    for (m in messages) {
        val label = dayLabel(m.ts)
        if (label != last) { out += ChatRow.Day(label, "day:$label:${m.id}"); last = label }
        out += ChatRow.Msg(m)
    }
    return out
}

private fun dayLabel(ts: Double): String {
    val cal = Calendar.getInstance().apply { timeInMillis = (ts * 1000).toLong() }
    val today = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        same(cal, today) -> "TODAY"
        same(cal, yesterday) -> "YESTERDAY"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(cal.time).uppercase()
    }
}

@Composable
private fun DayChip(label: String) {
    val p = Slab.colors
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            label, Modifier.slabBorder(p.line, 1.dp).padding(horizontal = 10.dp, vertical = 4.dp),
            fontFamily = Slab.mono, fontSize = 10.sp, letterSpacing = 0.14.em, color = p.muted,
        )
    }
}

@Composable
private fun Banner(text: String, primary: String, secondary: String?, onPrimary: () -> Unit, onSecondary: (() -> Unit)?) {
    val p = Slab.colors
    Column(Modifier.fillMaxWidth().background(p.alertGround)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = p.text)
            if (secondary != null && onSecondary != null) SlabButton(secondary, onSecondary, style = SlabButtonStyle.Secondary, height = 40.dp)
            SlabButton(primary, onPrimary, height = 40.dp)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(p.alert))
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val p = Slab.colors
    val out = m.outgoing
    Column(
        Modifier.fillMaxWidth(), horizontalAlignment = if (out) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val bubble = Modifier.widthIn(max = 300.dp).let {
            if (out) it.hardShadow(p.shadow).background(p.accent).padding(horizontal = 13.dp, vertical = 10.dp)
            else it.background(p.card).slabBorder(p.edge).padding(horizontal = 12.dp, vertical = 9.dp)
        }
        Text(
            m.text, bubble, fontSize = 15.sp, lineHeight = 21.sp,
            fontWeight = if (out) FontWeight.Medium else FontWeight.Normal, color = if (out) p.onAccent else p.text,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clockTime(m.ts), fontFamily = Slab.mono, fontSize = 10.sp, color = p.muted)
            if (out) StatusMark(m.status)
        }
    }
}

@Composable
private fun StatusMark(s: MessageStatus?) {
    val p = Slab.colors
    val (label, color) = when (s) {
        MessageStatus.SENDING -> "SENDING" to p.muted
        MessageStatus.WAITING -> "WAITING FOR THEM TO COME ONLINE" to p.muted
        MessageStatus.DELIVERED -> "DELIVERED" to p.muted
        MessageStatus.READ -> "READ" to p.accent
        MessageStatus.FAILED -> "FAILED" to MaterialTheme.colorScheme.error
        null -> return
    }
    Text(" · $label", fontFamily = Slab.mono, fontSize = 10.sp, color = color)
}

@Composable
private fun Composer(onTyping: () -> Unit = {}, onSend: (String) -> Unit) {
    val p = Slab.colors
    var text by rememberSaveable { mutableStateOf("") }
    SlabBottomBar {
        BasicTextField(
            text, { text = it; if (it.isNotEmpty()) onTyping() },
            Modifier.weight(1f).heightIn(min = 48.dp).background(p.card).slabBorder(p.edge),
            textStyle = TextStyle(color = p.text, fontSize = 15.sp, fontFamily = Slab.sans),
            cursorBrush = SolidColor(p.accent), maxLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 12.dp, vertical = 13.dp), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text("Message", color = p.muted, fontSize = 15.sp)
                    inner()
                }
            },
        )
        SlabIconButton(
            Icons.AutoMirrored.Filled.Send, "Send", { val t = text.trim(); if (t.isNotEmpty()) { onSend(t); text = "" } },
            filled = true, enabled = text.isNotBlank(), size = 48.dp,
        )
    }
}
