package com.voicealarm.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pulls the sound out of a video (or any audio file) on the device and writes
 * it as an audio file, using only the platform codecs (no FFmpeg):
 *
 *  - AAC source  → copied into an .m4a container untouched (fast, lossless);
 *  - MP3 source  → copied into a raw .mp3 when MP3 output is asked for;
 *  - anything else the phone can decode (Opus, Vorbis, FLAC, AMR, PCM, AC-3
 *    on most devices, …) → decoded and re-encoded to AAC-LC in .m4a.
 *
 * Android has no built-in MP3 *encoder*, so MP3 output is only possible when
 * the source sound is already MP3; [probe] reports that up front.
 *
 * Call [convert] from a background thread.
 */
object AudioConverter {

    enum class Format(val extension: String, val mime: String) {
        AAC("m4a", "audio/mp4"),
        MP3("mp3", "audio/mpeg"),
    }

    /** What the picked file contains. */
    data class Info(
        val durationMs: Long,
        val audioMime: String,
        val sampleRate: Int,
        val channels: Int,
        val hasVideo: Boolean,
    ) {
        val canCopyToMp3 get() = audioMime == MIME_MP3
        val codecName: String
            get() = when (audioMime) {
                MIME_AAC -> "AAC"
                MIME_MP3 -> "MP3"
                "audio/opus" -> "Opus"
                "audio/vorbis" -> "Vorbis"
                "audio/flac" -> "FLAC"
                "audio/3gpp" -> "AMR-NB"
                "audio/amr-wb" -> "AMR-WB"
                "audio/raw" -> "PCM"
                "audio/ac3" -> "AC-3"
                "audio/eac3" -> "E-AC-3"
                else -> audioMime.substringAfter('/').uppercase()
            }
    }

    class ConversionException(val reason: Reason, cause: Throwable? = null) :
        Exception(if (cause == null) reason.name else "${reason.name}: $cause", cause)

    enum class Reason { NO_AUDIO, UNSUPPORTED, CANCELLED, EMPTY, FAILED }

    data class Result(val file: File, val durationMs: Long, val format: Format, val reencoded: Boolean)

    const val MIME_AAC = "audio/mp4a-latm"
    const val MIME_MP3 = "audio/mpeg"
    private const val TIMEOUT_US = 10_000L

    fun probe(context: Context, uri: Uri): Info {
        val ex = open(context, uri)
        try {
            var hasVideo = false
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) hasVideo = true
            }
            val track = audioTrack(ex) ?: throw ConversionException(Reason.NO_AUDIO)
            val f = ex.getTrackFormat(track)
            return Info(
                durationMs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L,
                audioMime = f.getString(MediaFormat.KEY_MIME).orEmpty(),
                sampleRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, 0),
                channels = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, 0),
                hasVideo = hasVideo,
            )
        } finally {
            ex.release()
        }
    }

    /**
     * Writes the audio between [startMs] and [endMs] (≤ 0 = to the end) of
     * [uri] into [out] (extension chosen by the caller from [format]).
     * [onProgress] gets 0..1. Throws [ConversionException].
     */
    fun convert(
        context: Context,
        uri: Uri,
        out: File,
        format: Format,
        startMs: Long = 0,
        endMs: Long = 0,
        cancel: AtomicBoolean = AtomicBoolean(false),
        onProgress: (Float) -> Unit = {},
    ): Result {
        val ex = open(context, uri)
        out.delete()
        try {
            val track = audioTrack(ex) ?: throw ConversionException(Reason.NO_AUDIO)
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val startUs = startMs.coerceAtLeast(0) * 1000
            val endUs = if (endMs > 0) endMs * 1000 else Long.MAX_VALUE
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val span = ((if (endUs == Long.MAX_VALUE) durUs else endUs) - startUs).coerceAtLeast(1)
            val progress = { ptsUs: Long -> onProgress(((ptsUs - startUs).toFloat() / span).coerceIn(0f, 1f)) }

            val result = when {
                format == Format.MP3 && mime == MIME_MP3 -> copyMp3(ex, out, startUs, endUs, cancel, progress)
                format == Format.MP3 -> throw ConversionException(Reason.UNSUPPORTED)
                mime == MIME_AAC && fmt.containsKey("csd-0") -> try {
                    remuxAac(ex, fmt, out, startUs, endUs, cancel, progress)
                } catch (e: ConversionException) {
                    throw e
                } catch (e: Exception) {
                    // Some containers' AAC tracks can't go into MP4 as-is: re-encode instead.
                    out.delete()
                    ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    transcodeToAac(ex, fmt, out, startUs, endUs, cancel, progress)
                }
                else -> transcodeToAac(ex, fmt, out, startUs, endUs, cancel, progress)
            }
            onProgress(1f)
            return result
        } catch (e: ConversionException) {
            out.delete()
            throw e
        } catch (e: Exception) {
            out.delete()
            throw ConversionException(if (cancel.get()) Reason.CANCELLED else Reason.FAILED, e)
        } finally {
            ex.release()
        }
    }

    // ---- paths -------------------------------------------------------------------------

    private fun copyMp3(ex: MediaExtractor, out: File, startUs: Long, endUs: Long, cancel: AtomicBoolean, progress: (Long) -> Unit): Result {
        val buf = ByteBuffer.allocate(1 shl 16)
        val chunk = ByteArray(1 shl 16)
        var first = -1L
        var last = 0L
        FileOutputStream(out).use { os ->
            while (true) {
                if (cancel.get()) throw ConversionException(Reason.CANCELLED)
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val pts = ex.sampleTime
                if (pts > endUs) break
                if (pts >= startUs - 30_000) {
                    if (first < 0) first = pts
                    last = pts
                    buf.position(0)
                    buf.get(chunk, 0, size)
                    os.write(chunk, 0, size)
                    progress(pts)
                }
                buf.clear()
                ex.advance()
            }
        }
        if (first < 0) throw ConversionException(Reason.EMPTY)
        return Result(out, (last - first) / 1000 + 26, Format.MP3, reencoded = false)
    }

    private fun remuxAac(ex: MediaExtractor, fmt: MediaFormat, out: File, startUs: Long, endUs: Long, cancel: AtomicBoolean, progress: (Long) -> Unit): Result {
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val outTrack = muxer.addTrack(fmt)
            muxer.start()
            started = true
            val cap = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buf = ByteBuffer.allocateDirect(maxOf(cap, 1 shl 16))
            val info = MediaCodec.BufferInfo()
            var first = -1L
            var last = 0L
            while (true) {
                if (cancel.get()) throw ConversionException(Reason.CANCELLED)
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val pts = ex.sampleTime
                if (pts > endUs) break
                if (pts >= startUs - 30_000) {
                    if (first < 0) first = pts
                    last = pts
                    info.set(0, size, pts - first, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(outTrack, buf, info)
                    progress(pts)
                }
                buf.clear()
                ex.advance()
            }
            if (first < 0) throw ConversionException(Reason.EMPTY)
            muxer.stop()
            started = false
            return Result(out, (last - first) / 1000 + 23, Format.AAC, reencoded = false)
        } finally {
            if (started) try { muxer.stop() } catch (_: Exception) { }
            muxer.release()
        }
    }

    private fun transcodeToAac(ex: MediaExtractor, fmt: MediaFormat, out: File, startUs: Long, endUs: Long, cancel: AtomicBoolean, progress: (Long) -> Unit): Result {
        val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
        val decoder = try {
            MediaCodec.createDecoderByType(mime).apply {
                configure(fmt, null, null, 0)
                start()
            }
        } catch (e: Exception) {
            throw ConversionException(Reason.UNSUPPORTED, e)
        }
        var sink: AacSink? = null
        try {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var srcRate = fmt.intOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var srcChannels = fmt.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var isFloat = false
            var resampler: Resampler? = null
            var idleAfterInput = 0
            while (true) {
                if (cancel.get()) throw ConversionException(Reason.CANCELLED)
                if (!inputDone) {
                    val idx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val inBuf = decoder.getInputBuffer(idx)!!
                        val size = ex.readSampleData(inBuf, 0)
                        val pts = ex.sampleTime
                        if (size < 0 || pts > endUs) {
                            decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(idx, 0, size, pts, 0)
                            ex.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && ++idleAfterInput > 500) break // decoder never signalled EOS
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = decoder.outputFormat
                    srcRate = of.intOr(MediaFormat.KEY_SAMPLE_RATE, srcRate)
                    srcChannels = of.intOr(MediaFormat.KEY_CHANNEL_COUNT, srcChannels)
                    isFloat = Build.VERSION.SDK_INT >= 24 && of.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0 && info.presentationTimeUs >= startUs && info.presentationTimeUs <= endUs) {
                        val outBuf = decoder.getOutputBuffer(o)!!
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        var pcm = Pcm.read(outBuf.slice(), isFloat)
                        val outCh = Pcm.targetChannels(srcChannels)
                        val outRate = Pcm.targetRate(srcRate)
                        val s = sink ?: AacSink(out, outRate, outCh).also { sink = it }
                        pcm = Pcm.downmix(pcm, srcChannels, outCh)
                        val r = resampler ?: Resampler(srcRate, outRate, outCh).also { resampler = it }
                        s.write(r.process(pcm), cancel)
                        progress(info.presentationTimeUs)
                    }
                    decoder.releaseOutputBuffer(o, false)
                    if (eos) break
                }
            }
            val s = sink ?: throw ConversionException(Reason.EMPTY)
            val durationMs = s.finish()
            sink = null
            if (durationMs <= 0) throw ConversionException(Reason.EMPTY)
            return Result(out, durationMs, Format.AAC, reencoded = true)
        } finally {
            sink?.abort()
            try { decoder.stop() } catch (_: Exception) { }
            decoder.release()
        }
    }

    // ---- helpers -----------------------------------------------------------------------

    private fun open(context: Context, uri: Uri): MediaExtractor {
        val ex = MediaExtractor()
        try {
            if (uri.scheme == "file") ex.setDataSource(uri.path!!) else ex.setDataSource(context, uri, null)
        } catch (e: Exception) {
            ex.release()
            throw ConversionException(Reason.UNSUPPORTED, e)
        }
        return ex
    }

    private fun audioTrack(ex: MediaExtractor): Int? =
        (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }

    private fun MediaFormat.intOr(key: String, default: Int) = if (containsKey(key)) getInteger(key) else default

    /** AAC-LC encoder + MP4 muxer fed with interleaved 16-bit PCM. */
    private class AacSink(out: File, private val rate: Int, private val channels: Int) {
        private val encoder: MediaCodec
        private val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        private var muxing = false
        private var framesIn = 0L
        private var lastPts = 0L

        init {
            val f = MediaFormat.createAudioFormat(MIME_AAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 128_000 else 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
            }
            encoder = try {
                MediaCodec.createEncoderByType(MIME_AAC).apply {
                    configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
            } catch (e: Exception) {
                muxer.release()
                throw ConversionException(Reason.UNSUPPORTED, e)
            }
        }

        fun write(samples: ShortArray, cancel: AtomicBoolean) {
            val bytes = Pcm.toBytes(samples)
            var off = 0
            while (off < bytes.size) {
                if (cancel.get()) throw ConversionException(Reason.CANCELLED)
                val idx = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    val buf = encoder.getInputBuffer(idx)!!
                    buf.clear()
                    // Whole frames only, so timestamps stay exact.
                    val frameBytes = 2 * channels
                    val n = (minOf(buf.remaining(), bytes.size - off) / frameBytes) * frameBytes
                    buf.put(bytes, off, n)
                    encoder.queueInputBuffer(idx, 0, n, framesIn * 1_000_000L / rate, 0)
                    framesIn += n / frameBytes
                    off += n
                }
                drain(false)
            }
        }

        /** Flushes the encoder and closes the file. Returns the duration in ms. */
        fun finish(): Long {
            var queued = false
            while (!queued) {
                val idx = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    encoder.queueInputBuffer(idx, 0, 0, framesIn * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                }
                drain(false)
            }
            drain(true)
            encoder.stop()
            encoder.release()
            if (muxing) muxer.stop()
            muxer.release()
            return if (muxing) framesIn * 1000 / rate else 0
        }

        fun abort() {
            try { encoder.stop() } catch (_: Exception) { }
            encoder.release()
            try { if (muxing) muxer.stop() } catch (_: Exception) { }
            muxer.release()
        }

        private fun drain(untilEos: Boolean) {
            var idle = 0
            while (true) {
                val o = encoder.dequeueOutputBuffer(info, if (untilEos) TIMEOUT_US else 0)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos || ++idle > 500) return
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    o >= 0 -> {
                        val buf = encoder.getOutputBuffer(o)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && muxing) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            // The muxer needs strictly increasing timestamps.
                            if (info.presentationTimeUs <= lastPts && lastPts > 0) info.presentationTimeUs = lastPts + 1
                            lastPts = info.presentationTimeUs
                            muxer.writeSampleData(track, buf, info)
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(o, false)
                        if (eos) return
                    }
                }
            }
        }
    }
}
