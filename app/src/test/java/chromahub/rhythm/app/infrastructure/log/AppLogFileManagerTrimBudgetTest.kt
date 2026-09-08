/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.log

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogFileManagerTrimBudgetTest {

    @Test
    fun underCap_removesNothing() {
        val sizes = listOf(1L * 1024 * 1024, 2L * 1024 * 1024)
        assertEquals(0, AppLogFileManager.trimBudget(sizes))
    }

    @Test
    fun overCap_removesOldestUntilUnderLimit() {
        // 6 files × 6MB = 36MB > 30MB → oldest 2 files (12MB) must go.
        val sizes = List(6) { 6L * 1024 * 1024 }
        assertEquals(2, AppLogFileManager.trimBudget(sizes))
    }

    @Test
    fun singleHugeFile_keepsNewestAlone() {
        val sizes = listOf(40L * 1024 * 1024, 1L * 1024 * 1024)
        // Oldest 40MB file removed; remaining 1MB fits.
        assertEquals(1, AppLogFileManager.trimBudget(sizes))
    }

    @Test
    fun emptyList_removesNothing() {
        assertEquals(0, AppLogFileManager.trimBudget(emptyList()))
    }
}
