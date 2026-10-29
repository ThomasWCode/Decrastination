package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable

/**
 * Felix's answer (AI-22): his [message] (empty: he'd rather not write now), a line or two, each a text of its
 * own (D-120); up to three [replies] he'd accept, when he'd check again, and what to remember (AI-21); his note to
 * himself on how he felt ([feeling], D-119), written before the message and kept with it; and the texts he wrote
 * beside it and didn't send ([unsent], D-120), kept with it for your rating.
 */
@Serializable
data class FelixAnswer(val message: String = "", val replies: List<String> = emptyList(), val nextCheckMin: Int? = null, val memories: List<Given> = emptyList(), val feeling: String = "", val unsent: List<String> = emptyList())
