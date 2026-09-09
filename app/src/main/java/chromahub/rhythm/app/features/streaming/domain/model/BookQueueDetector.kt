/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.domain.model

/**
 * Decides whether a playback queue should run in "book mode": sequential
 * chapter order, no shuffle, resume-from-position.
 *
 * A queue is a book when:
 *  - any item is a server-reported book type (AudioBook / Book), or
 *  - any item's album id is in the user-managed audiobook set (user can
 *    manually mark an album as an audiobook in the album detail screen).
 */
object BookQueueDetector {

    /**
     * @param items items of the queue; each exposes an item type and album id.
     * @param isUserMarkedAudiobookAlbum resolver for user-marked album ids; may be null.
     */
    fun isBookQueue(
        items: List<BookCandidate>,
        isUserMarkedAudiobookAlbum: ((String) -> Boolean)? = null
    ): Boolean {
        return items.any { StreamingItemType.isBookType(it.itemType) } ||
            (isUserMarkedAudiobookAlbum != null && items.any { item ->
                item.albumId?.let { albumId -> albumId.isNotBlank() && isUserMarkedAudiobookAlbum(albumId) } == true
            })
    }

    /** Minimal view of a queue item for book detection. */
    data class BookCandidate(
        val itemType: String? = null,
        val albumId: String? = null
    )
}
