package com.idlix.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Edge-case tests for the pure [IdlixDirectApi] helpers: URL building,
 * gate-token `did` extraction, server-synchronized countdown math, and
 * Cloudflare-challenge classification. Offline-only, no network.
 */
class IdlixApiHelpersEdgeTest {

    private fun gateTokenFor(ownerJson: String): String {
        val first = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ownerJson.toByteArray(Charsets.UTF_8))
        return "$first.rest.sig"
    }

    // ---------------------------------------------------------- buildApiUrl

    @Test
    fun buildApiUrlTrimsParamValuesBeforeEncoding() {
        val url = buildApiUrl("https://z2.idlixku.com/", "api/search", mapOf("q" to "  batman  "))
        assertEquals("https://z2.idlixku.com/api/search?q=batman", url)
    }

    @Test
    fun buildApiUrlEncodesParamKeys() {
        val url = buildApiUrl("https://z2.idlixku.com/", "api/search", mapOf("a b" to "1"))
        assertEquals("https://z2.idlixku.com/api/search?a+b=1", url)
    }

    @Test
    fun buildApiUrlSlashMatrixCollapsesToSingleSeparator() {
        assertEquals("https://h/api/x", buildApiUrl("https://h", "api/x"))
        assertEquals("https://h/api/x", buildApiUrl("https://h/", "api/x"))
        assertEquals("https://h/api/x", buildApiUrl("https://h", "/api/x"))
        assertEquals("https://h/api/x", buildApiUrl("https://h/", "/api/x"))
        assertEquals("https://h/api/x?a=1", buildApiUrl("https://h", "/api/x", mapOf("a" to "1")))
    }

    @Test
    fun buildApiUrlEncodesUnicodeAndPlusValues() {
        val url = buildApiUrl("https://z2.idlixku.com/", "api/search", mapOf("q" to "café+a"))
        assertEquals("https://z2.idlixku.com/api/search?q=caf%C3%A9%2Ba", url)
    }

    @Test
    fun buildApiUrlOmitsEveryNullOrBlankParamButKeepsValidOnes() {
        val url = buildApiUrl(
            "https://z2.idlixku.com/",
            "api/movies",
            mapOf("a" to null, "b" to "", "c" to "   ", "d" to "x")
        )
        assertEquals("https://z2.idlixku.com/api/movies?d=x", url)
    }

    @Test
    fun buildApiUrlWithEmptyPathReturnsBaseRoot() {
        assertEquals("https://h/", buildApiUrl("https://h", ""))
        assertEquals("https://h/?a=1", buildApiUrl("https://h", "", mapOf("a" to "1")))
    }

    // ---------------------------------------------------------- extractDid

    @Test
    fun extractDidAcceptsUrlSafeBase64Payloads() {
        val token = gateTokenFor("""{"owner":"d:zz-9_8"}""")
        assertEquals("zz-9_8", extractDid(token))
    }

    @Test
    fun extractDidIgnoresNonFirstSegments() {
        val token = gateTokenFor("""{"owner":"d:abc123"}""") + ".!!!not-base64!!!"
        assertEquals("abc123", extractDid(token))
    }

    @Test
    fun extractDidReturnsNullForBarePayloadWithoutDots() {
        // "aGVsbG8" is valid base64url ("hello") but not a gate JSON object.
        assertNull(extractDid("aGVsbG8"))
    }

    @Test
    fun extractDidReturnsNullWhenOwnerLacksDevicePrefix() {
        assertNull(extractDid(gateTokenFor("""{"owner":"abc123"}""")))
    }

    @Test
    fun extractDidReturnsNullWhenDeviceIdIsEmpty() {
        assertNull(extractDid(gateTokenFor("""{"owner":"d:"}""")))
    }

    @Test
    fun extractDidPreservesDidValueVerbatim() {
        // No further validation: the claim Cookie echoes this byte-exact.
        assertEquals("abc 123", extractDid(gateTokenFor("""{"owner":"d:abc 123"}""")))
    }

    @Test
    fun extractDidReturnsNullForNonAsciiFirstSegment() {
        assertNull(extractDid("héllo.world.sig"))
    }

    // ---------------------------------------------------------- decodeBase64Url

    @Test
    fun decodeBase64UrlHandlesMissingPadding() {
        // "hello" is 7 chars: encoder drops the single "=" pad.
        assertEquals("hello", decodeBase64Url("aGVsbG8"))
        assertEquals("hello", decodeBase64Url("aGVsbG8="))
    }

    @Test
    fun decodeBase64UrlRejectsBlankAndIllegalInput() {
        assertNull(decodeBase64Url(""))
        assertNull(decodeBase64Url("   "))
        assertNull(decodeBase64Url("!!!"))
        assertNull(decodeBase64Url("a b"))
    }

    // ---------------------------------------------------------- countdownDelayMs

    @Test
    fun countdownDelayHitsCapExactlyAtBoundary() {
        // raw = unlockAt - serverNow + 500 == 16_000 stays 16_000.
        assertEquals(16_000L, countdownDelayMs(serverNow = 1_000L, unlockAt = 16_500L))
    }

    @Test
    fun countdownDelayCapsOneMillisecondOverBoundary() {
        assertEquals(16_000L, countdownDelayMs(serverNow = 1_000L, unlockAt = 16_501L))
    }

    @Test
    fun countdownDelayPassesThroughOneMillisecondUnderBoundary() {
        assertEquals(15_999L, countdownDelayMs(serverNow = 1_000L, unlockAt = 16_499L))
    }

    @Test
    fun countdownDelayMinimalPositiveWaitKeepsGrace() {
        assertEquals(501L, countdownDelayMs(serverNow = 1_000L, unlockAt = 1_001L))
    }

    @Test
    fun countdownDelayRealisticClockPairIsCapped() {
        val now = 1_700_000_000_000L
        assertEquals(16_000L, countdownDelayMs(serverNow = now, unlockAt = now + 30_000L))
        assertEquals(5_500L, countdownDelayMs(serverNow = now, unlockAt = now + 5_000L))
    }

    @Test
    fun countdownDelayFallsBackOnNegativeServerClock() {
        assertEquals(15_000L, countdownDelayMs(serverNow = -5L, unlockAt = 10_000L))
    }

    @Test
    fun countdownDelayZeroClocksFallBack() {
        assertEquals(15_000L, countdownDelayMs(serverNow = 0L, unlockAt = 0L))
    }

    @Test
    fun countdownDelayNegativeUnlockWithValidClockClaimsImmediately() {
        assertEquals(0L, countdownDelayMs(serverNow = 5_000L, unlockAt = -1L))
    }

    @Test
    fun countdownDelayOverflowWrapsToImmediateClaim() {
        // unlockAt - serverNow + 500 overflows Long near MAX_VALUE and the
        // coerceAtLeast(0) lands on 0. Server clocks never approach MAX_VALUE,
        // so this documents the wrap instead of asserting a wait.
        assertEquals(0L, countdownDelayMs(serverNow = 1L, unlockAt = Long.MAX_VALUE))
    }

    // ---------------------------------------------------------- isChallenge

    @Test
    fun isChallengeIgnoresNon403StatusesEvenWithMarkers() {
        val body = "<html><title>Just a moment...</title> cf-mitigated challenges.cloudflare.com</html>"
        assertFalse(isChallenge(200, body))
        assertFalse(isChallenge(301, body))
        assertFalse(isChallenge(500, body))
        // Classic Cloudflare interactive challenge answers 503: documents the
        // 403-only limitation — a 503 challenge reads as a generic HTTP error.
        assertFalse(isChallenge(503, body))
    }

    @Test
    fun isChallengeMatchesCaseInsensitively() {
        assertTrue(isChallenge(403, "CF-MITIGATED"))
        assertTrue(isChallenge(403, "CHALLENGES.CLOUDFLARE.COM/x"))
        assertTrue(isChallenge(403, "just a moment"))
    }

    @Test
    fun isChallengeMatchesBareMarkerWithSurroundingWhitespace() {
        assertTrue(isChallenge(403, "  \n Just a moment... \n  "))
    }

    @Test
    fun isChallengeRejectsPartialMarkersAndCleanBodies() {
        assertFalse(isChallenge(403, "Just a momen"))
        assertFalse(isChallenge(403, "cf-mitigate"))
        assertFalse(isChallenge(403, "   "))
        assertFalse(isChallenge(403, """{"error":"not found"}"""))
    }
}
