package com.thomaswcode.decrastination.views

import kotlinx.serialization.Serializable

/**
 * The storage, as Settings shows it on the phone and the website (6.10.0, D-161): each free allowance's [lines], the box's own beside
 * them ([box]), when it was last read ([read]), and what couldn't be ([problems]). Made by the server, as only it reads them.
 */
@Serializable
data class StorageView(val lines: List<StorageLine>, val box: List<String>, val read: String, val problems: List<String> = emptyList())

/** An allowance's line: what it is, how much is used of how much, its share, when it'd be passed at the last week's rate, and whether it's near. */
@Serializable
data class StorageLine(val label: String, val used: String, val percent: Int, val passes: String? = null, val near: Boolean = false)
