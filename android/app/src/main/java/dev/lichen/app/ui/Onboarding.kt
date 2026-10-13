package dev.lichen.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.lichen.app.Backend
import dev.lichen.core.api.SetupResult
import dev.lichen.core.api.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OnboardingScreen(dataDir: java.io.File, onDone: () -> Unit, onCancel: (() -> Unit)? = null) {
    var mode by rememberSaveable { mutableStateOf("") }
    Scaffold { pad ->
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
    if (onCancel != null) {
        IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    } else Spacer(Modifier.height(32.dp))
    Text("Lichen", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
    Text(
        "Private chat that belongs to you. Messages are end-to-end encrypted, and there is no account or server that owns your conversations.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(8.dp))
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("I use Lichen on my computer", style = MaterialTheme.typography.titleMedium)
            Text("Bring your identity, contacts and chats over to this phone.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { choose("import") }) { Text("Import from my computer") }
        }
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("New identity", style = MaterialTheme.typography.titleMedium)
            Text("I'm new here. Make me a fresh identity on this phone.", style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = { choose("new") }) { Text("Start fresh") }
        }
    }
}

@Composable
private fun Back(onBack: () -> Unit, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.headlineSmall)
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
        style = MaterialTheme.typography.bodyMedium,
    )
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Text("lichen --export me.lichen", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    }
    Text(
        "Then copy me.lichen to this phone (cloud drive, USB cable, or a messenger to yourself) and pick it here.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) {
        Text(if (uri == null) "Choose me.lichen" else "Chosen: $fileName")
    }
    OutlinedTextField(
        pass, { pass = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Export passphrase") },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !busy,
        isError = error != null, supportingText = { error?.let { Text(it) } },
    )
    Button(
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
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Importing…")
        } else Text("Import")
    }
}

@Composable
private fun NewForm(dataDir: java.io.File, onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    val hubs = remember { mutableStateListOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Back(onBack, "New identity")
    Text(
        "Pick the name people will see, and the address of a hub. A hub is a small server that passes your encrypted " +
            "messages along and holds them while you're offline; it can't read them.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Your name") }, singleLine = true, enabled = !busy)
    hubs.forEachIndexed { i, h ->
        OutlinedTextField(
            h, { hubs[i] = it }, Modifier.fillMaxWidth(), label = { Text(if (i == 0) "Hub address" else "Another hub") },
            placeholder = { Text("wss://…") }, singleLine = true, enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            trailingIcon = if (hubs.size > 1) {
                { IconButton({ hubs.removeAt(i) }) { Icon(Icons.Default.Close, "Remove hub") } }
            } else null,
        )
    }
    TextButton(onClick = { hubs.add("") }, enabled = !busy) {
        Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add another hub")
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
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
    ) { Text("Create my identity") }
}
