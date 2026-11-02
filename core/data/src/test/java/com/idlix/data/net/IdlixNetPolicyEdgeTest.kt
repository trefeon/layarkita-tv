package com.idlix.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge-case policy tests for [IdlixDomainResolver] and [CoreHttp] wiring:
 * allowlist boundaries, normalization seams (sub-paths, ports, queries),
 * Origin/Referer invariants, and the redirect/timeout split between the two
 * OkHttp clients. Offline-only: [IdlixDomainResolver.resolve] is never called.
 */
class IdlixNetPolicyEdgeTest {

    private fun normalize(location: String): String? {
        val method = IdlixDomainResolver::class.java
            .getDeclaredMethod("normalizeAndValidate", String::class.java)
        method.isAccessible = true
        return method.invoke(IdlixDomainResolver, location) as String?
    }

    // ---------------------------------------------------------- allowlist edges

    @Test
    fun canonicalAndFallbackHostsAreDistinct() {
        assertFalse(IdlixDomainResolver.CANONICAL_URL == IdlixDomainResolver.FALLBACK_URL)
        assertTrue(IdlixDomainResolver.CANONICAL_URL.contains("idlix.net"))
        assertTrue(IdlixDomainResolver.FALLBACK_URL.contains("idlixku.com"))
    }

    @Test
    fun httpCanonicalHostIsRejected() {
        assertNull(normalize("http://idlix.net/"))
        assertNull(normalize("http://z2.idlixku.com/"))
    }

    @Test
    fun ipHostIsRejected() {
        assertNull(normalize("https://1.2.3.4/"))
        assertNull(normalize("https://93.184.216.34/api/movies"))
    }

    @Test
    fun hyphenatedLookalikeHostDocumentsSubstringSeam() {
        // Companions IdlixDomainResolverTest.broadSubstringAllowlistDocumentsHardeningSeam:
        // host.contains("idlix") also admits hyphen-prefixed lookalikes.
        assertEquals("https://evil-idlixku.com/", normalize("https://evil-idlixku.com/"))
    }

    @Test
    fun bareIdlixSubdomainIsAccepted() {
        assertEquals("https://api.idlixku.com/", normalize("https://api.idlixku.com"))
    }

    // ---------------------------------------------------------- normalization seams

    @Test
    fun normalizePreservesSubPathDocumentsBasePoisoningSeam() {
        // A Location carrying a path is kept verbatim, so the persisted base
        // would shift every catalog call (base + "api/movies" under /path/).
        // Upstream answers from the root today, so this locks the seam.
        assertEquals("https://z2.idlixku.com/some/path/", normalize("https://z2.idlixku.com/some/path"))
    }

    @Test
    fun normalizePreservesQueryString() {
        assertEquals("https://z2.idlixku.com/?x=1/", normalize("https://z2.idlixku.com/?x=1"))
    }

    @Test
    fun normalizeAcceptsExplicitPort() {
        // OkHttp HttpUrl canonicalizes the https default port away
        // (IdlixDomainResolver.normalizeAndValidate returns url.toString()),
        // so :443 is accepted but not preserved in the normalized base.
        assertEquals("https://z2.idlixku.com/", normalize("https://z2.idlixku.com:443/"))
    }

    @Test
    fun normalizeTrimsWhitespaceViaHttpUrl() {
        // OkHttp's toHttpUrlOrNull() trims surrounding whitespace itself
        // (IdlixDomainResolver.normalizeAndValidate), so padded input is
        // accepted here; captureLocation's own trim() is belt-and-braces.
        assertEquals("https://z2.idlixku.com/", normalize("  https://z2.idlixku.com/  "))
    }

    @Test
    fun alreadyNormalizedBaseIsIdempotent() {
        val once = normalize("https://z2.idlixku.com")
        assertEquals("https://z2.idlixku.com/", once)
        assertEquals(once, normalize(once!!))
    }

    // ---------------------------------------------------------- Origin/Referer invariants

    @Test
    fun refererNeverCarriesADoubleSlash() {
        val referer = IdlixDomainResolver.referer()
        assertTrue(referer.endsWith("/"))
        assertFalse(referer.endsWith("//"))
    }

    @Test
    fun originAndRefererDeriveFromSameBase() {
        val base = IdlixDomainResolver.currentBaseUrl()
        assertEquals(base.trimEnd('/'), IdlixDomainResolver.origin())
        assertEquals(base.trimEnd('/') + "/", IdlixDomainResolver.referer())
    }

    // ---------------------------------------------------------- CoreHttp client split

    @Test
    fun noRedirectClientTimeoutsAreShorterThanMainClient() {
        val main = CoreHttp.okHttpClient
        val noRedirect = CoreHttp.noRedirectClient
        assertEquals(15_000, noRedirect.connectTimeoutMillis)
        assertEquals(15_000, noRedirect.readTimeoutMillis)
        assertEquals(15_000, noRedirect.writeTimeoutMillis)
        assertTrue(noRedirect.connectTimeoutMillis <= main.connectTimeoutMillis)
        assertTrue(noRedirect.readTimeoutMillis < main.readTimeoutMillis)
    }

    @Test
    fun apiClientsAreDistinctInstances() {
        assertNotSame(CoreHttp.okHttpClient, CoreHttp.noRedirectClient)
    }

    @Test
    fun mainClientFollowsRedirectsByDefault() {
        // Locks the split: only the resolver client observes Location itself;
        // catalog calls follow OkHttp defaults.
        assertTrue(CoreHttp.okHttpClient.followRedirects)
        assertTrue(CoreHttp.okHttpClient.followSslRedirects)
    }
}
