package com.layarkita.data.repository

import com.layarkita.data.api.IdlixDirectApi
import com.layarkita.data.api.countdownDelayMs
import com.layarkita.data.api.extractDid
import com.layarkita.data.cache.DirectSourceCache
import com.layarkita.data.model.ClaimResponse
import com.layarkita.data.model.CollectionDetailResponse
import com.layarkita.data.model.CollectionListResponse
import com.layarkita.data.model.EpisodeItem
import com.layarkita.data.model.EpisodeResponse
import com.layarkita.data.model.GenreListResponse
import com.layarkita.data.model.MovieDetail
import com.layarkita.data.model.MovieListResponse
import com.layarkita.data.model.PlayInfoResponse
import com.layarkita.data.model.RedeemResponse
import com.layarkita.data.model.SearchResponse
import com.layarkita.data.model.Season
import com.layarkita.data.model.SeasonDetail
import com.layarkita.data.model.SeriesDetail
import com.layarkita.data.model.SeriesListResponse
import com.layarkita.data.model.SubtitleTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

/**
 * Playback-gate tests for [IdlixRepository]: countdown clamp edges, the
 * claim-retry race, byte-exact `did` echo on claim/redeem, the tv_series
 * normalization chain, and proof that playback is never cached.
 *
 * All network is a fake [IdlixDirectApi] subclass (the class is `open` for
 * exactly this); no Android framework, no timers beyond one ~1s countdown.
 */
class IdlixPlaybackTest {

    private fun gateTokenFor(ownerJson: String): String {
        val first = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ownerJson.toByteArray(Charsets.UTF_8))
        return "$first.rest.sig"
    }

    private fun validClaim() = ClaimResponse(
        kind = "pentos",
        claim = "CLAIM-1",
        redeemUrl = "https://cdn/redeem",
        title = "Claimed Title",
        durationSec = 90L,
        videoId = "v1"
    )

    private fun validRedeem() = RedeemResponse(
        url = "https://cdn/x.m3u8",
        subtitles = listOf(SubtitleTrack(lang = "en", label = "English", path = "https://cdn/en.vtt"))
    )

    private class PlaybackFake(
        var playInfo: PlayInfoResponse = PlayInfoResponse(
            gateToken = "gate",
            serverNow = 5_000L,
            unlockAt = 5_000L
        ),
        var claimResponder: (attempt: Int) -> ClaimResponse = { validStaticClaim() },
        var redeemResponder: (redeemUrl: String, claim: String) -> RedeemResponse =
            { _, _ -> validStaticRedeem() },
        var seriesDetail: SeriesDetail = SeriesDetail(),
        var episodeFallback: EpisodeItem? = null,
        var failPlayInfo: IOException? = null
    ) : IdlixDirectApi() {
        var playInfoCalls = 0
        var claimCalls = 0
        var redeemCalls = 0
        var playTypeSeen: String? = null
        var playIdSeen: String? = null
        var claimCookieSeen: String? = null
        var redeemUrlSeen: String? = null
        var redeemClaimSeen: String? = null
        var episodeSeasonSeen: Int? = null
        var episodeNumberSeen: Int? = null

        override suspend fun getPlayInfo(type: String, id: String): PlayInfoResponse {
            playInfoCalls++
            playTypeSeen = type
            playIdSeen = id
            failPlayInfo?.let { throw it }
            return playInfo
        }

        override suspend fun claimSession(gateToken: String, cookie: String?): ClaimResponse {
            claimCalls++
            claimCookieSeen = cookie
            return claimResponder(claimCalls)
        }

        override suspend fun redeem(redeemUrl: String, claim: String): RedeemResponse {
            redeemCalls++
            redeemUrlSeen = redeemUrl
            redeemClaimSeen = claim
            return redeemResponder(redeemUrl, claim)
        }

        override suspend fun getSeriesDetail(slug: String): SeriesDetail = seriesDetail

        override suspend fun getEpisodeDetail(slug: String, season: Int, episode: Int): EpisodeResponse {
            episodeSeasonSeen = season
            episodeNumberSeen = episode
            return EpisodeResponse(episode = episodeFallback)
        }

        companion object {
            fun validStaticClaim() = ClaimResponse(
                kind = "pentos",
                claim = "CLAIM-1",
                redeemUrl = "https://cdn/redeem",
                title = "Claimed Title",
                durationSec = 90L,
                videoId = "v1"
            )

            fun validStaticRedeem() = RedeemResponse(
                url = "https://cdn/x.m3u8",
                subtitles = listOf(
                    SubtitleTrack(lang = "en", label = "English", path = "https://cdn/en.vtt")
                )
            )
        }
    }

    // ---------------------------------------------------------- countdown edges

    @Test
    fun countdownAddsGraceAndCapsAtSixteenSeconds() {
        assertEquals(600L, countdownDelayMs(serverNow = 1_000L, unlockAt = 1_100L))
        assertEquals(10_500L, countdownDelayMs(serverNow = 1_000L, unlockAt = 11_000L))
        // Exact cap edge: 15_500 + 500 == 16_000 stays, anything above clamps.
        assertEquals(16_000L, countdownDelayMs(serverNow = 1L, unlockAt = 15_501L))
        assertEquals(15_999L, countdownDelayMs(serverNow = 1L, unlockAt = 15_500L))
        assertEquals(16_000L, countdownDelayMs(serverNow = 1L, unlockAt = 100_001L))
    }

    @Test
    fun countdownSkipsWaitWhenAlreadyUnlocked() {
        assertEquals(0L, countdownDelayMs(serverNow = 5_000L, unlockAt = 5_000L))
        assertEquals(0L, countdownDelayMs(serverNow = 9_000L, unlockAt = 1_000L))
    }

    @Test
    fun countdownFallsBackOnUnusableClock() {
        assertEquals(15_000L, countdownDelayMs(serverNow = 0L, unlockAt = 10_000L))
        assertEquals(15_000L, countdownDelayMs(serverNow = -5L, unlockAt = 10_000L))
    }

    @Test
    fun alreadyUnlockedStreamClaimsWithOnlyFinalTick() = runBlocking {
        val fake = PlaybackFake()
        val repo = IdlixRepository(api = fake)
        val seen = mutableListOf<Int>()
        val res = repo.resolveStream("movie", "cid-unlocked", "T", onCountdown = { seen += it })
        assertTrue(res.isSuccess)
        assertEquals(listOf(0), seen)
        assertEquals("https://cdn/x.m3u8", res.getOrNull()?.hlsMasterUrl)
    }

    @Test
    fun shortCountdownEmitsSecondsThenZero() = runBlocking {
        val fake = PlaybackFake(
            playInfo = PlayInfoResponse(gateToken = "gate", serverNow = 1_000_000L, unlockAt = 1_002_000L)
        )
        val repo = IdlixRepository(api = fake)
        val seen = mutableListOf<Int>()
        val res = repo.resolveStream("movie", "cid-count", "T", onCountdown = { seen += it })
        assertTrue(res.isSuccess)
        // delayMs = 2000 + 500 grace = 2500 -> secondsLeft = 2 -> [2, 1], then 0.
        assertEquals(listOf(2, 1, 0), seen)
    }

    // ---------------------------------------------------------- claim race

    @Test
    fun claimRaceRetriesUntilValid() = runBlocking {
        val fake = PlaybackFake(claimResponder = { attempt ->
            if (attempt < 3) ClaimResponse(kind = "pending", claim = "") else validClaim()
        })
        val repo = IdlixRepository(api = fake)
        val res = repo.resolveStream("movie", "cid-race", "Race Title")
        assertTrue(res.isSuccess)
        assertEquals(3, fake.claimCalls)
        assertEquals("https://cdn/x.m3u8", res.getOrNull()?.hlsMasterUrl)
    }

    @Test
    fun claimTransportErrorsAreRetried() = runBlocking {
        val fake = PlaybackFake(claimResponder = { attempt ->
            if (attempt < 3) throw IOException("flaky") else validClaim()
        })
        val repo = IdlixRepository(api = fake)
        val res = repo.resolveStream("movie", "cid-flaky", "T")
        assertTrue(res.isSuccess)
        assertEquals(3, fake.claimCalls)
    }

    @Test
    fun claimNeverValidFailsHonestlyAfterTenAttempts() = runBlocking {
        val fake = PlaybackFake(claimResponder = { ClaimResponse(kind = "pending", claim = "") })
        val repo = IdlixRepository(api = fake)
        val res = repo.resolveStream("movie", "cid-noclaim", "T")
        assertTrue(res.isFailure)
        assertEquals(10, fake.claimCalls)
        assertTrue(res.exceptionOrNull()?.message?.contains("mengklaim") == true)
        assertEquals(0, fake.redeemCalls)
    }

    // ---------------------------------------------------------- did echo

    @Test
    fun didCookieIsByteExact() = runBlocking {
        val token = gateTokenFor("""{"owner":"d:dev42","exp":999}""")
        assertEquals("dev42", extractDid(token))
        val fake = PlaybackFake(
            playInfo = PlayInfoResponse(gateToken = token, serverNow = 1L, unlockAt = 1L)
        )
        val repo = IdlixRepository(api = fake)
        assertTrue(repo.resolveStream("movie", "cid-did", "T").isSuccess)
        assertEquals("did=dev42", fake.claimCookieSeen)
    }

    @Test
    fun missingDidSendsNoCookie() = runBlocking {
        val fake = PlaybackFake(
            playInfo = PlayInfoResponse(gateToken = "not-a-token", serverNow = 1L, unlockAt = 1L)
        )
        val repo = IdlixRepository(api = fake)
        assertTrue(repo.resolveStream("movie", "cid-nodid", "T").isSuccess)
        assertNull(fake.claimCookieSeen)
    }

    // ---------------------------------------------------------- gate chain

    @Test
    fun blankGateTokenFailsWithoutClaim() = runBlocking {
        val fake = PlaybackFake(
            playInfo = PlayInfoResponse(gateToken = "", serverNow = 1L, unlockAt = 1L)
        )
        val repo = IdlixRepository(api = fake)
        val res = repo.resolveStream("movie", "cid-notoken", "T")
        assertTrue(res.isFailure)
        assertEquals(0, fake.claimCalls)
    }

    @Test
    fun playInfoFailureSurfaces() = runBlocking {
        val fake = PlaybackFake(failPlayInfo = IOException("down"))
        val repo = IdlixRepository(api = fake)
        val res = repo.resolveStream("movie", "cid-down", "T")
        assertTrue(res.isFailure)
        assertEquals("down", res.exceptionOrNull()?.message)
    }

    @Test
    fun blankRedeemUrlUsesFallbackThenFailsOnEmptyMaster() = runBlocking {
        val fake = PlaybackFake(
            claimResponder = { validClaim().copy(redeemUrl = "") },
            redeemResponder = { _, _ -> RedeemResponse(url = "") }
        )
        val repo = IdlixRepository(api = fake)
        val res = repo.resolveStream("movie", "cid-fallback", "T")
        assertTrue(res.isFailure)
        assertEquals("https://e2e.majorplay.net/api/play", fake.redeemUrlSeen)
        assertTrue(res.exceptionOrNull()?.message?.contains("master playlist") == true)
    }

    @Test
    fun blankClaimTitleFallsBackToContentTitle() = runBlocking {
        val fake = PlaybackFake(claimResponder = { validClaim().copy(title = "") })
        val repo = IdlixRepository(api = fake)
        val res = repo.resolveStream("movie", "cid-title", "My Title")
        assertTrue(res.isSuccess)
        assertEquals("My Title", res.getOrNull()?.title)
    }

    @Test
    fun existingIndonesianSubtitleSkipsSubdlBackfill() = runBlocking {
        val fake = PlaybackFake(
            redeemResponder = { _, _ ->
                RedeemResponse(
                    url = "https://cdn/x.m3u8",
                    subtitles = listOf(SubtitleTrack(lang = "id", label = "Indonesian", path = "https://cdn/id.vtt"))
                )
            }
        )
        val repo = IdlixRepository(api = fake)
        val res = repo.resolveStream("movie", "cid-subs", "T")
        assertTrue(res.isSuccess)
        // No applicationContext on JVM, so no SubDL call can sneak in either way.
        assertEquals(1, res.getOrNull()?.subtitles?.size)
        assertEquals("id", res.getOrNull()?.subtitles?.first()?.lang)
    }

    // ---------------------------------------------------------- series normalization

    @Test
    fun seriesNormalizesToDefaultSeasonFirstEpisode() = runBlocking {
        val fake = PlaybackFake(
            seriesDetail = SeriesDetail(
                slug = "show",
                defaultSeason = SeasonDetail(
                    seasonNumber = 1,
                    episodes = listOf(EpisodeItem(id = "ep9", episodeNumber = 1, name = "Pilot"))
                )
            )
        )
        val repo = IdlixRepository(api = fake)
        val res = repo.resolveStream("tv_series", "cid-series", "Show")
        assertTrue(res.isSuccess)
        assertEquals("episode", fake.playTypeSeen)
        assertEquals("ep9", fake.playIdSeen)
    }

    @Test
    fun seriesFallsBackToFirstSeasonEpisodeOne() = runBlocking {
        val fake = PlaybackFake(
            seriesDetail = SeriesDetail(
                slug = "show",
                seasons = listOf(Season(seasonNumber = 2, name = "S2"))
            ),
            episodeFallback = EpisodeItem(id = "ep21", episodeNumber = 1, name = "S2E1")
        )
        val repo = IdlixRepository(api = fake)
        val res = repo.resolveStream("tv_series", "cid-seriesfb", "Show")
        assertTrue(res.isSuccess)
        assertEquals(2, fake.episodeSeasonSeen)
        assertEquals(1, fake.episodeNumberSeen)
        assertEquals("ep21", fake.playIdSeen)
    }

    @Test
    fun seriesWithoutEpisodesFailsHonestly() = runBlocking {
        val fake = PlaybackFake(seriesDetail = SeriesDetail(slug = "empty"))
        val repo = IdlixRepository(api = fake)
        val res = repo.resolveStream("tv_series", "cid-empty", "Show")
        assertTrue(res.isFailure)
        assertEquals(0, fake.playInfoCalls)
        assertTrue(res.exceptionOrNull()?.message?.contains("episode") == true)
    }

    // ---------------------------------------------------------- never cached + key isolation

    @Test
    fun playbackIsNeverCached() = runBlocking {
        val fake = PlaybackFake()
        val repo = IdlixRepository(api = fake)
        assertTrue(repo.resolveStream("movie", "cid-nocache", "T").isSuccess)
        assertTrue(repo.resolveStream("movie", "cid-nocache", "T").isSuccess)
        assertEquals(2, fake.playInfoCalls)
        assertEquals(2, fake.claimCalls)
        assertEquals(2, fake.redeemCalls)
    }

    private class CatalogCountingFake : IdlixDirectApi() {
        var movieCalls = 0
        var seriesCalls = 0
        var movieDetailCalls = 0
        var seriesDetailCalls = 0
        var episodeDetailCalls = 0
        var genreCalls = 0
        var collectionCalls = 0
        var searchCalls = 0

        override suspend fun getMovies(page: Int, limit: Int, sort: String?, genre: String?) =
            MovieListResponse().also { movieCalls++ }

        override suspend fun getSeries(page: Int, limit: Int, sort: String?, genre: String?) =
            SeriesListResponse().also { seriesCalls++ }

        override suspend fun getMovieDetail(slug: String) =
            MovieDetail(id = "m-$slug", title = "M", slug = slug).also { movieDetailCalls++ }

        override suspend fun getSeriesDetail(slug: String) =
            SeriesDetail(id = "s-$slug", title = "S", slug = slug).also { seriesDetailCalls++ }

        override suspend fun getEpisodeDetail(slug: String, season: Int, episode: Int) =
            EpisodeResponse().also { episodeDetailCalls++ }

        override suspend fun getGenres() = GenreListResponse().also { genreCalls++ }
        override suspend fun getCollections() = CollectionListResponse().also { collectionCalls++ }
        override suspend fun getCollectionDetail(slug: String) = CollectionDetailResponse()
        override suspend fun search(query: String) = SearchResponse().also { searchCalls++ }
    }

    @Test
    fun catalogCacheKeysAreIsolatedByKind() = runBlocking {
        val fake = CatalogCountingFake()
        val repo = IdlixRepository(api = fake, cache = DirectSourceCache())
        repo.getMovieDetail("same")
        repo.getSeriesDetail("same")
        repo.getEpisodeDetail("same", 1, 1)
        repo.getCollectionDetail("same")
        repo.getMovieDetail("same")
        repo.getSeriesDetail("same")
        assertEquals(1, fake.movieDetailCalls)
        assertEquals(1, fake.seriesDetailCalls)
        assertEquals(1, fake.episodeDetailCalls)
    }

    @Test
    fun searchCacheKeyUsesTrimmedQuery() = runBlocking {
        val fake = CatalogCountingFake()
        val repo = IdlixRepository(api = fake, cache = DirectSourceCache())
        repo.search(" Dune ")
        repo.search("Dune")
        assertEquals(1, fake.searchCalls)
        repo.search("Dune II")
        assertEquals(2, fake.searchCalls)
    }

    @Test
    fun listCacheKeySplitsOnPageAndGenre() = runBlocking {
        val fake = CatalogCountingFake()
        val repo = IdlixRepository(api = fake, cache = DirectSourceCache())
        repo.getMovies(page = 1, limit = 20)
        repo.getMovies(page = 1, limit = 20)
        repo.getMovies(page = 2, limit = 20)
        assertEquals(2, fake.movieCalls)
        repo.getGenres()
        repo.getGenres()
        assertEquals(1, fake.genreCalls)
        repo.getCollections()
        repo.getCollections()
        assertEquals(1, fake.collectionCalls)
    }
}
