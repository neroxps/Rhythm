/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingUserDataTest {

    private fun userData(
        positionMs: Long = 0L,
        playedPercentage: Double = 0.0,
        played: Boolean = false,
        hasPlayed: Boolean = false
    ) = StreamingUserData(
        positionMs = positionMs,
        playedPercentage = playedPercentage,
        played = played,
        lastPlayedMs = 0L,
        hasPlayed = hasPlayed
    )

    @Test
    fun isEffectivelyFinished_falseWhenPartialProgress() {
        assertFalse(userData(positionMs = 60_000, playedPercentage = 10.0).isEffectivelyFinished())
    }

    @Test
    fun isEffectivelyFinished_trueAt95Percent() {
        assertTrue(userData(positionMs = 600_000, playedPercentage = 95.0).isEffectivelyFinished())
    }

    @Test
    fun isEffectivelyFinished_trueWhenPlayedFlagSet() {
        assertTrue(userData(positionMs = 10_000, playedPercentage = 50.0, played = true).isEffectivelyFinished())
    }

    @Test
    fun isEffectivelyFinished_falseWhenFresh() {
        assertFalse(userData(positionMs = 0L, playedPercentage = 0.0).isEffectivelyFinished())
    }
}
