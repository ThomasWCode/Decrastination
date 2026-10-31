package com.thomaswcode.decrastination.sync

import kotlinx.serialization.Serializable

/**
 * What the phone and the server say to each other (docs/plan_web/data-and-sync.md). Every request names [VERSION]
 * in [HEADER]; the server takes its own and the one before, and a client behind that gets a 409 and carries on from
 * what it holds till it's installed (W-16).
 */
object Protocol {
    const val VERSION = 1
    const val HEADER = "X-Protocol"
}

/** A store's whole text, as its file holds it. */
@Serializable
data class StoreText(val name: String, val version: Int, val text: String)

/** The phone's stores as they are (the server in mirror mode, stage 1): those changed since it last sent them. */
@Serializable
data class SeedBatch(
    /** The phone's time zone, which the server keeps to: a day is the owner's wherever they are. */
    val zone: String,
    /** The phone's build, for the record. */
    val build: String,
    val stores: List<StoreText>,
)

/** What came of a [SeedBatch]: the revision it took, and the stores refused, as newer than the server can read. */
@Serializable
data class SeedAnswer(val revision: Long, val refused: List<String> = emptyList())

/** What a client can ask of the server with no sign-in: its version, the protocol and each store's version. */
@Serializable
data class ServerVersion(val version: String, val protocol: Int, val mode: String, val stores: Map<String, Int>)

/** A phone's claim to be paired: the code the website showed, and what to call it. */
@Serializable
data class DeviceClaim(val code: String, val name: String)

/** A claim that held: the device's id and its token, which is shown this once. */
@Serializable
data class DeviceToken(val id: String, val token: String)

/** A device, as the website lists it. */
@Serializable
data class DeviceLine(val id: String, val name: String, val createdAt: Long, val lastSeenAt: Long? = null)

/** The code the website shows, to type into the phone. */
@Serializable
data class PairingCode(val code: String, val expiresAt: Long)
