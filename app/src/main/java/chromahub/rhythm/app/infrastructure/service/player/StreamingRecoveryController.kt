/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.service.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import chromahub.rhythm.app.features.streaming.data.repository.StreamingMusicRepositoryImpl
import chromahub.rhythm.app.features.streaming.di.StreamingMusicModule
import chromahub.rhythm.app.infrastructure.log.AppLogFileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * Automatic recovery for streaming playback interruptions ("断流重连").
 *
 * When the ExoPlayer reports an IO error for a `streaming://` (or `SERVICE::id`)
 * track, the controller:
 *  1. stops the player,
 *  2. invalidates the cached streaming URL,
 *  3. resolves a fresh URL from the repository,
 *  4. rebuilds the media item with the same mediaId/title/artist metadata,
 *  5. seeks back to the position where playback stopped,
 *  6. resumes playback.
 *
 * Up to [MAX_ATTEMPTS] (3) attempts with exponential backoff (1s/2s/4s). After
 * the third failure the failure is published via [PlaybackErrorCenter] with
 * full detail and written to the app log directory (30MB cap, redacted).
 *
 * The controller is deliberately agnostic about the actual player: the service
 * passes in lambdas that operate on the master player, so it also composes
 * with the crossfade engine (the service cancels any pending transition first).
 */
@OptIn(UnstableApi::class)
class StreamingRecoveryController(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {

    companion object {
        private const val TAG = "StreamingRecovery"
        const val MAX_ATTEMPTS = StreamingRecoveryPolicy.MAX_ATTEMPTS
        private const val RETRY_POSITION_SLACK_MS = StreamingRecoveryPolicy.RETRY_POSITION_SLACK_MS
    }

    enum class State { IDLE, ATTEMPTING, FAILED }

    private val mutex = Mutex()
    private var state: State = State.IDLE
    private var attempt: Int = 0
    private var lastRunMediaId: String? = null

    /**
     * Handle an ExoPlayer error for the given track.
     *
     * @param playerCallbacks operations on the master player. All called on the
     *   controller scope (Main).
     * @return true when the error was handled by this controller (retry started
     *   or failure published); false when it is a non-streaming/local error that
     *   should fall through to the default error handling.
     */
    fun handlePlayerError(
        error: PlaybackException,
        mediaItem: MediaItem?,
        playerCallbacks: RecoveryPlayerCallbacks
    ): Boolean {
        val mediaId = mediaItem?.mediaId ?: return false
        val isStreaming = isStreamingMediaId(mediaId)
        if (!isStreaming) {
            Log.d(TAG, "Non-streaming error, skipping recovery: mediaId=$mediaId")
            return false
        }

        scope.launch {
            mutex.withLock {
                if (state == State.ATTEMPTING) {
                    Log.w(TAG, "Recovery already running; ignoring concurrent error for $mediaId")
                    return@withLock
                }
                attempt = 0
                state = State.ATTEMPTING
                PlaybackErrorCenter.setRetrying(true)
                val success = runRecoveryLoop(error, mediaId, playerCallbacks)
                state = if (success) State.IDLE else State.FAILED
                PlaybackErrorCenter.setRetrying(false)
                if (!success) {
                    publishFailure(error, mediaId, playerCallbacks)
                }
            }
        }
        return true
    }

    /** True when UI should be able to retry after a failure. */
    fun isFailed(): Boolean = state == State.FAILED

    /** Reset to IDLE (e.g. user tapped "Retry" or dismissed the dialog). */
    fun reset() {
        scope.launch {
            mutex.withLock {
                state = State.IDLE
                attempt = 0
                PlaybackErrorCenter.setRetrying(false)
            }
        }
    }

    /**
     * Manually re-trigger recovery for the current track (UI "Retry"). Keeps
     * any previous failure state until the retry actually succeeds.
     */
    fun retryCurrent(playerCallbacks: RecoveryPlayerCallbacks) {
        scope.launch {
            mutex.withLock {
                if (state == State.ATTEMPTING) return@withLock
                state = State.ATTEMPTING
                attempt = 0
                PlaybackErrorCenter.setRetrying(true)
                val trackId = playerCallbacks.currentTrackId() ?: return@withLock
                val success = runRecoveryLoop(null, trackId, playerCallbacks)
                state = if (success) State.IDLE else State.FAILED
                PlaybackErrorCenter.setRetrying(false)
                if (success) PlaybackErrorCenter.clear()
            }
        }
    }

    private suspend fun runRecoveryLoop(
        error: PlaybackException?,
        mediaId: String,
        callbacks: RecoveryPlayerCallbacks
    ): Boolean {
        val positionMs = callbacks.currentPositionMs()
        Log.d(TAG, "Streaming recovery: mediaId=$mediaId position=${positionMs}ms")

        while (attempt < StreamingRecoveryPolicy.MAX_ATTEMPTS && coroutineContext.isActive) {
            attempt++
            val backoff = StreamingRecoveryPolicy.backoffForAttempt(attempt)
            Log.d(TAG, "Streaming recovery attempt $attempt/${StreamingRecoveryPolicy.MAX_ATTEMPTS} (backoff ${backoff}ms)")
            PlaybackErrorCenter.setRetrying(true)

            delay(backoff)

            val recovered = try {
                resolveAndRebuild(mediaId, positionMs, callbacks)
            } catch (e: Exception) {
                Log.e(TAG, "Recovery attempt $attempt failed", e)
                AppLogFileManager.e(TAG, "Recovery attempt $attempt/${StreamingRecoveryPolicy.MAX_ATTEMPTS} failed", e)
                false
            }

            if (recovered) {
                Log.d(TAG, "Streaming recovery succeeded after $attempt attempt(s)")
                AppLogFileManager.d(TAG, "Streaming recovery succeeded after $attempt attempt(s) for $mediaId")
                return true
            }
        }

        Log.e(TAG, "Streaming recovery exhausted after ${StreamingRecoveryPolicy.MAX_ATTEMPTS} attempts")
        AppLogFileManager.e(TAG, "Streaming recovery exhausted after ${StreamingRecoveryPolicy.MAX_ATTEMPTS} attempts for $mediaId")
        return false
    }

    private suspend fun resolveAndRebuild(
        mediaId: String,
        positionMs: Long,
        callbacks: RecoveryPlayerCallbacks
    ): Boolean {
        return try {
            val context = callbacks.context()
            val repository = StreamingMusicModule.provideStreamingMusicRepository(context) as? StreamingMusicRepositoryImpl
            val trackId = decodeTrackId(mediaId)

            // Invalidate the stale cached URL and resolve a fresh one.
            repository?.invalidateStreamingUrlCache(mediaId)
            val freshUrl = repository?.getStreamingUrl(mediaId)
                ?: callbacks.resolveStreamingUrl(mediaId)

            if (freshUrl.isNullOrBlank()) {
                Log.w(TAG, "Recovery: no usable stream URL for $mediaId")
                AppLogFileManager.w(TAG, "Recovery: no usable stream URL for $mediaId")
                return false
            }

            AppLogFileManager.d(TAG, "Recovery: resolved fresh URL for $mediaId (trackId=$trackId)")

            // Stop before mutating the timeline to avoid ExoPlayer invariant errors.
            callbacks.stopPlayer()

            val oldItem = callbacks.currentMediaItem()
            val metadata = oldItem?.mediaMetadata
            val freshItem = MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(Uri.parse(freshUrl))
                .setMediaMetadata(metadata ?: androidx.media3.common.MediaMetadata.Builder().build())
                .build()

            callbacks.replaceCurrentItem(freshItem, frameSeekPosition(positionMs))

            callbacks.play()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Recovery resolveAndRebuild failed", e)
            AppLogFileManager.e(TAG, "Recovery resolveAndRebuild failed", e)
            false
        }
    }

    private fun publishFailure(error: PlaybackException?, mediaId: String, callbacks: RecoveryPlayerCallbacks) {
        val trackTitle = callbacks.currentMediaItem()?.mediaMetadata?.title?.toString() ?: mediaId
        val errorCode = error?.errorCode ?: PlaybackException.ERROR_CODE_UNSPECIFIED
        val errorCodeName = errorCodeName(errorCode)

        val causeChain = buildCauseChain(error)
        val attemptCount = attempt

        val failure = PlaybackErrorCenter.PlaybackFailure(
            trackId = mediaId,
            trackTitle = trackTitle,
            errorCode = errorCode,
            errorCodeName = errorCodeName,
            errorMessage = error?.message ?: "Streaming recovery failed after $attemptCount attempts",
            causeChain = causeChain,
            attemptCount = attemptCount,
            isForStreaming = true
        )
        PlaybackErrorCenter.publish(failure)
        AppLogFileManager.logError(
            TAG,
            "Streaming playback failed after $attemptCount attempts | track=$trackTitle | id=$mediaId | " +
                "code=$errorCodeName($errorCode) | message=${error?.message} | cause=$causeChain"
        )
    }

    private fun buildCauseChain(error: PlaybackException?): String {
        if (error == null) return "n/a"
        val sb = StringBuilder()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            if (depth > 0) sb.append(" <- ")
            sb.append(current.javaClass.simpleName).append(": ").append(current.message ?: "null message")
            current = current.cause
            depth++
        }
        return sb.toString()
    }

    private fun errorCodeName(code: Int): String {
        return when (code) {
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "IO_UNSPECIFIED"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "IO_NETWORK_CONNECTION_FAILED"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "IO_NETWORK_CONNECTION_TIMEOUT"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "IO_FILE_NOT_FOUND"
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "IO_NO_PERMISSION"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "IO_BAD_HTTP_STATUS"
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "IO_INVALID_HTTP_CONTENT_TYPE"
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "IO_READ_POSITION_OUT_OF_RANGE"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "PARSING_CONTAINER_UNSUPPORTED"
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "PARSING_MANIFEST_UNSUPPORTED"
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "PARSING_MANIFEST_MALFORMED"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "DECODER_INIT_FAILED"
            PlaybackException.ERROR_CODE_DECODING_FAILED -> "DECODING_FAILED"
            PlaybackException.ERROR_CODE_UNSPECIFIED -> "UNSPECIFIED"
            else -> "ERROR_$code"
        }
    }

    private fun frameSeekPosition(positionMs: Long): Long {
        return StreamingRecoveryPolicy.frameSeekPosition(positionMs)
    }

    private fun isStreamingMediaId(mediaId: String): Boolean {
        return StreamingRecoveryPolicy.isStreamingMediaId(mediaId)
    }

    private fun decodeTrackId(mediaId: String): String {
        // streaming://track/<id> → <id>
        return if (mediaId.startsWith("streaming://")) {
            Uri.parse(mediaId).lastPathSegment ?: mediaId
        } else {
            mediaId
        }
    }

    /** Operations the service provides so the controller stays player-agnostic. */
    interface RecoveryPlayerCallbacks {
        fun context(): android.content.Context
        fun currentMediaItem(): MediaItem?
        fun currentPositionMs(): Long
        fun stopPlayer()
        fun replaceCurrentItem(item: MediaItem, seekPositionMs: Long)
        fun play()
        fun currentTrackId(): String?
        fun resolveStreamingUrl(songId: String): String?
    }
}
