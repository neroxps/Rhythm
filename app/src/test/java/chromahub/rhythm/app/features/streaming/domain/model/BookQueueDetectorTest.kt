/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookQueueDetectorTest {

    private fun item(type: String? = null, albumId: String? = null) =
        BookQueueDetector.BookCandidate(itemType = type, albumId = albumId)

    @Test
    fun `server AudioBook type triggers book mode`() {
        assertTrue(
            BookQueueDetector.isBookQueue(
                listOf(item(type = "AudioBook"), item(type = "Audio"))
            )
        )
    }

    @Test
    fun `server Book type triggers book mode`() {
        assertTrue(
            BookQueueDetector.isBookQueue(
                listOf(item(type = "Book"))
            )
        )
    }

    @Test
    fun `case insensitive server types trigger book mode`() {
        assertTrue(BookQueueDetector.isBookQueue(listOf(item(type = "audiobook"))))
        assertTrue(BookQueueDetector.isBookQueue(listOf(item(type = "book"))))
        assertTrue(BookQueueDetector.isBookQueue(listOf(item(type = "AUDIOBOOK"))))
    }

    @Test
    fun `user marked audiobook album triggers book mode`() {
        val marked = setOf("album-123")
        assertTrue(
            BookQueueDetector.isBookQueue(
                items = listOf(item(type = "MusicAlbum", albumId = "album-123")),
                isUserMarkedAudiobookAlbum = { it in marked }
            )
        )
    }

    @Test
    fun `unmarked album does not trigger book mode`() {
        val marked = setOf("album-123")
        assertFalse(
            BookQueueDetector.isBookQueue(
                items = listOf(item(type = "MusicAlbum", albumId = "album-456")),
                isUserMarkedAudiobookAlbum = { it in marked }
            )
        )
    }

    @Test
    fun `plain music without book hints is not a book`() {
        assertFalse(
            BookQueueDetector.isBookQueue(
                items = listOf(item(type = "Audio", albumId = "album-999")),
                isUserMarkedAudiobookAlbum = { false }
            )
        )
    }

    @Test
    fun `null type and no marker is not a book`() {
        assertFalse(BookQueueDetector.isBookQueue(listOf(item())))
    }

    @Test
    fun `blank album id never matches marker`() {
        val marked = setOf("")
        assertFalse(
            BookQueueDetector.isBookQueue(
                items = listOf(item(type = "MusicAlbum", albumId = "  ")),
                isUserMarkedAudiobookAlbum = { it in marked }
            )
        )
    }
}
