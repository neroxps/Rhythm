/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.service.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingRecoveryPolicyTest {

    @Test
    fun maxAttempts_isThree() {
        assertEquals(3, StreamingRecoveryPolicy.MAX_ATTEMPTS)
    }

    @Test
    fun backoff_sequenceIsOneTwoFourSeconds() {
        assertEquals(1_000L, StreamingRecoveryPolicy.backoffForAttempt(1))
        assertEquals(2_000L, StreamingRecoveryPolicy.backoffForAttempt(2))
        assertEquals(4_000L, StreamingRecoveryPolicy.backoffForAttempt(3))
    }

    @Test
    fun backoff_clampsToLastValueBeyondMax() {
        assertEquals(4_000L, StreamingRecoveryPolicy.backoffForAttempt(4))
    }

    @Test
    fun frameSeekPosition_rewindsBySlack() {
        assertEquals(5_000L, StreamingRecoveryPolicy.frameSeekPosition(7_000L))
    }

    @Test
    fun frameSeekPosition_neverNegative() {
        assertEquals(0L, StreamingRecoveryPolicy.frameSeekPosition(500L))
        assertEquals(0L, StreamingRecoveryPolicy.frameSeekPosition(0L))
    }

    @Test
    fun isStreamingMediaId_detectsStreamingSchemes() {
        assertTrue(StreamingRecoveryPolicy.isStreamingMediaId("streaming://track/JELLYFIN::abc"))
        assertTrue(StreamingRecoveryPolicy.isStreamingMediaId("JELLYFIN::abc123"))
        assertTrue(StreamingRecoveryPolicy.isStreamingMediaId("SUBSONIC::xyz"))
        assertFalse(StreamingRecoveryPolicy.isStreamingMediaId("/sdcard/Music/song.mp3"))
        assertFalse(StreamingRecoveryPolicy.isStreamingMediaId(""))
    }
}
