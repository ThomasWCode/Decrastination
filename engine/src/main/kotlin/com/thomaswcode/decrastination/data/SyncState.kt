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
    /** How the two keep in step: [MIRROR], its stores sent whole, or [COMMANDS], commands sent and its stores pulled. */
    val mode: String = MIRROR,
    /** The revision of the stores last pulled, once the server applies commands. */
    val revision: Long = 0,
    /** What the server couldn't apply, and why, newest last, the last few. */
    val dropped: List<String> = emptyList(),
    /** This phone's Firebase Installation ID, as Firebase last registered it: where the server tells it of a change at once (W-18). */
    val pushId: String? = null,
    /**
     * The newest of the server's let-gos acted on here (D-94), by its revision: a pull of everything says them all, and those
     * already acted on aren't again, as the copies made here since are what a migration that fails is put right from.
     */
    val letGoSeen: Long = 0,
    /**
     * The parent code's secret as the server last took it, by its hash, or [NO_CODE] where it took none: it goes again where the one
     * here differs, once the two keep in step by commands (W-23), and never before. Null: none sent yet.
     */
    val parentCodeSent: String? = null,
    /** The shape this is in ([Migration]): raised with each change of it, a migration bringing older files to it. */
    val version: Int = 5,
) {
    companion object {
        const val DEFAULT_SERVER = "https://decrastination.thomaswhite.me"
        const val MIRROR = "mirror"
        const val COMMANDS = "commands"

        /** The three fields 5.2.0 adds, each with its default: nothing to bring across. */
        val V2 = Migration(1) { it }

        /** The id 5.11.0 adds, with its default: nothing to bring across. */
        val V3 = Migration(2) { it }

        /** The let-gos acted on, 5.12.0's, from none: nothing to bring across. */
        val V4 = Migration(3) { it }

        /** The parent code as the server last took it, 5.14.0's, from none sent: nothing to bring across. */
        val V5 = Migration(4) { it }

        /** [parentCodeSent] where the server's been told there's no parent code. */
        const val NO_CODE = "none"
    }
}
