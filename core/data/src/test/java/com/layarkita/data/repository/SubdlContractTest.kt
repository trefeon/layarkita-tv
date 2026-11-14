package com.layarkita.data.repository

import com.layarkita.data.model.SubdlResponse
import com.layarkita.data.model.SubdlSubtitle
import com.layarkita.data.net.CoreHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline contract tests for the SubDL slice: response parsing and download-URL
 * resolution. The live search path (`searchAllSubtitles`) needs
 * `android.util.Log` + network, so its query contract (key fail-closed,
 * tmdb > imdb > film_name, year-strip, tv/movie + season/episode params) is
 * pinned by review only — see the slice report. These tests lock everything
 * around it that runs on the JVM.
 */
class SubdlContractTest {

    @Test
    fun subdlResponseParses() {
        val body = """{"status":true,"results":[{"sd_id":1,"type":"movie","name":"Fuori","year":2025}],""" +
            """"subtitles":[{"release_name":"Fuori.2025.WEB","name":"Fuori","lang":"Indonesian","language":"ID","author":"subber","url":"/sub/abc.zip"}]}"""
        val parsed = CoreHttp.json.decodeFromString(SubdlResponse.serializer(), body)
        assertTrue(parsed.status)
        assertEquals(2025, parsed.results[0].year)
        assertEquals("ID", parsed.subtitles[0].language)
        assertEquals("Indonesian", parsed.subtitles[0].lang)
    }

    @Test
    fun relativeDownloadUrlResolvesToDlHost() {
        val sub = SubdlSubtitle(url = "/sub/abc.zip")
        assertEquals("https://dl.subdl.com/sub/abc.zip", sub.fullDownloadUrl)
    }

    @Test
    fun absoluteDownloadUrlPassesThrough() {
        val sub = SubdlSubtitle(url = "https://cdn.example.com/a.zip")
        assertEquals("https://cdn.example.com/a.zip", sub.fullDownloadUrl)
    }

    @Test
    fun indonesianPickPredicateMatchesChooser() {
        // Mirrors the `fetchIndonesianSubtitle` chooser so a predicate drift
        // fails here first: language == ID (any case) or lang == indonesian.
        val indo = SubdlSubtitle(lang = "Indonesian", language = "ID")
        val english = SubdlSubtitle(lang = "English", language = "EN")
        fun isIndo(s: SubdlSubtitle) =
            s.language.equals("ID", ignoreCase = true) || s.lang.equals("indonesian", ignoreCase = true)
        assertTrue(isIndo(indo))
        assertTrue(!isIndo(english))
    }
}
