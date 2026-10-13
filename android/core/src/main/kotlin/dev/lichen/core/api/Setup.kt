package dev.lichen.core.api

import java.io.File

// Creating or importing an identity, before there is a ChatClient. The data folder uses the same file
// names and formats as the Python node's data folder (key, enckey, device, phonebook.json, trust.json,
// dms.json, outbox.json, linked), plus settings.json ({"name": ..., "hubs": [...], "strangers": ...}) for what the
// Python node keeps in lichen.yaml.

data class Settings(val name: String, val hubs: List<String>, val strangers: Strangers = Strangers.REQUESTS)

/** What happens when someone who isn't in your contacts (and isn't blocked) writes, offers a file or invites you. */
enum class Strangers {
    /** Their messages, file offers and invites wait in Requests (the default). */
    REQUESTS,
    /** Their chats go straight into the chat list. */
    CHATS,
    /** Dropped without telling them, as if blocked. */
    IGNORE,
}

sealed interface SetupResult {
    data class Ok(val settings: Settings) : SetupResult
    data class Failed(val reason: String) : SetupResult  // wrong passphrase, not an export, ...
}

interface LichenSetup {
    fun hasIdentity(dataDir: File): Boolean
    fun settings(dataDir: File): Settings?
    fun saveSettings(dataDir: File, settings: Settings)

    /** A brand-new identity on this phone. */
    fun create(dataDir: File, settings: Settings)

    /**
     * Become an existing identity from a `lichen --export` file (contents of me.lichen). Name and hubs come
     * from the lichen.yaml inside the export when it has one (keys `name` and `relay`, a string or a list).
     */
    fun import(dataDir: File, exportText: String, passphrase: String): SetupResult

    /** A client for an existing data folder. Not started. */
    fun open(dataDir: File): ChatClient
}
