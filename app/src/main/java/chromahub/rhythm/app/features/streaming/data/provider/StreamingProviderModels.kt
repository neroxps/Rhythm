/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.provider

/**
 * Lightweight song model used by provider API clients before mapping to UI/domain models.
 */
data class ProviderSong(
    val providerId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkUrl: String? = null,
    val albumId: String? = null,
    val albumArtist: String? = null,
    val isFavorite: Boolean = false,
    val trackNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val codec: String? = null,
    val itemType: String? = null,
    val parentIndexNumber: Int? = null,
    val userData: ProviderUserData? = null
) {
    /** True when this item is an audiobook / audio-book chapter. */
    fun isBookType(): Boolean =
        chromahub.rhythm.app.features.streaming.domain.model.StreamingItemType.isBookType(itemType)
}

/**
 * Server-side user data mirror (subset of Jellyfin/Emby UserData) used for
 * audiobook resume: ticks are converted to milliseconds by the mapper.
 */
data class ProviderUserData(
    val playbackPositionTicks: Long = 0L,
    val playedPercentage: Double = 0.0,
    val played: Boolean = false,
    val hasPlayed: Boolean = false
) {
    /** Resume position in milliseconds (ticks -> ms). */
    val positionMs: Long
        get() = playbackPositionTicks.coerceAtLeast(0L) / 10_000L
}

/**
 * Lightweight playlist model used by provider API clients before mapping to UI/domain models.
 */
data class ProviderPlaylist(
    val providerId: String,
    val name: String,
    val description: String? = null,
    val artworkUrl: String? = null,
    val songCount: Int = 0,
    val owner: String? = null,
    val isPublic: Boolean = true
)

/**
 * Lightweight album model used by provider API clients before mapping to UI/domain models.
 */
data class ProviderAlbum(
    val providerId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val songCount: Int = 0,
    val year: Int? = null,
    val description: String? = null,
    val itemType: String? = null
) {
    /** True when this album is an audiobook (folder/collection of book type). */
    fun isBookType(): Boolean =
        chromahub.rhythm.app.features.streaming.domain.model.StreamingItemType.isBookType(itemType)
}

/**
 * Lightweight artist model used by provider API clients before mapping to UI/domain models.
 */
data class ProviderArtist(
    val providerId: String,
    val name: String,
    val artworkUrl: String? = null,
    val songCount: Int = 0,
    val albumCount: Int = 0,
    val description: String? = null
)

/**
 * Result of a successful provider connection/authentication.
 */
data class ProviderConnectionResult(
    val displayName: String,
    val serverUrl: String = ""
)
