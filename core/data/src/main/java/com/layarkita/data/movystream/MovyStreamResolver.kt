package com.layarkita.data.movystream

import com.layarkita.data.model.ResolvedPlayback
import com.layarkita.data.model.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Direct playback resolver over the movy.sx stream CDN (api.wecollege.net).
 *
 * Flow per provider: `/seed?mediaId={tmdbId}` → `/{city}/sources?…&enc=2&seed`
 * (base64url payload) → `mvm1` keystream decrypt → variant playlist URLs plus
 * sidecar subtitle tracks. No account, no gate, no Turnstile — unlike the
 * IDLIX guest claim path, which a headless TV client cannot complete.
 *
 * The keystream below is a faithful port of the CDN's reference decoder
 * (murmur3-fmix32 + rotl32 + golden-ratio PRNG over a 61-slot state table,
 * little-endian words). Unit-tested against a captured seed/payload pair in
 * `movy-fixture.json`.
 *
 * Provider priority mirrors the movy web player: Miami/Boise carry up to
 * 2160p without subs, Dallas ships Indonesian (lang `id`) WebVTT sidecars,
 * Seattle carries EN SRT. First provider that yields sources wins; a null
 * result means "no coverage" and callers fall back to the IDLIX gate flow.
 */
open class MovyStreamResolver(
    private val client: OkHttpClient = defaultClient(),
    private val base: String = "https://api.wecollege.net"
) {

    /** Provider cities in pick order; first successful response is used. */
    private val providers = listOf("miami", "boise", "dallas", "seattle")

    /**
     * Resolves one movie against the CDN. Returns null when no provider
     * covers the title (caller falls back); throws nothing outward.
     */
    open suspend fun resolveMovie(
        title: String,
        year: Int?,
        tmdbId: Long,
        imdbId: String?
    ): ResolvedPlayback? = withContext(Dispatchers.IO) {
        val mediaId = tmdbId.toInt()
        var seed: String? = null
        var best: ResolvedPlayback? = null
        android.util.Log.d("MovyResolver", "resolveMovie tmdbId=$tmdbId title=$title year=$year")
        for (provider in providers) {
            try {
                val currentSeed = seed ?: fetchSeed(mediaId)?.also { seed = it } ?: continue
                val payload = fetchSources(provider, title, year, tmdbId, imdbId, currentSeed) ?: continue
                val plain = decrypt(payload, currentSeed, mediaId) ?: continue
                val parsed = Json.parseToJsonElement(plain).jsonObject
                val sources = parsed["sources"]?.jsonArray ?: continue
                val src = pickBestSource(sources) ?: continue
                android.util.Log.d("MovyResolver", "video via $provider: ${src.quality}")
                best = ResolvedPlayback(
                    title = title,
                    hlsMasterUrl = src.url,
                    subtitles = parseSubtitles(parsed),
                    sourceOrigin = MOVY_ORIGIN
                )
                break
            } catch (e: Exception) {
                android.util.Log.d("MovyResolver", "$provider failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        val video = best ?: run {
            android.util.Log.d("MovyResolver", "no video from any provider")
            return@withContext null
        }
        if (video.subtitles.isEmpty()) {
            // Video-only provider (Miami/Boise 1080p–2160p): sweep the rest
            // for sidecar tracks — Dallas carries Indonesian WebVTT. Some
            // providers answer the sweep with a PLAIN JSON envelope instead
            // of an encrypted payload, so accept both shapes. Dallas's first
            // response can exceed 12s; the client's read timeout must cover it.
            for (provider in providers) {
                try {
                    val currentSeed = seed ?: continue
                    val payload = fetchSourcesRaw(provider, title, year, tmdbId, imdbId, currentSeed)
                    if (payload == null) continue
                    val parsed: kotlinx.serialization.json.JsonObject =
                        if (payload.trimStart().startsWith("{")) {
                            Json.parseToJsonElement(payload.trim()).jsonObject
                        } else {
                            val plain = decrypt(payload, currentSeed, mediaId) ?: continue
                            Json.parseToJsonElement(plain).jsonObject
                        }
                    val subs = parseSubtitles(parsed)
                    if (subs.isNotEmpty()) return@withContext video.copy(subtitles = subs)
                } catch (_: Exception) {
                }
            }
        }
        video
    }

    // ------------------------------------------------------------- transport

    private fun fetchSeed(mediaId: Int): String? {
        val body = getJson("$base/seed?mediaId=$mediaId") ?: return null
        return Json.parseToJsonElement(body).jsonObject["seed"]?.jsonPrimitive?.content
    }

    private fun fetchSources(
        provider: String,
        title: String,
        year: Int?,
        tmdbId: Long,
        imdbId: String?,
        seed: String
    ): String? {
        // The web player double-encodes (encodeURIComponent + query encoding);
        // the CDN compares the once-decoded string, so mirror it exactly.
        val encodedTitle = percentEncode(percentEncode(title))
        val url = buildString {
            append(base).append('/').append(provider).append("/sources?")
            append("title=").append(encodedTitle)
            append("&mediaType=movie")
            if (year != null && year > 1900) append("&year=").append(year)
            append("&tmdbId=").append(tmdbId)
            if (!imdbId.isNullOrBlank()) append("&imdbId=").append(percentEncode(percentEncode(imdbId)))
            append("&enc=2&seed=").append(percentEncode(seed))
        }
        val text = getText(url) ?: return null
        // Encrypted payloads are bare base64url; anything starting with '{'
        // is a JSON error envelope (seed invalid, unknown title, …).
        if (text.trimStart().startsWith("{")) return null
        return text.trim()
    }

    private fun getJson(url: String): String? = runCatching {
        client.newCall(request(url)).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }.getOrNull()

    private fun getText(url: String): String? = getJson(url)

    /**
     * Same as [fetchSources] but returns plain JSON envelopes untouched —
     * the subtitle sweep must see them (some providers answer unencrypted).
     */
    private fun fetchSourcesRaw(
        provider: String,
        title: String,
        year: Int?,
        tmdbId: Long,
        imdbId: String?,
        seed: String
    ): String? {
        val url = buildString {
            append(base).append('/').append(provider).append("/sources?")
            append("title=").append(percentEncode(percentEncode(title)))
            append("&mediaType=movie")
            if (year != null && year > 1900) append("&year=").append(year)
            append("&tmdbId=").append(tmdbId)
            if (!imdbId.isNullOrBlank()) append("&imdbId=").append(percentEncode(percentEncode(imdbId)))
            append("&enc=2&seed=").append(percentEncode(seed))
        }
        return getText(url)?.trim()
    }

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", BROWSER_UA)
        .header("Referer", MOVY_PAGE)
        .header("Origin", MOVY_ORIGIN)
        .header("Accept", "*/*")
        .get()
        .build()

    // ------------------------------------------------------------ selections

    /** Highest variant at or under 1080p; "Auto" ABR masters as fallback. */
    private fun pickBestSource(sources: kotlinx.serialization.json.JsonArray): MovySource? {
        val parsed = sources.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val quality = obj.str("quality") ?: return@mapNotNull null
            val url = obj.str("url") ?: return@mapNotNull null
            MovySource(quality, url)
        }
        if (parsed.isEmpty()) return null
        val under1080 = parsed.filter { (it.height() in 1..1080) }
        under1080.maxByOrNull { it.height() }?.let { return it }
        // "Auto" variants carry no digits — the ABR master plays everything.
        parsed.firstOrNull { it.height() == 0 }?.let { return it }
        return parsed.maxByOrNull { it.height() }
    }

    private fun MovySource.height(): Int = quality.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0

    /** Indonesian first (Dallas `id` WebVTT), English second (Seattle SRT). */
    private fun parseSubtitles(obj: JsonObject): List<SubtitleTrack> {
        val arr = obj["subtitles"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val sub = el as? JsonObject ?: return@mapNotNull null
            val lang = sub.str("lang") ?: sub.str("language") ?: return@mapNotNull null
            val url = sub.str("url") ?: return@mapNotNull null
            val label = sub.str("label") ?: when (lang.lowercase()) {
                "id", "ind", "indonesian" -> "Indonesian"
                "en", "eng", "english" -> "English"
                else -> lang
            }
            SubtitleTrack(lang = lang, label = label, path = url)
        }
    }

    // ------------------------------------------------------------ crypto

    /**
     * Decodes the `mvm1` envelope: keystream-XOR the payload, verify the
     * magic, return the UTF-8 JSON body. Null on any mismatch.
     */
    internal fun decrypt(payloadB64Url: String, seed: String, mediaId: Int): String? {
        return try {
            val data = b64UrlDecode(payloadB64Url)
            val state = KeyState(seed, mediaId)
            val plain = ByteArray(data.size)
            for (i in data.indices) plain[i] = (data[i].toInt() xor state.nextByte()).toByte()
            for (k in MAGIC.indices) if (plain[k] != MAGIC[k]) return null
            String(plain, MAGIC.size, plain.size - MAGIC.size, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** 61-slot PRNG state emitting one keystream byte per call (LE words). */
    private class KeyState(seed: String, mediaId: Int) {
        private val slots = arrayOfNulls<Int>(61)
        private val assigned = BooleanArray(61)
        private var acc: Int
        private var wordIndex = 0
        private var byteInWord = 0
        private var word = 0

        init {
            var n = fmix32(fnv1a(seed) xor fmix32(mediaId xor GOLDEN))
            for (e in 0 until 8) {
                val slot = unsignedMod(n, 61)
                // JS `7+(7&e)` is a BITWISE AND over the loop index, not a
                // multiply — shifts run 7,8,9,…,14 across the 8 writes.
                n = rotl(n + GOLDEN, 7 + (e and 7))
                slots[slot] = n xor fmix32(n)
                assigned[slot] = true
                n = fmix32(n + slot)
            }
            acc = fmix32(0xA5A5A5A5.toInt() xor n)
        }

        fun nextByte(): Int {
            if (byteInWord == 0) {
                word = nextWord()
            }
            val shift = byteInWord * 8
            byteInWord = (byteInWord + 1) and 3
            return (word ushr shift) and 0xFF
        }

        private fun nextWord(): Int {
            val d = unsignedMod(acc, 61)
            val r = slots[d] ?: 0
            val c = GOLDEN * (wordIndex + 1)
            val rc = r xor c
            // JS reference: b = (acc ^ rc) | (acc & rc & present-mask);
            // present ⇒ the OR-of-XOR-and-AND collapses to acc | rc.
            val b = if (assigned[d]) acc or rc else acc xor rc
            // JS `u(l,31&Math.imul(d,7))` — d×7, NOT golden×d.
            val mixed = rotl(b + acc, 31 and d) xor rotl(acc, 31 and (d * 7))
            acc = fmix32(mixed + GOLDEN)
            slots[d] = acc
            assigned[d] = true
            wordIndex++
            return acc
        }
    }

    // ------------------------------------------------------------- helpers

    private companion object {
        const val GOLDEN = 0x9e3779b9.toInt()
        const val MOVY_ORIGIN = "https://www.movy.sx"
        const val MOVY_PAGE = "https://www.movy.sx/"
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        val MAGIC = byteArrayOf(0x6D, 0x76, 0x6D, 0x31) // "mvm1"

        /** murmur3 32-bit finalizer, matching Math.imul bit semantics. */
        fun fmix32(h0: Int): Int {
            var h = h0 xor (h0 ushr 16)
            h *= 0x85ebca6b.toInt()
            h = h xor (h ushr 13)
            h *= 0xc2b2ae35.toInt()
            return h xor (h ushr 16)
        }

        /** FNV-1a over UTF-16 code units, finalized with fmix32. */
        fun fnv1a(value: String): Int {
            var h = 0x811c9dc5.toInt()
            for (ch in value) h = (h xor ch.code) * 0x1000193
            return fmix32(h)
        }

        fun rotl(x: Int, n: Int): Int {
            val s = n and 31
            if (s == 0) return x
            return (x shl s) or (x ushr (32 - s))
        }

        /** JS `uintValue % m`: the modulus runs on the unsigned bit pattern. */
        fun unsignedMod(value: Int, m: Int): Int =
            ((value.toLong() and 0xFFFF_FFFFL) % m).toInt()

        fun b64UrlDecode(value: String): ByteArray {
            val normalized = value.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            return java.util.Base64.getDecoder().decode(padded)
        }

        fun JsonObject.str(key: String): String? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // Dallas (the Indonesian-sub provider) is slow — first response
            // can take >12s, so read headroom must exceed the old 12s.
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Minimal ASCII percent-encoding for query values (space, control, and
 * reserved characters only) — matches URLSearchParams closely enough that
 * the CDN compares the raw string, never re-decoding it.
 */
internal fun percentEncode(value: String): String = buildString {
    for (b in value.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        if (c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A ||
            c == '-'.code || c == '_'.code || c == '.'.code || c == '*'.code
        ) {
            append(c.toChar())
        } else {
            append('%')
            append(Integer.toHexString(c).uppercase().padStart(2, '0'))
        }
    }
}

private data class MovySource(val quality: String, val url: String)
