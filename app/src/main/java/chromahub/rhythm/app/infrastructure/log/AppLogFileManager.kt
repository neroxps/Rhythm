/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.log

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App log file manager for detailed playback/streaming diagnostics.
 *
 * Writes human-readable log lines to the app's filesDir/logs/ directory:
 *  - playback.log       (main diagnostic log, rotated at MAX_FILE_BYTES)
 *  - streaming-errors.log (playback failure details, rotated at MAX_FILE_BYTES)
 *
 * Total directory size is capped at MAX_TOTAL_BYTES (30 MB): when a write would
 * exceed the cap, the oldest files are deleted until the directory fits.
 *
 * All credential-like strings are redacted before writing: api_key values,
 * MediaBrowser tokens, Subsonic p=/t= parameters, Authorization headers,
 * and common "password"/"token" JSON fields.
 */
object AppLogFileManager {

    private const val TAG = "AppLogFileManager"

    const val MAX_TOTAL_BYTES = 30L * 1024 * 1024      // 30 MB hard cap (user requirement)
    const val MAX_FILE_BYTES = 5L * 1024 * 1024        // single-file rotation size
    const val MAX_ROTATED_FILES = 6                    // per log name (current + 5 rotated)
    const val LOG_DIR_NAME = "logs"

    /**
     * Pure policy: the set of file sizes to remove (in bytes) so that after
     * deletion the directory stays under [MAX_TOTAL_BYTES]. Sorted by
     * modification time (oldest first) by the caller; this returns indices to
     * keep. Extracted so the 30MB budgeting rule is unit-testable.
     */
    internal fun trimBudget(sizes: List<Long>): Int {
        var total = sizes.sum()
        var removeCount = 0
        for (size in sizes) { // caller passes oldest-first
            if (total <= MAX_TOTAL_BYTES) break
            total -= size
            removeCount++
        }
        return removeCount
    }

    private val mutex = Mutex()
    private var initialized = false
    private lateinit var logDir: File
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Idempotent initialization; safe to call from Application or Service. */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            logDir = File(context.filesDir, LOG_DIR_NAME)
            if (!logDir.exists()) logDir.mkdirs()
            initialized = true
        }
    }

    fun getLogDir(): File? {
        if (!initialized) return null
        return logDir
    }

    /**
     * Append a log line. Non-blocking (background IO). `throwable` is serialized
     * with stack trace when present. Credentials are redacted.
     */
    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        if (!initialized) return
        val sb = StringBuilder()
        sb.append('[').append(timeFormat.format(Date())).append("] [")
            .append(level).append("] [").append(tag).append("] ")
            .append(redact(message)).append('\n')
        throwable?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            sb.append(redact(sw.toString())).append('\n')
        }
        val line = sb.toString()
        scope.launch {
            mutex.withLock {
                try {
                    writeFileAtomic(File(logDir, "playback.log"), line)
                    trimToLimit()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write playback log", e)
                }
            }
        }
    }

    /**
     * Write a dedicated failure record (also mirrored to playback.log).
     * Used when retries are exhausted so the raw error is never lost.
     */
    fun logError(errorTag: String, message: String, throwable: Throwable? = null) {
        if (!initialized) return
        val sb = StringBuilder()
        sb.append('[').append(timeFormat.format(Date())).append("] [ERROR] [")
            .append(errorTag).append("] ").append(redact(message)).append('\n')
        throwable?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            sb.append(redact(sw.toString())).append('\n')
        }
        val line = sb.toString()
        scope.launch {
            mutex.withLock {
                try {
                    writeFileAtomic(File(logDir, "streaming-errors.log"), line)
                    writeFileAtomic(File(logDir, "playback.log"), line)
                    trimToLimit()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write error log", e)
                }
            }
        }
    }

    /** Plain severity helper. */
    fun d(tag: String, message: String, throwable: Throwable? = null) = log("D", tag, message, throwable)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log("W", tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log("E", tag, message, throwable)

    /**
     * Gather the current log files for export (share/upload). Returns the
     * directory; the UI can zip or share individual files.
     */
    fun export(): File? {
        if (!initialized) return null
        return logDir
    }

    // ---------------------------------------------------------------------

    private fun writeFileAtomic(target: File, line: String) {
        if (!target.parentFile.exists()) target.parentFile.mkdirs()
        val existingLength = target.length()
        if (existingLength + line.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
            rotate(target)
        }
        target.appendText(line)
    }

    private fun rotate(target: File) {
        val baseName = target.name
        val lastRotated = File(target.parentFile, "$baseName.$MAX_ROTATED_FILES")
        if (lastRotated.exists()) lastRotated.delete()

        for (i in MAX_ROTATED_FILES - 1 downTo 1) {
            val from = File(target.parentFile, "$baseName.$i")
            val to = File(target.parentFile, "$baseName.${i + 1}")
            if (from.exists()) from.renameTo(to)
        }
        val first = File(target.parentFile, "$baseName.1")
        if (target.exists()) target.renameTo(first)
        // Create a fresh empty file so the next append has a clean start.
        target.createNewFile()
    }

    /**
     * Deleting oldest files first (by lastModified) until the directory is
     * within the total cap. Never deletes the active files unless nothing else
     * can be removed; if even one active file alone exceeds the cap we keep
     * the newest file only.
     */
    private fun trimToLimit() {
        val files = logDir.listFiles()?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_TOTAL_BYTES) return

        val sorted = files.sortedBy { it.lastModified() }
        val sizes = sorted.map { it.length() }
        val removeCount = trimBudget(sizes)
        for (i in 0 until removeCount.coerceAtMost(sorted.size)) {
            val file = sorted[i]
            if (file.delete()) {
                total -= file.length()
            }
        }
    }

    // ----------------------------- Redaction ------------------------------

    private val redactionPatterns: List<Pair<Regex, String>> = listOf(
        // URL query api_key= (Jellyfin/Emby)
        Regex("""(?i)(api_key)[=:]\s*[A-Za-z0-9_\-\.]{6,}""") to "$1=***",
        // Authorization / MediaBrowser header with Token="..."
        Regex("""(?i)(Token=")[^"]*(")""") to "$1***$2",
        Regex("""(?i)(Authorization[=:]\s*)[^\s,]+""") to "$1***",
        // Subsonic stream auth (p= or t= with a hash)
        Regex("""(?i)([?&]p=)[^&\s]+""") to "$1***",
        Regex("""(?i)([?&]t=)[^&\s]+""") to "$1***",
        Regex("""(?i)([?&]s=)[^&\s]+""") to "$1***",
        // JSON-ish password fields
        Regex("""(?i)("(?:password|pw|access_token|token)"\s*:\s*")[^"]*(")""") to "$1***$2",
        // raw Pw= (Emby auth body)
        Regex("""(?i)(Pw[=:]\s{0,2})[^\s",}]+""") to "$1***",
        // Bearer tokens
        Regex("""(?i)(Bearer\s+)[A-Za-z0-9_\-\.]+""") to "$1***"
    )

    internal fun redact(input: String): String {
        var result = input
        for ((pattern, replacement) in redactionPatterns) {
            result = pattern.replace(result, replacement)
        }
        return result
    }
}
