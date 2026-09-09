/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.service.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory hub that carries detailed playback failure information from the
 * playback service (where ExoPlayer errors surface) to the UI (MusicViewModel
 * dialog). The message shown to the user is fully descriptive:
 * error code, cause chain, affected track/book, and attempt count.
 */
object PlaybackErrorCenter {

    data class PlaybackFailure(
        val trackId: String,
        val trackTitle: String,
        val errorCode: Int,
        val errorCodeName: String,
        val errorMessage: String,
        val causeChain: String,
        val attemptCount: Int,
        val isForStreaming: Boolean,
        val timestampMs: Long = System.currentTimeMillis()
    )

    private val _currentFailure = MutableStateFlow<PlaybackFailure?>(null)
    val currentFailure: StateFlow<PlaybackFailure?> = _currentFailure.asStateFlow()

    /** Set when the service is about to retry (UI can show "Retrying…"). */
    private val _retrying = MutableStateFlow(false)
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    fun publish(failure: PlaybackFailure) {
        _currentFailure.value = failure
    }

    fun setRetrying(value: Boolean) {
        _retrying.value = value
    }

    fun clear() {
        _currentFailure.value = null
        _retrying.value = false
    }
}
