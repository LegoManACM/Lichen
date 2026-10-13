package dev.lichen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import dev.lichen.app.Backend
import dev.lichen.app.OpenChat
import dev.lichen.core.api.*
import kotlinx.coroutines.launch

// Group chats, minimal: rows for the contacts screen, the chat, "new group" and the group info screen.
// A designer will redo the visuals; this is just enough to use the feature.

/** The id a group goes by in navigation stacks, notifications and [Backend.currentChat]. */
fun groupKey(groupId: String) = "group:$groupId"

@Composable
private fun GroupAvatar(name: String, id: String) {
    val hue = (id.hashCode().toUInt() % 360u).toFloat()
    Box(Modifier.size(48.dp).clip(CircleShape).background(Color.hsv(hue, 0.35f, 0.6f)), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifEmpty { "#" }, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

/** Invites (with Accept / Decline) and groups, for the top of the chat list. */
fun LazyListScope.groupRows(
    groups: List<GroupInfo>, onOpen: (String) -> Unit, onAccept: (String) -> Unit, onDecline: (String) -> Unit,
) {
    items(groups, key = { "group-row:${it.id}" }) { g ->
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !g.pending) { onOpen(g.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupAvatar(g.name, g.id)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "# ${g.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (g.unread > 0) FontWeight.Bold else FontWeight.Medium,
                )
                if (g.pending) {
                    Text("Invited by ${g.ownerName}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        TextButton(onClick = { onAccept(g.id) }) { Text("Accept") }
                        TextButton(onClick = { onDecline(g.id) }) { Text("Decline") }
                    }
                } else {
                    Text(
                        g.lastMessage?.let { (if (it.outgoing) "You" else it.authorName) + ": " + it.text } ?: "${g.members.size} members",
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!g.pending) {
                Column(horizontalAlignment = Alignment.End) {
                    g.lastMessage?.let { Text(relativeTime(it.ts), style = MaterialTheme.typography.labelSmall) }
                    if (g.unread > 0) { Spacer(Modifier.height(4.dp)); Badge { Text(g.unread.toString()) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(client: ChatClient, profileId: String, groupId: String, onBack: () -> Unit, onInfo: () -> Unit) {
    val groups by client.groups.groups.collectAsState()
    val group = groups.firstOrNull { it.id == groupId }
    val messages by client.groups.messages(groupId).collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)

    LaunchedEffect(groupId, resumed, messages.size) { if (resumed) client.groups.markRead(groupId) }
    DisposableEffect(profileId, groupId, resumed) {
        val me = OpenChat(profileId, groupKey(groupId))
        if (resumed) Backend.currentChat.value = me
        onDispose { if (Backend.currentChat.value == me) Backend.currentChat.value = null }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column(Modifier.clickable(enabled = group != null, onClick = onInfo)) {
                        Text(group?.let { "# ${it.name}" } ?: "Group", style = MaterialTheme.typography.titleMedium)
                        if (group != null) Text("${group.members.size} members", style = MaterialTheme.typography.labelSmall)
                    }
                },
            )
        },
        bottomBar = {
            if (group != null && !group.pending) Composer(onAttach = {}) { text ->
                scope.launch {
                    val r = client.groups.send(groupId, text)
                    if (r is GroupResult.Failed) snackbar.showSnackbar(r.reason)
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (group == null) {
                Text("This group isn't available (any more).", Modifier.padding(24.dp))
                return@Column
            }
            if (group.pending) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Invited by ${group.ownerName}", Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { client.groups.decline(groupId); onBack() } }) { Text("Decline") }
                        TextButton(onClick = { scope.launch { client.groups.accept(groupId) } }) { Text("Accept") }
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(messages, key = { it.id }) { GroupBubble(it) }
            }
        }
    }
}

@Composable
private fun GroupBubble(m: GroupMessage) {
    val out = m.outgoing
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (out) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (out) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(18.dp, 18.dp, if (out) 4.dp else 18.dp, if (out) 18.dp else 4.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!out) Text(m.authorName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(m.text, style = MaterialTheme.typography.bodyLarge)
                Text(clockTime(m.ts), Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// --- new group ---------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupScreen(client: ChatClient, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val people by client.people.collectAsState()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var shared by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val shown = remember(people, query) {
        people.filter { it.trust != Trust.BLOCKED && (query.isBlank() || it.displayName.contains(query, true)) }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("New group") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                TextButton(enabled = name.isNotBlank() && !busy, onClick = {
                    busy = true; error = null
                    scope.launch {
                        when (val r = client.groups.create(name.trim(), picked.toList(), if (shared) GroupHistory.SHARED else GroupHistory.PRIVATE)) {
                            is GroupResult.Ok -> onCreated(r.groupId)
                            is GroupResult.Failed -> { error = r.reason; busy = false }
                        }
                    }
                }) { Text("Create") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                name, { name = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("Group name") }, singleLine = true,
            )
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Newcomers can read earlier messages")
                    Text(
                        if (shared) "Shared history" else "Private history: people who join later only see what comes after",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(shared, { shared = it })
            }
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search people to invite") }, singleLine = true,
            )
            LazyColumn {
                items(shown, key = { it.pubkey }) { c ->
                    val on = c.pubkey in picked
                    ListItem(
                        modifier = Modifier.clickable { if (on) picked.remove(c.pubkey) else picked.add(c.pubkey) },
                        leadingContent = { Avatar(c, 40.dp) },
                        headlineContent = { Text(c.displayName) },
                        trailingContent = { Checkbox(on, { if (it) picked.add(c.pubkey) else picked.remove(c.pubkey) }) },
                    )
                }
            }
        }
    }
}

// --- group info --------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(client: ChatClient, groupId: String, onBack: () -> Unit, onGone: () -> Unit) {
    val groups by client.groups.groups.collectAsState()
    val people by client.people.collectAsState()
    val g = groups.firstOrNull { it.id == groupId }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    fun act(leaves: Boolean = false, block: suspend () -> GroupResult) {
        scope.launch {
            when (val r = block()) {
                is GroupResult.Ok -> { r.detail?.let { snackbar.showSnackbar(it) }; if (leaves) onGone() }
                is GroupResult.Failed -> snackbar.showSnackbar(r.reason)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Group") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        if (g == null) {
            Text("This group isn't available (any more).", Modifier.padding(pad).padding(24.dp))
            return@Scaffold
        }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("# ${g.name}", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Owner: ${g.ownerName}" + if (g.youOwn) " (you)" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Newcomers can read earlier messages")
                    Text(
                        if (g.history == GroupHistory.SHARED) "Shared history" else "Private history",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    g.history == GroupHistory.SHARED, enabled = g.youOwn,
                    onCheckedChange = { on -> act { client.groups.setHistory(g.id, if (on) GroupHistory.SHARED else GroupHistory.PRIVATE) } },
                )
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${g.members.size} members", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (g.youOwn) TextButton(onClick = { adding = true }) { Text("Add member") }
            }
            for (m in g.members) {
                ListItem(
                    headlineContent = { Text(m.name + if (m.isYou) " (you)" else "") },
                    supportingContent = if (m.isOwner) ({ Text("Owner") }) else null,
                    trailingContent = if (g.youOwn && !m.isYou) ({
                        Box {
                            IconButton(onClick = { menuFor = m.pubkey }) { Icon(Icons.Default.MoreVert, "Member actions") }
                            DropdownMenu(menuFor == m.pubkey, { menuFor = null }) {
                                DropdownMenuItem(text = { Text("Remove from group") }, onClick = { menuFor = null; act { client.groups.removeMember(g.id, m.pubkey) } })
                                DropdownMenuItem(text = { Text("Make owner") }, onClick = { menuFor = null; act { client.groups.transfer(g.id, m.pubkey) } })
                                DropdownMenuItem(text = { Text("Make owner and leave") }, onClick = { menuFor = null; act(leaves = true) { client.groups.leave(g.id, m.pubkey) } })
                            }
                        }
                    }) else null,
                )
            }
            HorizontalDivider()
            if (!g.youOwn) {
                OutlinedButton(onClick = { confirmLeave = true }, Modifier.fillMaxWidth()) { Text("Leave group") }
            } else {
                Text(
                    "To leave, make another member the owner (the menu next to their name), or delete the group for everyone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { confirmDelete = true }, Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete group for everyone") }
            }
        }

        if (adding) {
            val candidates = people.filter { p -> p.trust != Trust.BLOCKED && g.members.none { it.pubkey == p.pubkey } }
            AlertDialog(
                onDismissRequest = { adding = false },
                title = { Text("Add a member") },
                text = {
                    if (candidates.isEmpty()) Text("There is no one else to add.")
                    else LazyColumn {
                        items(candidates, key = { it.pubkey }) { c ->
                            ListItem(
                                modifier = Modifier.clickable { adding = false; act { client.groups.addMember(g.id, c.pubkey) } },
                                leadingContent = { Avatar(c, 36.dp) }, headlineContent = { Text(c.displayName) },
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
            )
        }
        if (confirmLeave) {
            AlertDialog(
                onDismissRequest = { confirmLeave = false },
                title = { Text("Leave ${g.name}?") },
                text = { Text("You will stop getting its messages.") },
                confirmButton = { TextButton(onClick = { confirmLeave = false; act(leaves = true) { client.groups.leave(g.id) } }) { Text("Leave") } },
                dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
            )
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete ${g.name}?") },
                text = { Text("Everyone's copy of the group and its messages is removed.") },
                confirmButton = { TextButton(onClick = { confirmDelete = false; act(leaves = true) { client.groups.delete(g.id) } }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }
    }
}
