/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.domain.model

/**
 * Server-side per-item user data (e.g. Jellyfin/Emby UserData object).
 *
 * Used to decide where an audiobook chapter resumes: the server stores the
 * last reported playback position per item, so the client can continue from
 * the exact chapter and offset without any local state.
 */
data class StreamingUserData(
    /** Resume position in milliseconds (converted from ticks). */
    val positionMs: Long = 0L,
    /** Played percentage 0..100, when provided by the server. */
    val playedPercentage: Double = 0.0,
    /** True when the server marks the item as fully played. */
    val played: Boolean = false,
    /** Last play timestamp in epoch millis, when provided. */
    val lastPlayedMs: Long = 0L,
    /** True when the item was played at least once. */
    val hasPlayed: Boolean = false
) {
    /**
     * Whether the server considers this item finished (>=95% played).
     *
     * NOTE: We deliberately ignore the `played` flag. Emby/Jellyfin sets
     * `Played=true` as soon as an item has playback history (even paused or
     * partially listened), so treating it as "finished" would skip chapters
     * the user is still in the middle of (e.g. a book session paused at 16%).
     * Only the percentage threshold marks a chapter as complete.
     */
    fun isEffectivelyFinished(): Boolean =
        playedPercentage >= 95.0
}

/** Item types the server can report that represent audiobook/audio-book content. */
object StreamingItemType {
    const val AUDIO_BOOK = "AudioBook"
    const val BOOK = "Book"

    fun isBookType(itemType: String?): Boolean {
        if (itemType.isNullOrBlank()) return false
        return itemType.equals(AUDIO_BOOK, ignoreCase = true) ||
            itemType.equals(BOOK, ignoreCase = true)
    }
}
