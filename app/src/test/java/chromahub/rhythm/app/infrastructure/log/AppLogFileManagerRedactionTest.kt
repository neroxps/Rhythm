/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the credential redaction rules. No Android context needed:
 * [AppLogFileManager.redact] is internal and operates purely on strings.
 */
class AppLogFileManagerRedactionTest {

    private val manager = AppLogFileManager

    @Test
    fun redact_masksApiKeyQueryParameter() {
        val out = manager.redact("https://server/Audio/1/universal?api_key=AbCdEf1234567890")
        assertTrue(out.contains("api_key=***"))
        assertFalse(out.contains("AbCdEf1234567890"))
    }

    @Test
    fun redact_masksMediaBrowserTokenHeader() {
        val out = manager.redact(
            "Authorization: MediaBrowser Client=\"Rhythm\", Token=\"super-secret-token-123\""
        )
        assertFalse(out.contains("super-secret-token-123"))
        assertTrue(out.contains("Token=\"***\""))
    }

    @Test
    fun redact_masksSubsonicAuthParameters() {
        val out = manager.redact("https://server/rest/stream.view?u=user&p=enc%3Aabcd&t=abcdef123456&s=salt123")
        assertFalse(out.contains("abcdef123456"))
        assertFalse(out.contains("salt123"))
        assertTrue(out.contains("p=***"))
        assertTrue(out.contains("t=***"))
        assertTrue(out.contains("s=***"))
    }

    @Test
    fun redact_masksJsonPasswordAndTokenFields() {
        val out = manager.redact("""{"Username":"admin","Pw":"hunter2","token":"abc123"}""")
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("abc123"))
        assertTrue(out.contains("\"Pw\":\"***\""))
        assertTrue(out.contains("\"token\":\"***\""))
    }

    @Test
    fun redact_masksBearerTokens() {
        val out = manager.redact("header: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature")
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9.payload.signature"))
        assertTrue(out.contains("Bearer ***"))
    }

    @Test
    fun redact_keepsPlainLogText() {
        val out = manager.redact("Streaming recovery succeeded after 2 attempts for JELLYFIN::item-42")
        assertTrue(out.contains("Streaming recovery succeeded"))
        assertTrue(out.contains("item-42"))
    }
}
