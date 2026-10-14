package dev.lichen.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import dev.lichen.app.Backend
import dev.lichen.app.OpenChat
import dev.lichen.app.ui.theme.*
import dev.lichen.core.api.*
import kotlinx.coroutines.launch

// Group chats: rows for the contacts screen, the chat, "new group" and the group info screen.

/** The id a group goes by in navigation stacks, notifications and [Backend.currentChat]. */
fun groupKey(groupId: String) = "group:$groupId"

internal sealed interface CollageTile {
    data class Person(val pubkey: String, val name: String) : CollageTile
    data class More(val count: Int) : CollageTile
}

/**
 * Who a group's icon shows: the other members, or just you when there is no one else.
 * Up to four tiles; past four, the fourth is "+N" for everyone not shown.
 */
internal fun collageTiles(members: List<GroupMember>): List<CollageTile> {
    val shown = members.filter { !it.isYou }.ifEmpty { members }
    if (shown.size <= 4) return shown.map { CollageTile.Person(it.pubkey, it.name) }
    return shown.take(3).map { CollageTile.Person(it.pubkey, it.name) } + CollageTile.More(shown.size - 3)
}

/**
 * A group's icon: its members' avatars (the same tint and initial as [Avatar]) in a square, split by 2dp
 * edge-colored lines and framed in the same 2dp edge. One member fills it; two sit side by side; three are one
 * tall tile and two small ones; four or more make a 2x2 grid, the last tile "+N" when there are more than four.
 */
@Composable
fun GroupAvatar(members: List<GroupMember>, size: Dp = 46.dp, modifier: Modifier = Modifier) {
    val p = Slab.colors
    val tiles = collageTiles(members)
    val gap = 2.dp
    val col = Modifier.fillMaxHeight()
    Box(modifier.size(size).background(p.edge).padding(gap)) {
        when (tiles.size) {
            0 -> Box(Modifier.fillMaxSize().background(p.card), contentAlignment = Alignment.Center) {
                Text("#", color = p.muted, fontFamily = Slab.mono, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.39f).sp)
            }
            1 -> CollageCell(tiles[0], Modifier.fillMaxSize())
            2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                CollageCell(tiles[0], col.weight(1f)); CollageCell(tiles[1], col.weight(1f))
            }
            3 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                CollageCell(tiles[0], col.weight(1f))
                Column(col.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                    CollageCell(tiles[1], Modifier.fillMaxWidth().weight(1f)); CollageCell(tiles[2], Modifier.fillMaxWidth().weight(1f))
                }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                for (r in 0..1) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    CollageCell(tiles[r * 2], col.weight(1f)); CollageCell(tiles[r * 2 + 1], col.weight(1f))
                }
            }
        }
    }
}

/** One collage tile: a person's avatar tint and mono initial, or "+N" on the bar color. */
@Composable
private fun CollageCell(t: CollageTile, modifier: Modifier) {
    val p = Slab.colors
    BoxWithConstraints(
        modifier.background(if (t is CollageTile.Person) avatarTint(t.pubkey) else p.bar), contentAlignment = Alignment.Center,
    ) {
        val fontSize = (minOf(maxWidth, maxHeight).value * 0.5f).coerceIn(8f, 28f)
        when (t) {
            is CollageTile.Person -> Text(
                initialOf(t.name), color = Color(0xFF0B0D0A), fontFamily = Slab.mono, fontWeight = FontWeight.SemiBold,
                fontSize = fontSize.sp, maxLines = 1,
            )
            is CollageTile.More -> Text(
                "+${t.count}", color = p.text, fontFamily = Slab.mono, fontWeight = FontWeight.SemiBold,
                fontSize = (fontSize * 0.8f).sp, letterSpacing = (-0.04).em, maxLines = 1,
            )
        }
    }
}

/** Invites (with Accept / Decline) and groups, for the top of the chat list. Laid out like a contact's row. */
fun LazyListScope.groupRows(
    groups: List<GroupInfo>, onOpen: (String) -> Unit, onAccept: (String) -> Unit, onDecline: (String) -> Unit,
) {
    items(groups, key = { "group-row:${it.id}" }) { g ->
        val p = Slab.colors
        Column(Modifier.fillMaxWidth().clickable(enabled = !g.pending) { onOpen(g.id) }) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GroupAvatar(g.members)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                g.name, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Bold, fontSize = 16.sp, color = p.text,
                            )
                            Text(
                                if (g.pending) "INVITE" else "GROUP · ${g.members.size}", fontFamily = Slab.mono, fontSize = 10.sp,
                                letterSpacing = 0.08.em, color = if (g.pending) p.alert else p.muted, maxLines = 1,
                            )
                        }
                        if (!g.pending) g.lastMessage?.let {
                            Text(
                                relativeTime(it.ts), fontFamily = Slab.mono, fontSize = 11.sp,
                                color = if (g.unread > 0) p.accent else p.muted,
                            )
                        }
                    }
                    if (g.pending) {
                        Text("Invited by ${g.ownerName}", fontSize = 14.sp, color = p.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SlabButton("Accept", { onAccept(g.id) }, height = 36.dp)
                            SlabButton("Decline", { onDecline(g.id) }, style = SlabButtonStyle.Secondary, height = 36.dp)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                g.lastMessage?.let { (if (it.outgoing) "You" else it.authorName) + ": " + it.text } ?: "No messages yet",
                                Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = p.subtle,
                            )
                            if (g.unread > 0) MonoTag(g.unread.toString(), p.accent, p.onAccent, shadow = p.shadow)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(p.card))
        }
    }
}

/** A group chat's list: messages and file offers, with a day chip wherever the day changes. */
internal sealed interface GroupRow {
    val key: String
    data class Day(val label: String, override val key: String) : GroupRow
    /** [first]: the first of a run from one author, which gets their avatar and name above it. */
    data class Msg(val m: GroupMessage, val first: Boolean) : GroupRow { override val key: String get() = m.id }
    data class File(val t: FileTransfer, val first: Boolean) : GroupRow { override val key: String get() = "f:${t.id}" }
}

/** [files] are the group's transfers (FileTransfer.group == the group id); they interleave with [messages] by time. */
internal fun groupRowsOf(messages: List<GroupMessage>, files: List<FileTransfer> = emptyList()): List<GroupRow> {
    // (ts, author, row built with "first of a run")
    val items = (messages.map { m -> Triple(m.ts, m.from) { first: Boolean -> GroupRow.Msg(m, first) as GroupRow } } +
        files.map { t -> Triple(t.ts, t.from) { first: Boolean -> GroupRow.File(t, first) as GroupRow } }).sortedBy { it.first }
    val out = ArrayList<GroupRow>(items.size + 4)
    var lastDay: String? = null
    var lastFrom: String? = null
    for ((ts, from, row) in items) {
        val r = row(from != lastFrom)
        val day = dayLabel(ts)
        if (day != lastDay) {
            out += GroupRow.Day(day, "day:$day:${r.key}"); lastDay = day
            out += row(true)
        } else out += r
        lastFrom = from
    }
    return out
}

@Composable
fun GroupChatScreen(client: ChatClient, profileId: String, groupId: String, onBack: () -> Unit, onInfo: () -> Unit) {
    val groups by client.groups.groups.collectAsState()
    val group = groups.firstOrNull { it.id == groupId }
    val messages by client.groups.messages(groupId).collectAsState()
    // Group file offers share the timeline with the messages.
    val transfers by client.files.transfers.collectAsState()
    val rows = remember(messages, transfers) { groupRowsOf(messages, transfers.filter { it.group == groupId }) }
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
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val g = group ?: return@rememberLauncherForActivityResult
        val others = g.members.filter { !it.isYou }.map { it.pubkey }
        if (uri != null && others.isNotEmpty()) offerFile(context, client, others, groupId, uri)
    }
    LaunchedEffect(rows.size) { if (rows.isNotEmpty()) listState.animateScrollToItem(rows.size - 1) }

    val p = Slab.colors
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = p.ground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            SlabTopBar(onBack = onBack, content = {
                Row(
                    Modifier.weight(1f).clickable(enabled = group != null, onClick = onInfo),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    group?.let { GroupAvatar(it.members, 40.dp) }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            group?.name ?: "Group", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                            letterSpacing = (-0.01).em, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (group != null) Text(
                            "■ ${group.members.size} PEOPLE · ENCRYPTED", fontFamily = Slab.mono, fontSize = 10.sp,
                            letterSpacing = 0.1.em, color = p.muted, maxLines = 1,
                        )
                    }
                }
                if (group != null) SlabIconButton(Icons.Outlined.Info, "Group info", onInfo)
            })
        },
        bottomBar = {
            if (group != null && !group.pending) Composer({
                if (group.members.any { !it.isYou }) picker.launch(arrayOf("*/*"))
                else scope.launch { snackbar.showSnackbar("No one else is in this group yet") }
            }) { text ->
                scope.launch {
                    val r = client.groups.send(groupId, text)
                    if (r is GroupResult.Failed) snackbar.showSnackbar(r.reason)
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (group == null) {
                Text("This group isn't available (any more).", Modifier.padding(24.dp), color = p.subtle, fontSize = 15.sp)
                return@Column
            }
            if (group.pending) {
                Banner(
                    "Invited by ${group.ownerName}", "Accept", "Decline",
                    { scope.launch { client.groups.accept(groupId) } },
                    { scope.launch { client.groups.decline(groupId); onBack() } },
                )
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), state = listState,
                contentPadding = PaddingValues(start = 14.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rows, key = { it.key }) { r ->
                    when (r) {
                        is GroupRow.Day -> DayChip(r.label)
                        is GroupRow.Msg -> GroupBubble(r.m, r.first)
                        is GroupRow.File -> FileBubble(
                            client.files, r.t,
                            header = if (r.t.outgoing || !r.first) null else {
                                { AuthorHeader(r.t.from, group.members.firstOrNull { it.pubkey == r.t.from }?.name ?: r.t.from.take(8)) }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** The DM bubble, with the sender's avatar and name above the first of a run of someone else's messages. */
@Composable
private fun GroupBubble(m: GroupMessage, first: Boolean) {
    val header: (@Composable () -> Unit)? = if (m.outgoing || !first) null else { { AuthorHeader(m.from, m.authorName) } }
    SlabBubble(m.text, m.ts, m.outgoing, header = header)
}

/** The sender's avatar and name above the first of a run of someone else's messages or files. */
@Composable
private fun AuthorHeader(pubkey: String, name: String) {
    val p = Slab.colors
    Row(
        Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SquareAvatar(initialOf(name), avatarTint(pubkey), 20.dp)
        Text(
            name.uppercase(), fontFamily = Slab.mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
            letterSpacing = 0.08.em, color = p.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GroupAvatar(g.members, 64.dp)
                Text(g.name, style = MaterialTheme.typography.headlineSmall)
            }
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
