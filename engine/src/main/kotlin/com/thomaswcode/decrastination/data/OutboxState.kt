package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.sync.protocol.Command
import kotlinx.serialization.Serializable

/** The commands the server hasn't answered yet (`outbox.json`, this phone's alone), oldest first (docs/spec/sync.md). */
@Serializable
data class OutboxState(
    val commands: List<Command> = emptyList(),
    /** The shape this is in ([Migration]): raised with each change of it, a migration bringing older files to it. */
    val version: Int = 1,
)
