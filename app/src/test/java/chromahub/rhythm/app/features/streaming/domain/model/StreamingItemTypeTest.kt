/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingItemTypeTest {

    @Test
    fun isBookType_acceptsAudioBook() {
        assertTrue(StreamingItemType.isBookType("AudioBook"))
        assertTrue(StreamingItemType.isBookType("AUDIOBOOK"))
        assertTrue(StreamingItemType.isBookType("audiobook"))
    }

    @Test
    fun isBookType_acceptsBook() {
        assertTrue(StreamingItemType.isBookType("Book"))
        assertTrue(StreamingItemType.isBookType("BOOK"))
    }

    @Test
    fun isBookType_rejectsAudioAndMusicAlbum() {
        assertFalse(StreamingItemType.isBookType("Audio"))
        assertFalse(StreamingItemType.isBookType("MusicAlbum"))
        assertFalse(StreamingItemType.isBookType("MusicArtist"))
    }

    @Test
    fun isBookType_rejectsNullOrBlank() {
        assertFalse(StreamingItemType.isBookType(null))
        assertFalse(StreamingItemType.isBookType(""))
        assertFalse(StreamingItemType.isBookType("   "))
    }
}
