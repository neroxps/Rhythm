/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.domain.model

import chromahub.rhythm.app.core.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookChapterOrderTest {

    private fun chapter(
        name: String,
        parent: Int? = null,
        index: Int? = null
    ) = StreamingSong(
        id = "JELLYFIN::$name",
        title = name,
        artist = "Author",
        album = "Book",
        duration = 100L,
        artworkUri = null,
        sourceType = SourceType.JELLYFIN,
        streamingUrl = null,
        previewUrl = null,
        trackNumber = index,
        parentIndexNumber = parent,
        itemType = "AudioBook"
    )

    @Test
    fun sortsByParentIndexThenChapterIndex() {
        val chapters = listOf(
            chapter("第2章", index = 2),
            chapter("第1章", index = 1),
            chapter("楔子", index = 0)
        )
        val sorted = AudiobookChapterOrder.sorted(chapters)
        assertEquals(listOf("楔子", "第1章", "第2章"), sorted.map { it.title })
    }

    @Test
    fun sortsPartsBeforeChapters() {
        val chapters = listOf(
            chapter("第2部份之第1章", parent = 2, index = 1),
            chapter("第1部份之第2章", parent = 1, index = 2),
            chapter("第1部份之第1章", parent = 1, index = 1)
        )
        val sorted = AudiobookChapterOrder.sorted(chapters)
        assertEquals(
            listOf("第1部份之第1章", "第1部份之第2章", "第2部份之第1章"),
            sorted.map { it.title }
        )
    }

    @Test
    fun missingIndexFallsBackToTitleOrder() {
        val chapters = listOf(
            chapter("Beta"),
            chapter("Alpha")
        )
        val sorted = AudiobookChapterOrder.sorted(chapters)
        assertEquals(listOf("Alpha", "Beta"), sorted.map { it.title })
    }

    @Test
    fun stableOrderWhenAllEqual() {
        val chapters = listOf(
            chapter("Same", index = 1),
            chapter("Same", index = 1)
        )
        val sorted = AudiobookChapterOrder.sorted(chapters)
        assertEquals(2, sorted.size)
    }
}
