package dev.lichen.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.lichen.app.Backend
import dev.lichen.app.ui.theme.*
import dev.lichen.core.api.SetupResult
import dev.lichen.core.api.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OnboardingScreen(dataDir: java.io.File, onDone: () -> Unit, onCancel: (() -> Unit)? = null) {
    var mode by rememberSaveable { mutableStateOf("") }
    Scaffold(containerColor = Slab.colors.ground) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (mode) {
                "import" -> ImportForm(dataDir, onBack = { mode = "" }, onDone = onDone)
                "new" -> NewForm(dataDir, onBack = { mode = "" }, onDone = onDone)
                else -> Welcome(onCancel) { mode = it }
            }
        }
    }
}

@Composable
private fun Welcome(onCancel: (() -> Unit)?, choose: (String) -> Unit) {
    val p = Slab.colors
    if (onCancel != null) {
        SlabIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onCancel, bordered = false)
    } else Spacer(Modifier.height(32.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LichenMark(48.dp)
        Text("Lichen", fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = (-0.02).em, color = p.text)
    }
    Box(Modifier.width(64.dp).height(3.dp).background(p.accent))
    Text(
        "Private chat that belongs to you. Messages are end-to-end encrypted, and there is no account or server that owns your conversations.",
        fontSize = 16.sp, lineHeight = 23.sp, color = p.subtle,
    )
    Spacer(Modifier.height(8.dp))
    SlabCard(Modifier.fillMaxWidth().padding(end = 4.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("ALREADY USE LICHEN")
            Text("I use Lichen on my computer", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = p.text)
            Text("Bring your identity, contacts and chats over to this phone.", fontSize = 14.sp, color = p.subtle)
            SlabButton("Import from my computer", { choose("import") }, Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
    SlabCard(Modifier.fillMaxWidth().padding(end = 4.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("NEW HERE")
            Text("New identity", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = p.text)
            Text("I'm new here. Make me a fresh identity on this phone.", fontSize = 14.sp, color = p.subtle)
            SlabButton("Start fresh", { choose("new") }, Modifier.fillMaxWidth().padding(top = 4.dp), SlabButtonStyle.Secondary)
        }
    }
}

@Composable
private fun Back(onBack: () -> Unit, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlabIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, bordered = false)
            Text(title, fontWeight = FontWeight.Bold, fontSize = 21.sp, color = Slab.colors.text)
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(Slab.colors.accent))
    }
}

@Composable
private fun ImportForm(dataDir: java.io.File, onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var fileName by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            uri = u
            error = null
            fileName = runCatching {
                ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
                }
            }.getOrNull() ?: (u.lastPathSegment ?: "export file")
        }
    }

    Back(onBack, "Import from my computer")
    Text(
        "An export is one file that holds your identity (your keys, name and hubs), protected by a passphrase. " +
            "On your computer run:",
        fontSize = 14.sp, lineHeight = 20.sp, color = Slab.colors.subtle,
    )
    Text(
        "$ lichen --export me.lichen", Modifier.fillMaxWidth().background(Slab.colors.bar).slabBorder(Slab.colors.edge).padding(12.dp),
        fontFamily = Slab.mono, fontSize = 14.sp, color = Slab.colors.accent,
    )
    Text(
        "Then copy me.lichen to this phone (cloud drive, USB cable, or a messenger to yourself) and pick it here.",
        fontSize = 14.sp, lineHeight = 20.sp, color = Slab.colors.subtle,
    )
    SlabButton(
        if (uri == null) "Choose me.lichen" else "Chosen: $fileName", { picker.launch(arrayOf("*/*")) },
        Modifier.fillMaxWidth(), SlabButtonStyle.Card, enabled = !busy,
    )
    OutlinedTextField(
        pass, { pass = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Export passphrase") },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !busy,
        isError = error != null, supportingText = { error?.let { Text(it) } }, colors = slabFieldColors(),
    )
    SlabButton(
        if (busy) "Importing…" else "Import", modifier = Modifier.fillMaxWidth().padding(end = 4.dp), height = 52.dp, shadow = 4.dp,
        enabled = uri != null && pass.isNotEmpty() && !busy,
        onClick = {
            busy = true; error = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val text = ctx.contentResolver.openInputStream(uri!!)!!.use { it.readBytes().decodeToString() }
                        Backend.setup.import(dataDir, text, pass)
                    } catch (e: Exception) {
                        SetupResult.Failed("Couldn't read that file: ${e.message ?: "unknown error"}")
                    }
                }
                busy = false
                when (result) {
                    is SetupResult.Ok -> onDone()
                    is SetupResult.Failed -> error = result.reason
                }
            }
        },
    )
}

/** Filled in for a new identity; change it to use another hub. */
private const val DEFAULT_HUB = "wss://chat.afrikamcclintock.com"

@Composable
private fun NewForm(dataDir: java.io.File, onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    val hubs = remember { mutableStateListOf(DEFAULT_HUB) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Back(onBack, "New identity")
    Text(
        "Pick the name people will see, and the address of a hub. A hub is a small server that passes your encrypted " +
            "messages along and holds them while you're offline; it can't read them.",
        fontSize = 14.sp, lineHeight = 20.sp, color = Slab.colors.subtle,
    )
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Your name") }, singleLine = true, enabled = !busy, colors = slabFieldColors())
    hubs.forEachIndexed { i, h ->
        OutlinedTextField(
            h, { hubs[i] = it }, Modifier.fillMaxWidth(), label = { Text(if (i == 0) "Hub address" else "Another hub") },
            placeholder = { Text("wss://…") }, singleLine = true, enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = slabFieldColors(),
            textStyle = LocalTextStyle.current.copy(fontFamily = Slab.mono),
            trailingIcon = if (hubs.size > 1) {
                { IconButton({ hubs.removeAt(i) }) { Icon(Icons.Default.Close, "Remove hub") } }
            } else null,
        )
    }
    SlabButton("Add another hub", { hubs.add("") }, Modifier.fillMaxWidth(), SlabButtonStyle.Dashed, enabled = !busy, icon = Icons.Default.Add)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    SlabButton(
        if (busy) "Creating…" else "Create my identity", modifier = Modifier.fillMaxWidth().padding(end = 4.dp), height = 52.dp, shadow = 4.dp,
        enabled = !busy,
        onClick = {
            val hs = hubs.map { it.trim() }.filter { it.isNotEmpty() }
            when {
                name.isBlank() -> error = "Please choose a name."
                hs.isEmpty() -> error = "Add at least one hub address."
                else -> {
                    busy = true; error = null
                    scope.launch {
                        withContext(Dispatchers.IO) { Backend.setup.create(dataDir, Settings(name.trim(), hs)) }
                        busy = false
                        onDone()
                    }
                }
            }
        },
    )
}
