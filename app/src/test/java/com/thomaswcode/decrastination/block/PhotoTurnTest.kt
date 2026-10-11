package com.thomaswcode.decrastination.block

import androidx.exifinterface.media.ExifInterface
import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoTurnTest {
    /** Where [turn] puts a point of the photo as stored (a unit square, y down): mirrored first, then turned clockwise. */
    private fun place(turn: PhotoChecks.Turn, x: Int, y: Int): Pair<Int, Int> {
        val (mx, my) = if (turn.mirrored) (1 - x) to y else x to y
        return when (turn.degrees) {
            90 -> (1 - my) to mx
            180 -> (1 - mx) to (1 - my)
            270 -> my to (1 - mx)
            else -> mx to my
        }
    }

    @Test
    fun `every EXIF orientation stands the photo upright`() {
        // Where the stored photo's top-left and top-right corners belong, as the EXIF standard puts its
        // first row and column (orientation 6: the first row is the right-hand side, the first column the top).
        val upright = mapOf(
            ExifInterface.ORIENTATION_NORMAL to listOf(0 to 0, 1 to 0),
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to listOf(1 to 0, 0 to 0),
            ExifInterface.ORIENTATION_ROTATE_180 to listOf(1 to 1, 0 to 1),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to listOf(0 to 1, 1 to 1),
            ExifInterface.ORIENTATION_TRANSPOSE to listOf(0 to 0, 0 to 1),
            ExifInterface.ORIENTATION_ROTATE_90 to listOf(1 to 0, 1 to 1),
            ExifInterface.ORIENTATION_TRANSVERSE to listOf(1 to 1, 1 to 0),
            ExifInterface.ORIENTATION_ROTATE_270 to listOf(0 to 1, 0 to 0),
        )
        for ((orientation, corners) in upright) {
            val turn = PhotoChecks.turnOf(orientation)
            assertEquals(corners, listOf(place(turn, 0, 0), place(turn, 1, 0)), "orientation $orientation")
        }
        assertEquals(PhotoChecks.Turn.NONE, PhotoChecks.turnOf(ExifInterface.ORIENTATION_UNDEFINED))
    }
}
