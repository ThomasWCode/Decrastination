package com.thomaswcode.decrastination.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.thomaswcode.decrastination.core.Slot
import java.time.LocalDate

/**
 * A piece or a break outside dragged on the Day (UI-04, 4.6.0): what's lifted, where it was held and from, how long it is, the
 * finger in the root's coordinates, the grid's top and bottom there, and the strip's chips by their bounds, so a drop is judged
 * by where it's let go. A drag under way isn't kept: rotated or killed, it's dropped.
 */
@Stable
internal class DayDrag {
    var slot by mutableStateOf<Slot?>(null)
        private set

    /** The slot lifted, by its key. */
    val key: String? get() = slot?.key

    /** Its length, in minutes. */
    var minutes by mutableIntStateOf(0)
        private set

    /** Its block's size, and where on the screen it was. */
    var size by mutableStateOf(IntSize.Zero)
        private set
    var origin by mutableStateOf(Offset.Zero)
        private set

    /** Where in the block it was held, and where the finger is now. */
    var grab by mutableStateOf(Offset.Zero)
        private set
    var finger by mutableStateOf(Offset.Zero)

    /** The grid's top and bottom on the screen. */
    var gridTop by mutableFloatStateOf(0f)
    var gridBottom by mutableFloatStateOf(0f)

    /** The strip's chips' bounds, each day's. */
    val chips = mutableStateMapOf<LocalDate, DayLayout.Box>()

    val active: Boolean get() = slot != null

    /** [slot] lifted, held at [at] on the screen, [grab] into its block of [size]: [minutes] long. */
    fun start(slot: Slot, size: IntSize, at: Offset, grab: Offset, minutes: Int) {
        this.slot = slot
        this.size = size
        this.grab = grab
        this.origin = at - grab
        this.minutes = minutes
        finger = at
    }

    /** Let go of, or lost (the gesture cancelled). */
    fun cancel() {
        slot = null
    }

    /** The strip's day under the finger; null off the strip. */
    fun dayUnder(): LocalDate? = DayLayout.dayAt(finger.x, finger.y, chips)

    /** The minute of the grid the lifted block's top is at, the grid scrolled [scroll], an hour [hourPx] tall; null off it. */
    fun minuteOnGrid(scroll: Float, hourPx: Float): Int? {
        if (finger.y !in gridTop..gridBottom || dayUnder() != null) return null
        return DayLayout.minuteAt(finger.y - grab.y - gridTop + scroll, hourPx)
    }

    /** Back where it was lifted from, and let go of: a drop that's refused, or does nothing. */
    suspend fun springBack() {
        val from = finger
        val to = origin + grab
        animate(0f, 1f, animationSpec = tween(200)) { t, _ -> finger = Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t) }
        cancel()
    }
}
