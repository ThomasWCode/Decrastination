package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.Calibration
import kotlinx.serialization.Serializable

/** What the app has learned about how long things take you (docs/spec/learning.md): Learning's. Kept in `learning.json`. */
@Serializable
data class LearningState(
    val calibration: Calibration = Calibration(),
    /** The shape this is in ([Migration]): raised with each change of it, a migration bringing older files to it. */
    val version: Int = 1,
)
