package com.idlix.data.model

import com.idlix.data.net.CoreHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge-case coverage for the 1:1 mirror models and [FlexibleStringSerializer]:
 * absent-field defaults for every owned shape, `0`/`0.0` ratings (kept, never
 * coerced to missing), TMDB image-size prefixes, `toMovieItem` content-type
 * mapping, and the relative-URL seams. Offline-only.
 */
class IdlixModelEdgeTest {

    // ---------------------------------------------------------- FlexibleStringSerializer

    @Test
    fun flexibleStringAcceptsNumbersBooleansObjectsAndArrays() {
        assertEquals("123", CoreHttp.json.decodeFromString(FlexibleStringSerializer, "123"))
        assertEquals("6.4", CoreHttp.json.decodeFromString(FlexibleStringSerializer, "6.4"))
        assertEquals("true", CoreHttp.json.decodeFromString(FlexibleStringSerializer, "true"))
        assertEquals(
            """{"a":1}""",
            CoreHttp.json.decodeFromString(FlexibleStringSerializer, """{"a":1}""")
        )
        assertEquals(
            "[1,2]",
            CoreHttp.json.decodeFromString(FlexibleStringSerializer, "[1,2]")
        )
    }

    @Test
    fun flexibleStringAcceptsNullLiteral() {
        assertNull(CoreHttp.json.decodeFromString(FlexibleStringSerializer, "null"))
    }

    // ---------------------------------------------------------- zero ratings

    @Test
    fun movieZeroRatingIsKeptNotTreatedAsMissing() {
        val fromInt = CoreHttp.json.decodeFromString<MovieItem>(
            """{"id":"1","title":"T","voteAverage":0}"""
        )
        assertEquals("0", fromInt.voteAverage)
        assertEquals("0.0", fromInt.formattedRating)

        val fromDouble = CoreHttp.json.decodeFromString<MovieItem>(
            """{"id":"1","title":"T","voteAverage":0.0}"""
        )
        assertEquals("0.0", fromDouble.formattedRating)

        val fromString = MovieItem(id = "1", title = "T", voteAverage = "0.0")
        assertEquals("0.0", fromString.formattedRating)
    }

    @Test
    fun seriesZeroRatingIsKeptNotTreatedAsMissing() {
        val parsed = CoreHttp.json.decodeFromString<SeriesItem>(
            """{"id":"s1","title":"T","voteAverage":0}"""
        )
        assertEquals("0.0", parsed.formattedRating)
    }

    @Test
    fun searchNonNumericRatingFallsBackToRawValue() {
        // Locks the SearchResultItem inconsistency: unparseable ratings pass
        // through raw here, while MovieItem/SeriesItem render "N/A".
        val parsed = CoreHttp.json.decodeFromString<SearchResultItem>(
            """{"id":"1","title":"T","voteAverage":"abc"}"""
        )
        assertEquals("abc", parsed.formattedRating)
    }

    // ---------------------------------------------------------- absent-field defaults

    @Test
    fun movieDetailEmptyObjectCarriesSaneDefaults() {
        val parsed = CoreHttp.json.decodeFromString<MovieDetail>("{}")
        assertEquals("", parsed.id)
        assertEquals("", parsed.title)
        assertEquals("", parsed.slug)
        assertNull(parsed.tmdbId)
        assertNull(parsed.overview)
        assertNull(parsed.voteAverage)
        assertEquals(0, parsed.viewCount)
        assertTrue(parsed.genres.isEmpty())
        assertTrue(parsed.cast.isEmpty())
        assertNull(parsed.trailerUrl)
        assertEquals("", parsed.fullPosterUrl)
        assertEquals("", parsed.fullBackdropUrl)
    }

    @Test
    fun seriesDetailEmptyObjectCarriesSaneDefaults() {
        val parsed = CoreHttp.json.decodeFromString<SeriesDetail>("{}")
        assertEquals("", parsed.id)
        assertEquals(1, parsed.numberOfSeasons)
        assertEquals(1, parsed.numberOfEpisodes)
        assertTrue(parsed.seasons.isEmpty())
        assertNull(parsed.defaultSeason)
        assertTrue(parsed.cast.isEmpty())
        assertNull(parsed.voteAverage)
    }

    @Test
    fun episodeShapesDefaultWithoutCrashing() {
        val parsed = CoreHttp.json.decodeFromString<EpisodeResponse>("{}")
        assertNull(parsed.series)
        assertNull(parsed.season)
        assertNull(parsed.episode)
        assertTrue(parsed.allSeasons.isEmpty())

        val item = CoreHttp.json.decodeFromString<EpisodeItem>("{}")
        assertEquals(1, item.episodeNumber)
        assertEquals("", item.name)
        assertTrue(item.hasVideo)
    }

    @Test
    fun collectionShapesDefaultWithoutCrashing() {
        val parsed = CoreHttp.json.decodeFromString<CollectionDetailResponse>("{}")
        assertEquals(CollectionItem(), parsed.collection)
        assertTrue(parsed.sections.isEmpty())
        assertNull(parsed.canonicalSlug)
        assertTrue(parsed.isCanonical)
        assertNull(parsed.sectionAnchor)

        val section = CoreHttp.json.decodeFromString<CollectionSection>("{}")
        assertEquals("", section.title)
        assertTrue(section.items.isEmpty())
    }

    @Test
    fun playbackShapesDefaultWithoutCrashing() {
        val playInfo = CoreHttp.json.decodeFromString<PlayInfoResponse>("{}")
        assertEquals("", playInfo.kind)
        assertEquals("", playInfo.gateToken)
        assertEquals(0L, playInfo.serverNow)
        assertEquals(0L, playInfo.unlockAt)
        assertEquals("guest", playInfo.viewerTier)
        assertEquals(720, playInfo.maxHeight)

        val claim = CoreHttp.json.decodeFromString<ClaimResponse>("{}")
        assertEquals("", claim.claim)
        assertEquals(0L, claim.claimExpiresAt)
        assertTrue(claim.qualityLadder.isEmpty())

        val redeem = CoreHttp.json.decodeFromString<RedeemResponse>("{}")
        assertEquals("", redeem.url)
        assertEquals("browser", redeem.mode)
        assertEquals(3600L, redeem.ttlSeconds)
        assertTrue(redeem.subtitles.isEmpty())
    }

    @Test
    fun subdlShapesDefaultWithoutCrashing() {
        val parsed = CoreHttp.json.decodeFromString<SubdlResponse>("{}")
        assertFalse(parsed.status)
        assertTrue(parsed.results.isEmpty())
        assertTrue(parsed.subtitles.isEmpty())

        val item = CoreHttp.json.decodeFromString<SubdlItem>(
            """{"sd_id":null,"type":null,"name":null,"imdb_id":null,"tmdb_id":null,"year":null}"""
        )
        assertNull(item.sd_id)
        assertNull(item.year)

        val subtitle = CoreHttp.json.decodeFromString<SubdlSubtitle>("{}")
        assertEquals("", subtitle.url)
        assertNull(subtitle.releaseName)
    }

    @Test
    fun genreAndCastMemberDefaultWithoutCrashing() {
        val genre = CoreHttp.json.decodeFromString<Genre>("{}")
        assertEquals("", genre.id)
        assertEquals("", genre.name)
        assertEquals("", genre.slug)

        val cast = CoreHttp.json.decodeFromString<CastMember>("{}")
        assertEquals("", cast.id)
        assertNull(cast.character)
        assertEquals("", cast.fullProfileUrl)
    }

    @Test
    fun paginationCoercesExplicitNullToDefault() {
        val parsed = CoreHttp.json.decodeFromString<Pagination>(
            """{"page":null,"limit":null,"total":0,"totalPages":1}"""
        )
        assertEquals(1, parsed.page)
        assertEquals(20, parsed.limit)
    }

    // ---------------------------------------------------------- toMovieItem mapping

    @Test
    fun collectionTitleLegacySeriesContentTypeMapsToTvSeries() {
        val legacy = CollectionTitle(id = "s1", title = "Loki", slug = "loki", contentType = "series")
        assertEquals("tv_series", legacy.toMovieItem().contentType)
    }

    @Test
    fun collectionTitleUnknownContentTypeFallsBackToMovie() {
        val unknown = CollectionTitle(id = "x1", title = "X", slug = "x", contentType = "")
        assertEquals("movie", unknown.toMovieItem().contentType)
    }

    @Test
    fun collectionTitlePrefersReleaseDateOverFirstAirDate() {
        val both = CollectionTitle(
            id = "m1", title = "T", slug = "t",
            releaseDate = "2024-01-01", firstAirDate = "2021-06-09"
        )
        assertEquals("2024-01-01", both.toMovieItem().releaseDate)
    }

    @Test
    fun collectionTitleFallsBackToFirstAirDateWhenReleaseMissing() {
        val series = CollectionTitle(id = "s1", title = "T", slug = "t", firstAirDate = "2021-06-09")
        assertEquals("2021-06-09", series.toMovieItem().releaseDate)
    }

    @Test
    fun collectionViewCountAndQualitySurviveConversion() {
        val title = CollectionTitle(
            id = "m1", title = "T", slug = "t",
            viewCount = 42L, quality = "BLU-RAY", country = "US", runtime = 120
        )
        val movie = title.toMovieItem()
        assertEquals(42L, movie.viewCount)
        assertEquals("BLU-RAY", movie.quality)
        assertEquals("US", movie.country)
        assertEquals(120, movie.runtime)
    }

    // ---------------------------------------------------------- TMDB image URLs

    @Test
    fun tmdbSizePrefixesAreStableAcrossShapes() {
        assertEquals(
            "https://image.tmdb.org/t/p/w342/p.jpg",
            MovieDetail(id = "1", title = "T", slug = "t", posterPath = "/p.jpg").fullPosterUrl
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w1280/b.jpg",
            SeriesDetail(id = "s1", title = "T", slug = "s", backdropPath = "/b.jpg").fullBackdropUrl
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w500/s.jpg",
            EpisodeItem(id = "e1", stillPath = "/s.jpg").fullStillUrl
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w342/c.jpg",
            CastMember(id = "c1", name = "N", profilePath = "/c.jpg").fullProfileUrl
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w342/p.jpg",
            SearchResultItem(id = "1", title = "T", posterPath = "/p.jpg").fullPosterUrl
        )
    }

    @Test
    fun posterPathWithoutLeadingSlashDocumentsConcatSeam() {
        // Paths arrive with a leading "/" upstream; without it the segments
        // concatenate directly. Locks current behavior as a hardening seam.
        assertEquals(
            "https://image.tmdb.org/t/p/w342p.jpg",
            MovieItem(id = "1", title = "T", posterPath = "p.jpg").fullPosterUrl
        )
    }

    @Test
    fun subdlRelativeUrlWithoutLeadingSlashDocumentsConcatSeam() {
        assertEquals(
            "https://dl.subdl.comdl/abc.zip",
            SubdlSubtitle(url = "dl/abc.zip").fullDownloadUrl
        )
    }
}
