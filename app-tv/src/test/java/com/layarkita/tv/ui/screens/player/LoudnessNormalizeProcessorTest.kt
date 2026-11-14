package com.layarkita.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.pow

/**
 * JVM unit tests for the stage-1 PCM loudness normalizer.
 *
 * The processor is pure PCM math ([BaseAudioProcessor] + [ByteBuffer]), so the
 * whole contract runs on plain JVM: no Android framework, no DSP session, no
 * player. What still needs the rig (out of scope here): ear-tuning of the
 * preset against real film audio and the stage-2 DynamicsProcessing effect.
 *
 * Covers: preset constants, configure-only contract (rejects non-PCM / bad
 * geometry, passes format through unchanged), queueInput-before-configure
 * safety, disabled transparent bypass, enabled silence stability, the -1.5
 * dBFS limiter clamp on full-scale input, float path, toggle state, flush.
 */
@OptIn(UnstableApi::class)
class LoudnessNormalizeProcessorTest {

    private fun stereo16Frames(frames: Int, sample: Short): ByteBuffer {
        val buf = ByteBuffer.allocate(frames * 2 * 2)
        repeat(frames * 2) {
            buf.put((sample.toInt() and 0xFF).toByte())
            buf.put(((sample.toInt() shr 8) and 0xFF).toByte())
        }
        buf.flip()
        return buf
    }

    private fun drain16(out: ByteBuffer): ShortArray {
        val count = out.remaining() / 2
        val result = ShortArray(count)
        for (i in 0 until count) {
            val lo = out.get(i * 2).toInt() and 0xFF
            val hi = out.get(i * 2 + 1).toInt()
            var v = lo or (hi shl 8)
            if (v >= 32768) v -= 65536
            result[i] = v.toShort()
        }
        out.position(out.limit())
        return result
    }

    private fun configured(
        channels: Int = 2,
        rate: Int = 48000,
        encoding: Int = C.ENCODING_PCM_16BIT,
    ): LoudnessNormalizeProcessor {
        val processor = LoudnessNormalizeProcessor()
        // Media3 AudioFormat ctor order is (sampleRate, channelCount, encoding).
        processor.configure(AudioProcessor.AudioFormat(rate, channels, encoding))
        return processor
    }

    private fun expectUnhandled(block: () -> Unit) {
        try {
            block()
        } catch (e: AudioProcessor.UnhandledAudioFormatException) {
            return
        }
        throw AssertionError("Expected UnhandledAudioFormatException")
    }

    @Test
    fun presetConstantsMatchVerifiedSpec() {
        assertEquals(15.0, LoudnessNormalizeProcessor.PRE_GAIN_DB, 0.0)
        assertEquals(20.0, LoudnessNormalizeProcessor.TOTAL_CAP_DB, 0.0)
        assertEquals(-1.5, LoudnessNormalizeProcessor.LIMITER_CEILING_DB, 0.0)
        assertEquals(-26.0, LoudnessNormalizeProcessor.LOW_THRESHOLD_DB, 0.0)
        assertEquals(4.0, LoudnessNormalizeProcessor.LOW_RATIO, 0.0)
        assertEquals(-22.0, LoudnessNormalizeProcessor.MID_THRESHOLD_DB, 0.0)
        assertEquals(3.0, LoudnessNormalizeProcessor.MID_RATIO, 0.0)
        assertEquals(6.0, LoudnessNormalizeProcessor.MID_KNEE_DB, 0.0)
        assertEquals(-26.0, LoudnessNormalizeProcessor.HIGH_THRESHOLD_DB, 0.0)
        assertEquals(2.0, LoudnessNormalizeProcessor.HIGH_RATIO, 0.0)
        // +15 dB pre-gain ≈ x5.623 linear; -1.5 dBFS ceiling ≈ x0.8414 linear.
        assertEquals(5.623, 10.0.pow(15.0 / 20.0), 0.001)
        assertEquals(0.8414, 10.0.pow(-1.5 / 20.0), 0.0001)
    }

    @Test
    fun configureRejectsNonPcmEncoding() {
        expectUnhandled {
            configured(encoding = C.ENCODING_PCM_8BIT)
        }
        expectUnhandled {
            configured(encoding = C.ENCODING_MP3)
        }
    }

    @Test
    fun configureRejectsBadGeometry() {
        expectUnhandled { configured(channels = 0) }
        expectUnhandled { configured(rate = 0) }
    }

    @Test
    fun configurePassesFormatThroughUnchanged() {
        val processor = LoudnessNormalizeProcessor()
        val out = processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        assertEquals(2, out.channelCount)
        assertEquals(48000, out.sampleRate)
        assertEquals(C.ENCODING_PCM_16BIT, out.encoding)
        assertTrue(processor.isActive)
    }

    @Test
    fun queueInputBeforeConfigureDropsSafely() {
        val processor = LoudnessNormalizeProcessor()
        assertFalse(processor.isActive)
        processor.queueInput(stereo16Frames(16, 1000))
        assertEquals(0, processor.output.remaining())
    }

    @Test
    fun disabledIsTransparentBypass() {
        val processor = configured()
        processor.setEnabled(false)
        assertFalse(processor.enabled)
        val input = stereo16Frames(64, 12345)
        val expected = ByteArray(input.remaining())
        input.mark()
        input.get(expected)
        input.reset()
        processor.queueInput(input)
        val out = processor.output
        assertEquals(expected.size, out.remaining())
        val actual = ByteArray(out.remaining())
        out.get(actual)
        assertTrue(expected.contentEquals(actual))
    }

    @Test
    fun enabledSilenceStaysSilence() {
        val processor = configured()
        assertTrue(processor.enabled)
        processor.queueInput(stereo16Frames(1024, 0))
        val samples = drain16(processor.output)
        assertEquals(2048, samples.size)
        assertTrue(samples.all { it == 0.toShort() })
    }

    @Test
    fun limiterClampsFullScalePeaksToCeiling() {
        val processor = configured()
        // 1 s of full-scale stereo: with +15 dB pre-gain the limiter must
        // engage; the hard clamp bounds every sample including the attack
        // transient before the follower converges.
        processor.queueInput(stereo16Frames(48000, Short.MAX_VALUE))
        val samples = drain16(processor.output)
        assertEquals(96000, samples.size)
        val ceilingSamples = (32767 * 10.0.pow(-1.5 / 20.0)).toInt() + 2
        var peak = 0
        for (s in samples) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
            assertTrue("Sample $s exceeds -1.5 dBFS ceiling ($ceilingSamples)", a <= ceilingSamples)
        }
        // Signal passes through (not muted): a constant full-scale DC input is
        // low-band-dominated after the split, so the -26 dBFS 4:1 low compressor
        // pulls it well below the ceiling (measured peak ~6728, XML line 169).
        // The ceiling assert above is the limiter guarantee; here just rule out mute.
        assertTrue("Peak $peak too low; normalizer is muting", peak > 1000)
    }

    @Test
    fun floatSilenceStaysSilence() {
        val processor = configured(encoding = C.ENCODING_PCM_FLOAT)
        val input = ByteBuffer.allocate(256 * 2 * 4)
        input.position(input.limit())
        input.flip()
        processor.queueInput(input)
        val out = processor.output
        assertEquals(256 * 2 * 4, out.remaining())
        while (out.hasRemaining()) {
            assertEquals(0.toByte(), out.get())
        }
    }

    @Test
    fun floatBypassCopiesExactly() {
        val processor = configured(encoding = C.ENCODING_PCM_FLOAT)
        processor.setEnabled(false)
        val input = ByteBuffer.allocate(32 * 4)
        repeat(32) { input.putFloat(it * 0.125f - 2f) }
        input.flip()
        val expected = ByteArray(input.remaining())
        input.mark()
        input.get(expected)
        input.reset()
        processor.queueInput(input)
        val out = processor.output
        val actual = ByteArray(out.remaining())
        out.get(actual)
        assertTrue(expected.contentEquals(actual))
    }

    @Test
    fun toggleFlipsStateAndStaysActive() {
        val processor = configured()
        assertTrue(processor.enabled)
        processor.setEnabled(false)
        assertFalse(processor.enabled)
        assertTrue(processor.isActive)
        processor.setEnabled(true)
        assertTrue(processor.enabled)
        // Pipeline still works after the flip (state was reset, not corrupted).
        processor.queueInput(stereo16Frames(256, 0))
        assertTrue(drain16(processor.output).all { it == 0.toShort() })
    }

    @Test
    fun flushKeepsPipelineUsable() {
        val processor = configured()
        processor.queueInput(stereo16Frames(512, Short.MAX_VALUE))
        assertTrue(processor.output.remaining() > 0)
        processor.flush()
        assertTrue(processor.isActive)
        processor.queueInput(stereo16Frames(256, 0))
        assertTrue(drain16(processor.output).all { it == 0.toShort() })
    }

    @Test
    fun monoConfiguresAndPassesSilence() {
        val processor = configured(channels = 1, rate = 44100)
        val buf = ByteBuffer.allocate(512 * 2)
        buf.position(buf.limit())
        buf.flip()
        processor.queueInput(buf)
        assertEquals(512 * 2, processor.output.remaining())
    }
}
