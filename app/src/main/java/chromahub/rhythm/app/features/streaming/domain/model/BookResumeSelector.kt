/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.domain.model

/**
 * Pure policy for picking where an audiobook resumes, based on per-chapter
 * server UserData. Extracted from the repository so it can be unit tested.
 */
object BookResumeSelector {

    /**
     * Choose the resume target:
     *  - the last (highest index) chapter with a saved position the server does
     *    not consider finished (>=95%) wins;
     *  - otherwise position 0 of the first chapter (book restart).
     */
    fun select(chapters: List<StreamingSong>): ResumeTarget {
        if (chapters.isEmpty()) return ResumeTarget(0, 0L)

        for (index in chapters.indices.reversed()) {
            val chapter = chapters[index]
            val userData = chapter.userData ?: continue
            if (userData.isEffectivelyFinished()) continue
            if (userData.positionMs <= 0L) continue
            return ResumeTarget(index, userData.positionMs)
        }
        return ResumeTarget(0, 0L)
    }

    data class ResumeTarget(
        val chapterIndex: Int,
        val positionMs: Long
    )
}
