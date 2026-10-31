package com.thomaswcode.decrastination.data

import kotlinx.serialization.Serializable

/** What this phone keeps of its pairing with the server (`sync.json`, this phone's alone): where it is, and what it's been sent. */
@Serializable
data class SyncState(
    /** The server, the website's own address. */
    val server: String = DEFAULT_SERVER,
    /** This phone's id there, once it's paired. */
    val deviceId: String? = null,
    /** Each store's text as last accepted, by its hash: only what's changed since goes. */
    val sent: Map<String, String> = emptyMap(),
    /** When the server last took what was sent. */
    val lastSyncAt: Long? = null,
    /** Why the last try failed; null when it worked. */
    val lastError: String? = null,
    /** The server's version as last heard, for Setup. */
    val serverVersion: String? = null,
    /** The stores it refused last, as newer than it can read: this build is ahead of it till it's deployed. */
    val refused: List<String> = emptyList(),
    /** The shape this is in ([Migration]): raised with each change of it, a migration bringing older files to it. */
    val version: Int = 1,
) {
    companion object {
        const val DEFAULT_SERVER = "https://decrastination.thomaswhite.me"
    }
}
