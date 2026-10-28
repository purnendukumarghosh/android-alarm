package com.voicealarm.app.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/*
 * Plain-Kotlin PCM helpers used by AudioConverter between the decoder and the
 * AAC encoder. No Android types here, so they are unit-tested on the JVM.
 * All PCM is interleaved 16-bit signed samples.
 */
object Pcm {
    /** AAC encoders on Android accept 8–48 kHz; anything else is resampled into range. */
    fun targetRate(sourceRate: Int): Int = when {
        sourceRate > 48_000 -> 48_000
        sourceRate < 8_000 -> 16_000
        else -> sourceRate
    }

    /** Mono stays mono, everything else becomes stereo. */
    fun targetChannels(sourceChannels: Int): Int = if (sourceChannels <= 1) 1 else 2

    /** Reads decoder output (16-bit or float PCM) into 16-bit samples. */
    fun read(buffer: ByteBuffer, isFloat: Boolean): ShortArray {
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        return if (isFloat) {
            val f = b.asFloatBuffer()
            ShortArray(f.remaining()) { (f.get(it).coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort() }
        } else {
            val s = b.asShortBuffer()
            ShortArray(s.remaining()).also { s.get(it) }
        }
    }

    /**
     * Folds any channel layout into [outChannels] (1 or 2). Surround is folded
     * so the front pair stays dominant: L = 0.6·ch0 + 0.4·avg(rest), likewise R.
     */
    fun downmix(input: ShortArray, inChannels: Int, outChannels: Int): ShortArray {
        if (inChannels == outChannels || inChannels <= 0) return input
        val frames = input.size / inChannels
        val out = ShortArray(frames * outChannels)
        for (f in 0 until frames) {
            val base = f * inChannels
            if (outChannels == 1) {
                var sum = 0
                for (c in 0 until inChannels) sum += input[base + c]
                out[f] = clamp(sum / inChannels)
            } else if (inChannels == 1) {
                out[2 * f] = input[base]
                out[2 * f + 1] = input[base]
            } else {
                var rest = 0f
                for (c in 2 until inChannels) rest += input[base + c]
                val restAvg = if (inChannels > 2) rest / (inChannels - 2) else 0f
                val w = if (inChannels > 2) 0.6f else 1f
                out[2 * f] = clamp((input[base] * w + restAvg * (1 - w)).roundToInt())
                out[2 * f + 1] = clamp((input[base + 1] * w + restAvg * (1 - w)).roundToInt())
            }
        }
        return out
    }

    fun clamp(v: Int): Short = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    fun toBytes(samples: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        bb.asShortBuffer().put(samples)
        return bb.array()
    }
}

/**
 * Streaming linear-interpolation resampler for interleaved 16-bit PCM. Keeps
 * the last input frame between calls so chunk boundaries are seamless.
 */
class Resampler(private val inRate: Int, private val outRate: Int, private val channels: Int) {
    private val step = inRate.toDouble() / outRate
    /** Position of the next output frame, in input frames, relative to [prev]. */
    private var pos = 0.0
    private var prev: ShortArray? = null

    val isPassThrough get() = inRate == outRate

    fun process(input: ShortArray): ShortArray {
        if (isPassThrough || input.isEmpty()) return input
        val p = prev
        // Frame -1 is the carried frame (if any), frames 0..n-1 the new input.
        val n = input.size / channels
        fun sample(frame: Int, c: Int): Int =
            if (frame < 0) (p?.get(c)?.toInt() ?: input[c].toInt()) else input[frame * channels + c].toInt()
        val start = if (p == null) 0.0 else -1.0
        var x = if (p == null) pos else pos - 1.0
        if (x < start) x = start
        val frames = if (x > n - 1) 0 else ((n - 1 - x) / step).toInt() + 1
        val out = ShortArray(frames * channels)
        for (k in 0 until frames) {
            val i = kotlin.math.floor(x).toInt()
            val frac = x - i
            for (c in 0 until channels) {
                val a = sample(i, c)
                val b = if (i + 1 <= n - 1) sample(i + 1, c) else a
                out[k * channels + c] = Pcm.clamp((a + (b - a) * frac).roundToInt())
            }
            x += step
        }
        // Next call: its frame -1 is this call's last frame.
        pos = x - (n - 1)
        prev = input.copyOfRange((n - 1) * channels, n * channels)
        return out
    }
}
