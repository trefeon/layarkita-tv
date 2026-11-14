package com.layarkita.data.movystream

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `mvm1` keystream port against a captured seed/payload pair from
 * the live CDN (payload captured from the same /seed fetch that produced it,
 * plaintext recorded from the reference web player). If the CDN rotates its
 * algorithm, this fixture is the tripwire: regenerate with the capture tool
 * and re-port, never hand-tune constants to make it pass.
 */
class MovyStreamResolverTest {

    private val resolver = MovyStreamResolver()

    private fun fixtureJson(): String =
        javaClass.getResourceAsStream("/movy-fixture.json")!!
            .readBytes().toString(Charsets.UTF_8)

    private fun fixtureField(key: String): String {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(fixtureJson()).jsonObject
        return obj[key]?.jsonPrimitive?.content ?: error("fixture missing field $key")
    }

    @Test
    fun decryptMatchesReferencePlaintext() {
        val seed = fixtureField("seed")
        val payload = fixtureField("payloadB64url")
        val expected = fixtureField("expectedPlainPrefix")

        val plain = resolver.decrypt(payload, seed, 299534)

        assertNotNull("decrypt returned null — keystream port diverged", plain)
        assertTrue(
            "plaintext mismatch\nexpected prefix: $expected\nactual: ${plain?.take(120)}",
            plain!!.startsWith(expected)
        )
    }

    @Test
    fun decryptRejectsTamperedPayload() {
        val seed = fixtureField("seed")
        val payload = fixtureField("payloadB64url")
        // Flip one payload character: the keystream is position-sensitive, so
        // the magic bytes must no longer validate.
        val tampered = buildString {
            append(payload.substring(0, 4))
            append(if (payload[4] == 'A') 'B' else 'A')
            append(payload.substring(5))
        }
        assertEquals(null, resolver.decrypt(tampered, seed, 299534))
    }

    @Test
    fun decryptRejectsWrongMediaId() {
        val seed = fixtureField("seed")
        val payload = fixtureField("payloadB64url")
        // The media id feeds key derivation — a different id must fail magic.
        assertEquals(null, resolver.decrypt(payload, seed, 299535))
    }
}
