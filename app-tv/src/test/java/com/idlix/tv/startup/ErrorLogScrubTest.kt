package com.idlix.tv.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PII-scrub + ring-cap contract tests for ErrorLog.kt (owned by the
 * StartupRepair/ErrorLog slice: crash-handler chain, prefs quarantine,
 * Coil probe, migrations in StartupRepair; breadcrumb log here).
 *
 * Pure JVM: [ErrorLog.scrub] touches only kotlin.text.Regex, so it runs
 * without a device. File I/O paths (append/appendMessage/readHead/clear)
 * need a Context and stay uncovered — they are fail-silent by contract
 * (every write is guarded, logging never throws).
 */
class ErrorLogScrubTest {

    @Test
    fun ringCeilingConstants() {
        assertEquals(64 * 1024, ErrorLog.MAX_BYTES)
        assertEquals(2048, ErrorLog.ENTRY_CAP_CHARS)
        assertEquals("startup_error.log", ErrorLog.FILE_NAME)
    }

    @Test
    fun emailIsRedacted() {
        val out = ErrorLog.scrub("crash for user@example.com at boot")
        assertFalse(out.contains("user@example.com"))
        assertTrue(out.contains("[email-redacted]"))
    }

    @Test
    fun apiKeyIsRedacted() {
        val out = ErrorLog.scrub("resolve failed api_key=supersecret123 retry")
        assertFalse(out.contains("supersecret123"))
        assertTrue(out.contains("api_key="))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun passwordFormsAreRedacted() {
        val out = ErrorLog.scrub("login password: hunter2 done")
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun bearerTokenIsRedacted() {
        // secretRegex requires a [: =] separator after the key name
        // (ErrorLog.kt), so the token must follow "Bearer:"/"Bearer=".
        val out = ErrorLog.scrub("auth Bearer: abcdef12345 rejected")
        assertFalse(out.contains("abcdef12345"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun plainMessagesPassThrough() {
        val msg = "Gamepad present at launch: Xbox 360 Controller"
        assertEquals(msg, ErrorLog.scrub(msg))
    }

    @Test
    fun scrubKeepsNonSecretEqualsLines() {
        // 'size=42' has no secret key name, so the value must survive.
        val out = ErrorLog.scrub("downloaded size=42 ok")
        assertTrue(out.contains("size=42"))
    }
}
