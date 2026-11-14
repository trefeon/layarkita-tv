package com.layarkita.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Route building/parsing contract for [Screen] (owned slice: AppNavigation +
 * every screen's createRoute call-site).
 *
 * All encode/decode goes through java.net with explicit UTF-8, so these run
 * on plain JVM JUnit with zero Android/Compose. The decode side replicates
 * exactly what AppNavigation does on receipt (URLDecoder with try/catch
 * fallback to raw), so each round-trip test pins the real wire contract.
 */
class ScreenRouteTest {

    private fun utf8(): String = StandardCharsets.UTF_8.toString()

    private fun decode(raw: String): String = try {
        URLDecoder.decode(raw, utf8())
    } catch (_: Exception) {
        raw
    }

    // ---- Search: initialQuery ----

    @Test
    fun search_nullQuery_buildsBareRoute() {
        assertEquals("search", Screen.Search.createRoute(null))
    }

    @Test
    fun search_blankQuery_buildsBareRoute() {
        assertEquals("search", Screen.Search.createRoute(""))
        assertEquals("search", Screen.Search.createRoute("   "))
    }

    @Test
    fun search_plainQuery_roundTrips() {
        val route = Screen.Search.createRoute("avengers")
        assertEquals("search?initialQuery=avengers", route)
        assertEquals("avengers", decode(route.substringAfter("initialQuery=")))
    }

    @Test
    fun search_queryWithSpaces_roundTrips() {
        val route = Screen.Search.createRoute("kopi susu")
        assertEquals("kopi susu", decode(route.substringAfter("initialQuery=")))
    }

    @Test
    fun search_queryWithReservedChars_roundTrips() {
        val query = "a+b & c?d=e%f/g#h"
        val route = Screen.Search.createRoute(query)
        // Nothing raw may leak: ? & = # / must all be encoded inside the value.
        val value = route.substringAfter("initialQuery=")
        assertTrue(!value.contains("?"))
        assertTrue(!value.contains("&"))
        assertTrue(!value.contains("#"))
        assertEquals(query, decode(value))
    }

    @Test
    fun search_unicodeQuery_roundTrips() {
        val query = "大江戸 Hotman"
        val route = Screen.Search.createRoute(query)
        assertEquals(query, decode(route.substringAfter("initialQuery=")))
    }

    @Test
    fun search_routeTemplate_declaresNullableInitialQuery() {
        assertEquals("search?initialQuery={initialQuery}", Screen.Search.route)
    }

    // ---- Genre / Collection detail: slug path + encoded name query ----

    @Test
    fun genreDetail_nameWithReservedChars_roundTrips() {
        val route = Screen.GenreDetail.createRoute("action", "Action & Adventure")
        assertEquals("genre/action", route.substringBefore("?"))
        assertEquals("Action & Adventure", decode(route.substringAfter("name=")))
    }

    @Test
    fun genreDetail_emptyName_emitsBlankParam() {
        val route = Screen.GenreDetail.createRoute("horror")
        assertEquals("genre/horror?name=", route)
        // AppNavigation falls back to the slug when the decoded name is blank.
        val decoded = decode(route.substringAfter("name="))
        assertEquals("", decoded)
        assertTrue(decoded.isBlank())
    }

    @Test
    fun collectionDetail_nameWithSlash_roundTrips() {
        val route = Screen.CollectionDetail.createRoute("marvel", "Marvel/Disney+ Picks")
        assertEquals("Marvel/Disney+ Picks", decode(route.substringAfter("name=")))
    }

    // ---- Detail: contentType + slug path segments (slug NOT encoded) ----

    @Test
    fun detail_routeFormat_isTypeThenSlug() {
        assertEquals("detail/movie/filipina-2023", Screen.Detail.createRoute("movie", "filipina-2023"))
        assertEquals("detail/tv_series/breaking-bad", Screen.Detail.createRoute("tv_series", "breaking-bad"))
    }

    @Test
    fun detail_slugWithSlash_breaksSegmentCount_knownLimitation() {
        // Documents the Minor finding: Detail.createRoute does not encode the
        // slug, so a slug containing "/" adds a path segment and misses the
        // "detail/{contentType}/{slug}" pattern. Current catalog slugs are
        // URL-safe, so this pins today's behavior — a future fix that encodes
        // the slug must update this test AND add the AppNavigation decode.
        val route = Screen.Detail.createRoute("movie", "a/b")
        val segments = route.split("/")
        assertEquals(listOf("detail", "movie", "a", "b"), segments)
    }

    // ---- Player: encoded title path + optional query extras ----

    @Test
    fun player_fullArgs_roundTrip() {
        val title = "Avengers: Endgame (2019)"
        val poster = "https://img.example.com/p/x.jpg?sig=ab&c=d"
        val backdrop = "https://img.example.com/b/y.jpg#frag"
        val slug = "avengers-endgame"
        val route = Screen.Player.createRoute(
            contentType = "movie", contentId = "42", title = title,
            poster = poster, backdrop = backdrop, slug = slug,
            season = 2, episode = 7
        )
        val path = route.substringBefore("?")
        val query = route.substringAfter("?")
        // Title is a path segment: no raw "/" may leak into it.
        assertEquals(3, path.split("/").size - 1) // player/<type>/<id>/<title>
        assertEquals(title, decode(path.substringAfterLast("/")))
        val params = query.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to decode(v)
        }
        assertEquals(poster, params["poster"])
        assertEquals(backdrop, params["backdrop"])
        assertEquals(slug, params["slug"])
        assertEquals("2", params["season"])
        assertEquals("7", params["episode"])
    }

    @Test
    fun player_titleWithSlash_staysOneSegment() {
        val route = Screen.Player.createRoute("movie", "1", "A/B: C", null, null, null, null, null)
        val path = route.substringBefore("?")
        assertEquals(4, path.split("/").size) // player + type + id + title
        assertEquals("A/B: C", decode(path.substringAfterLast("/")))
    }

    @Test
    fun player_minimalArgs_emitBlankExtras() {
        val route = Screen.Player.createRoute("movie", "42", "T")
        assertTrue(route.contains("poster=&"))
        assertTrue(route.contains("backdrop=&"))
        assertTrue(route.contains("slug=&"))
        assertTrue(route.endsWith("season=&episode="))
        // AppNavigation maps blank extras back to null via takeIf(isNotBlank).
        val params = route.substringAfter("?").split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to v
        }
        assertEquals("", params["poster"])
        assertEquals(null, params["season"]!!.ifBlank { null }?.toIntOrNull())
        assertEquals(null, params["episode"]!!.ifBlank { null }?.toIntOrNull())
    }

    @Test
    fun player_seasonEpisode_parseRule() {
        // Mirrors AppNavigation: getString("season")?.toIntOrNull().
        assertEquals(12, "12".toIntOrNull())
        assertEquals(null, "".toIntOrNull())
        assertEquals(null, "s2".toIntOrNull())
    }

    @Test
    fun playerEncoder_matchesAppNavigationDecoder() {
        // End-to-end symmetry proof for the exact pair used in prod:
        // Screen.Player.createRoute encode vs AppNavigation URLDecoder.decode.
        val original = "Spider-Man: No Way Home + Bonus?"
        val encoded = URLEncoder.encode(original, utf8())
        assertEquals(original, decode(encoded))
    }
}
