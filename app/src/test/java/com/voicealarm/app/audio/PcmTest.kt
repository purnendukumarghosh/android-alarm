package com.voicealarm.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class PcmTest {
    @Test
    fun targetFormat_staysInsideAacEncoderRange() {
        assertEquals(48_000, Pcm.targetRate(96_000))
        assertEquals(44_100, Pcm.targetRate(44_100))
        assertEquals(16_000, Pcm.targetRate(4_000))
        assertEquals(1, Pcm.targetChannels(1))
        assertEquals(2, Pcm.targetChannels(6))
    }

    @Test
    fun downmix_monoToStereoDuplicates() {
        assertArrayEquals(shortArrayOf(5, 5, -7, -7), Pcm.downmix(shortArrayOf(5, -7), 1, 2))
    }

    @Test
    fun downmix_surroundKeepsFrontDominant() {
        // One 5.1 frame: FL=1000 FR=-1000, the rest 0.
        val out = Pcm.downmix(shortArrayOf(1000, -1000, 0, 0, 0, 0), 6, 2)
        assertArrayEquals(shortArrayOf(600, -600), out)
    }

    @Test
    fun downmix_stereoToMonoAverages() {
        assertArrayEquals(shortArrayOf(0, 150), Pcm.downmix(shortArrayOf(100, -100, 100, 200), 2, 1))
    }

    @Test
    fun resampler_halvesFrameCountAcrossChunks() {
        val r = Resampler(96_000, 48_000, 1)
        var total = 0
        repeat(10) { total += r.process(ShortArray(9_600) { 1 }).size }
        // 96 000 input frames → ~48 000 output frames, regardless of chunking.
        assertTrue("got $total", abs(total - 48_000) <= 2)
    }

    @Test
    fun resampler_isSeamlessAtChunkBoundaries() {
        // A 1 kHz sine at 96 kHz, fed in odd-sized chunks, must come out as a clean 1 kHz sine at 44.1 kHz.
        val input = ShortArray(96_000) { (sin(2 * PI * 1000 * it / 96_000) * 20_000).roundToInt().toShort() }
        val r = Resampler(96_000, 44_100, 1)
        val out = ArrayList<Short>()
        var i = 0
        val sizes = intArrayOf(1, 7, 333, 4096, 50)
        var k = 0
        while (i < input.size) {
            val n = minOf(sizes[k++ % sizes.size], input.size - i)
            r.process(input.copyOfRange(i, i + n)).forEach { out += it }
            i += n
        }
        assertTrue(abs(out.size - 44_100) <= 2)
        var maxErr = 0.0
        for (j in 0 until out.size - 1) {
            val expected = sin(2 * PI * 1000 * j / 44_100.0) * 20_000
            maxErr = maxOf(maxErr, abs(out[j] - expected))
        }
        // Linear interpolation of a 1 kHz tone at 96 kHz is accurate to well under 1 %.
        assertTrue("max error $maxErr", maxErr < 200)
    }

    @Test
    fun resampler_passThroughWhenRatesMatch() {
        val r = Resampler(44_100, 44_100, 2)
        val x = shortArrayOf(1, 2, 3, 4)
        assertTrue(r.process(x) === x)
    }
}
