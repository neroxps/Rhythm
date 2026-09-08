/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.service.player

/**
 * Pure policy for the streaming recovery (断流重连) loop. Kept as a separate
 * immutable object so the retry sequence and seek-frame rules can be unit
 * tested without an Android runtime.
 */
object StreamingRecoveryPolicy {

    /** Maximum automatic retry attempts before surfacing the failure to the UI. */
    const val MAX_ATTEMPTS = 3

    /** Backoff between attempts (ms): 1s → 2s → 4s. */
    val BACKOFF_MS: LongArray = longArrayOf(1_000L, 2_000L, 4_000L)

    /**
     * Small safety margin rewind when re-seeking after a network blip, so the
     * audio does not cut mid-word right at the interruption point.
     */
    const val RETRY_POSITION_SLACK_MS = 2_000L

    fun backoffForAttempt(attempt: Int): Long {
        return BACKOFF_MS.getOrNull(attempt - 1) ?: BACKOFF_MS.last()
    }

    /** Clamp the resumed position: never negative, never beyond the slack rewind. */
    fun frameSeekPosition(positionMs: Long): Long {
        return (positionMs - RETRY_POSITION_SLACK_MS).coerceAtLeast(0L)
    }

    fun isStreamingMediaId(mediaId: String): Boolean {
        if (mediaId.isBlank()) return false
        return mediaId.startsWith("streaming://") || mediaId.contains("::")
    }
}
