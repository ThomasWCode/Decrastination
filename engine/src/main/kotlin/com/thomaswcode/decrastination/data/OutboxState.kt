package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.sync.protocol.Command
import kotlinx.serialization.Serializable

/** The commands the server hasn't answered yet (`outbox.json`, this phone's alone), oldest first (docs/spec/sync.md). */
@Serializable
data class OutboxState(
    val commands: List<Queued> = emptyList(),
    /** The shape this is in ([Migration]): raised with each change of it, a migration bringing older files to it. */
    val version: Int = 1,
)

/** A command on its way, [applied] once it's been applied here: one that hasn't, its process stopped first, is applied at the next start. */
@Serializable
data class Queued(val command: Command, val applied: Boolean = false)
