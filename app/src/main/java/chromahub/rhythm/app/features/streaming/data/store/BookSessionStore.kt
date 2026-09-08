/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.store

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Local snapshot of the last audiobook playback session.
 *
 * Written whenever an audiobook chapter changes (or its progress advances
 * periodically) so the home screen can offer "Continue listening" even after
 * the app process is killed. The authoritative position still comes from the
 * server (Emby/Jellyfin UserData); this store only remembers WHICH book and
 * chapter so the next launch can reopen it and ask the server for the offset.
 */
class BookSessionStore(context: Context) {

    companion object {
        private const val TAG = "BookSessionStore"
        private const val PREFS_NAME = "streaming_book_session"
        private const val KEY_BOOK_SESSION = "last_book_session_json"

        const val MAX_BOOK_SESSIONS = 10
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Snapshot of one audiobook "continue listening" card.
     */
    data class BookSession(
        val bookId: String,
        val title: String,
        val author: String = "",
        val artworkUrl: String? = null,
        val chapterId: String = "",
        val chapterIndex: Int = 0,
        val positionMs: Long = 0L,
        val updatedAtMs: Long = System.currentTimeMillis(),
        val isAudiobook: Boolean = true
    )

    private data class SessionList(val sessions: MutableList<BookSession> = mutableListOf())

    /** Most recently updated book session (single "continue" card). */
    fun getLastBook(): BookSession? {
        val raw = prefs.getString(KEY_BOOK_SESSION, null) ?: return null
        return try {
            parseSession(JSONObject(raw))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse saved book session", e)
            null
        }
    }

    /** All saved sessions, newest first (bounded list for a possible grid UI). */
    fun getBookSessions(): List<BookSession> {
        val raw = prefs.getString(KEY_BOOK_SESSION, null) ?: return emptyList()
        return try {
            val json = JSONObject(raw)
            val arr = json.optJSONArray("sessions")
            if (arr == null) {
                listOfNotNull(parseSession(json)).sortedByDescending { it.updatedAtMs }
            } else {
                buildList {
                    for (i in 0 until arr.length()) {
                        parseSession(arr.optJSONObject(i))?.let { add(it) }
                    }
                }.sortedByDescending { it.updatedAtMs }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse saved book sessions", e)
            emptyList()
        }
    }

    /** Upsert the book session (kept sorted newest-first, capped). */
    fun saveSession(session: BookSession) {
        try {
            val sessions = getBookSessions().toMutableList()
            sessions.removeAll { it.bookId == session.bookId }
            sessions.add(0, session)
            val capped = sessions.take(MAX_BOOK_SESSIONS)
            val arr = org.json.JSONArray()
            capped.forEach { arr.put(toJson(it)) }
            prefs.edit()
                .putString(KEY_BOOK_SESSION, JSONObject().put("sessions", arr).toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save book session", e)
        }
    }

    /** Remove a book (e.g. when the user dismisses the continue card). */
    fun removeBook(bookId: String) {
        try {
            val sessions = getBookSessions().toMutableList()
            sessions.removeAll { it.bookId == bookId }
            val arr = org.json.JSONArray()
            sessions.forEach { arr.put(toJson(it)) }
            prefs.edit()
                .putString(KEY_BOOK_SESSION, JSONObject().put("sessions", arr).toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove book session", e)
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_BOOK_SESSION).apply()
    }

    private fun parseSession(json: JSONObject?): BookSession? {
        if (json == null) return null
        val bookId = json.optString("bookId", "").takeIf { it.isNotBlank() } ?: return null
        val title = json.optString("title", "").takeIf { it.isNotBlank() } ?: return null
        return BookSession(
            bookId = bookId,
            title = title,
            author = json.optString("author", ""),
            artworkUrl = json.optString("artworkUrl", "").takeIf { it.isNotBlank() },
            chapterId = json.optString("chapterId", ""),
            chapterIndex = json.optInt("chapterIndex", 0),
            positionMs = json.optLong("positionMs", 0L),
            updatedAtMs = json.optLong("updatedAtMs", System.currentTimeMillis()),
            isAudiobook = json.optBoolean("isAudiobook", true)
        )
    }

    private fun toJson(session: BookSession): JSONObject {
        return JSONObject().apply {
            put("bookId", session.bookId)
            put("title", session.title)
            put("author", session.author)
            put("artworkUrl", session.artworkUrl ?: "")
            put("chapterId", session.chapterId)
            put("chapterIndex", session.chapterIndex)
            put("positionMs", session.positionMs)
            put("updatedAtMs", session.updatedAtMs)
            put("isAudiobook", session.isAudiobook)
        }
    }
}
