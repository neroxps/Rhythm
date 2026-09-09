/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.domain.model

/**
 * Pure policy for picking where an audiobook resumes, based on per-chapter
 * server UserData. Extracted from the repository so it can be unit tested.
 *
 * Candidates are chapters that:
 *  - are not finished by the server (<95% played);
 *  - carry a saved position (>0).
 *
 * Selection order:
 *  1. the most recently played candidate wins — this makes "continue"
 *     follow the user's last listening session even when other chapters
 *     left progress behind (e.g. from shuffled/random playback);
 *  2. if no chapter carries a LastPlayedDate, the highest-index candidate
 *     wins (classic "furthest chapter" heuristic);
 *  3. otherwise position 0 of chapter index 0 (book restart).
 */
object BookResumeSelector {

    fun select(chapters: List<StreamingSong>): ResumeTarget {
        if (chapters.isEmpty()) return ResumeTarget(0, 0L)

        // 1) Oldest-first bias fallback: highest index with saved position.
        var fallbackIndex = -1
        var fallbackPositionMs = 0L

        // 2) Most-recently-played candidate.
        var bestIndex = -1
        var bestPositionMs = 0L
        var bestPlayedMs = Long.MIN_VALUE

        for (index in chapters.indices.reversed()) {
            val chapter = chapters[index]
            val userData = chapter.userData ?: continue
            if (userData.isEffectivelyFinished()) continue
            if (userData.positionMs <= 0L) continue

            if (fallbackIndex < 0) {
                fallbackIndex = index
                fallbackPositionMs = userData.positionMs
            }

            val lastPlayed = userData.lastPlayedMs
            if (lastPlayed > bestPlayedMs) {
                bestPlayedMs = lastPlayed
                bestIndex = index
                bestPositionMs = userData.positionMs
            }
        }

        if (bestIndex >= 0 && bestPlayedMs > 0L) {
            return ResumeTarget(bestIndex, bestPositionMs)
        }
        if (fallbackIndex >= 0) {
            return ResumeTarget(fallbackIndex, fallbackPositionMs)
        }
        return ResumeTarget(0, 0L)
    }

    data class ResumeTarget(
        val chapterIndex: Int,
        val positionMs: Long
    )
}
