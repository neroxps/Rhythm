/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.domain.model

import chromahub.rhythm.app.core.domain.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class BookResumeSelectorTest {

    private fun chapter(
        name: String,
        positionMs: Long = 0L,
        playedPercentage: Double = 0.0,
        played: Boolean = false
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
        itemType = "AudioBook",
        userData = if (positionMs > 0 || playedPercentage > 0 || played) {
            StreamingUserData(
                positionMs = positionMs,
                playedPercentage = playedPercentage,
                played = played,
                hasPlayed = played
            )
        } else null
    )

    @Test
    fun resumesAtLastIncompleteChapter() {
        val chapters = listOf(
            chapter("ch1", positionMs = 600_000, playedPercentage = 95.0), // finished
            chapter("ch2", positionMs = 30_000, playedPercentage = 10.0),  // last partial → target
            chapter("ch3", positionMs = 0L)
        )
        val target = BookResumeSelector.select(chapters)
        assertEquals(1, target.chapterIndex)
        assertEquals(30_000L, target.positionMs)
    }

    @Test
    fun finishesBook_restartsFromBeginning() {
        val chapters = listOf(
            chapter("ch1", positionMs = 600_000, playedPercentage = 100.0, played = true),
            chapter("ch2", positionMs = 650_000, playedPercentage = 97.0)
        )
        val target = BookResumeSelector.select(chapters)
        assertEquals(0, target.chapterIndex)
        assertEquals(0L, target.positionMs)
    }

    @Test
    fun noProgress_startsAtChapterZero() {
        val chapters = listOf(
            chapter("ch1"),
            chapter("ch2")
        )
        val target = BookResumeSelector.select(chapters)
        assertEquals(0, target.chapterIndex)
        assertEquals(0L, target.positionMs)
    }

    @Test
    fun emptyList_returnsOrigin() {
        val target = BookResumeSelector.select(emptyList())
        assertEquals(0, target.chapterIndex)
        assertEquals(0L, target.positionMs)
    }

    @Test
    fun gappedProgressPicksIncompleteMiddleChapter() {
        // ch1 finished, ch2 partial, ch3 never played → ch2 is correct resume point
        val chapters = listOf(
            chapter("ch1", positionMs = 900_000, playedPercentage = 96.0),
            chapter("ch2", positionMs = 150_000, playedPercentage = 40.0),
            chapter("ch3")
        )
        val target = BookResumeSelector.select(chapters)
        assertEquals(1, target.chapterIndex)
        assertEquals(150_000L, target.positionMs)
    }
}
