package com.thomaswcode.decrastination.core

import kotlinx.serialization.Serializable

/**
 * Felix's answer (AI-22): his [message] (empty: he'd rather not write now), up to three [replies]
 * he'd accept, when he'd check again, and what to remember (AI-21).
 */
@Serializable
data class FelixAnswer(val message: String = "", val replies: List<String> = emptyList(), val nextCheckMin: Int? = null, val memories: List<Given> = emptyList())
