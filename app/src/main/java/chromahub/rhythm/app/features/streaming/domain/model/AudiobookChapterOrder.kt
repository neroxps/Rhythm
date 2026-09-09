/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.domain.model

/**
 * Pure chapter-ordering policy for audiobook (book mode) playback.
 *
 * Books play strictly in server order: ParentIndexNumber (disc/part) first,
 * then IndexNumber (chapter), then title as a tie-breaker. Keeping this
 * comparator pure lets it be unit tested without an Android runtime.
 */
object AudiobookChapterOrder {

    fun comparator(): Comparator<StreamingSong> {
        return Comparator { a, b ->
            val parentCompare = (a.parentIndexNumber ?: 0).compareTo(b.parentIndexNumber ?: 0)
            if (parentCompare != 0) return@Comparator parentCompare
            val indexCompare = (a.trackNumber ?: 0).compareTo(b.trackNumber ?: 0)
            if (indexCompare != 0) return@Comparator indexCompare
            a.title.compareTo(b.title, ignoreCase = true)
        }
    }

    fun sorted(chapters: List<StreamingSong>): List<StreamingSong> {
        return chapters.sortedWith(comparator())
    }
}
