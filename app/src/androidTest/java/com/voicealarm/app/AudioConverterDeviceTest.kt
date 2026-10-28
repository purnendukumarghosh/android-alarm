package com.voicealarm.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicealarm.app.audio.AudioConverter
import com.voicealarm.app.audio.AudioConverter.Format
import com.voicealarm.app.audio.RingtoneExporter
import com.voicealarm.app.audio.SoundLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Runs the real platform codecs on a device / emulator against small test
 * videos (made with FFmpeg, see app/src/androidTest/assets/media/README.md).
 */
@RunWith(AndroidJUnit4::class)
class AudioConverterDeviceTest {
    private val app: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx: Context = InstrumentationRegistry.getInstrumentation().context

    private fun fixture(name: String): Uri {
        val f = File(app.cacheDir, "fixtures/$name")
        f.parentFile!!.mkdirs()
        testCtx.assets.open("media/$name").use { input -> f.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(f)
    }

    private fun out(name: String) = File(app.cacheDir, "out/$name").apply { parentFile!!.mkdirs(); delete() }

    /** Checks the output is a single playable audio track of [mime], [expectMs] long (±[tolMs]). */
    private fun assertAudio(file: File, mime: String, expectMs: Long, tolMs: Long = 250) {
        assertTrue("output missing: $file", file.exists() && file.length() > 0)
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        assertEquals("tracks in $file", 1, ex.trackCount)
        val f = ex.getTrackFormat(0)
        assertEquals(mime, f.getString(MediaFormat.KEY_MIME))
        assertTrue("channels ≤ 2", f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) in 1..2)
        assertTrue("rate ≤ 48 kHz", f.getInteger(MediaFormat.KEY_SAMPLE_RATE) in 8_000..48_000)
        // Count the real samples instead of trusting the header duration.
        ex.selectTrack(0)
        val buf = ByteBuffer.allocate(1 shl 16)
        var first = -1L
        var last = 0L
        while (ex.readSampleData(buf, 0) >= 0) {
            if (first < 0) first = ex.sampleTime
            last = ex.sampleTime
            ex.advance()
        }
        ex.release()
        val dur = (last - first) / 1000
        Log.i(TAG, "${file.name}: $mime ${f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}ch ${f.getInteger(MediaFormat.KEY_SAMPLE_RATE)}Hz ${dur}ms ${file.length()}B")
        assertTrue("duration ${dur}ms, expected ~${expectMs}ms", abs(dur - expectMs) <= tolMs)
        // And Android's own player accepts it.
        val p = MediaPlayer()
        try {
            p.setDataSource(file.absolutePath)
            p.prepare()
            assertTrue(p.duration > 0)
        } finally {
            p.release()
        }
    }

    private fun convert(name: String, format: Format, startMs: Long = 0, endMs: Long = 0): AudioConverter.Result {
        val o = out(name.substringBeforeLast('.') + "_${startMs}_$endMs." + format.extension)
        var lastProgress = 0f
        val r = try {
            AudioConverter.convert(app, fixture(name), o, format, startMs, endMs) { lastProgress = it }
        } catch (e: AudioConverter.ConversionException) {
            if (e.reason != AudioConverter.Reason.FAILED) throw e
            throw AssertionError("conversion of $name failed:\n" + Log.getStackTraceString(e), e)
        }
        assertEquals(1f, lastProgress)
        return r
    }

    @Test
    fun aacVideo_isCopiedWithoutReencoding() {
        val r = convert("h264_aac.mp4", Format.AAC)
        assertTrue(!r.reencoded)
        assertAudio(r.file, AudioConverter.MIME_AAC, 5_000)
    }

    @Test
    fun aacVideo_clipIsTrimmed() {
        val r = convert("h264_aac.mp4", Format.AAC, startMs = 1_000, endMs = 3_500)
        assertAudio(r.file, AudioConverter.MIME_AAC, 2_500)
    }

    @Test
    fun opusMkv_isReencodedToAac() {
        val r = convert("vp9_opus.mkv", Format.AAC)
        assertTrue(r.reencoded)
        assertAudio(r.file, AudioConverter.MIME_AAC, 3_000, tolMs = 300)
    }

    @Test
    fun vorbisWebm_isReencodedToAac_andTrimmed() {
        val r = convert("vp8_vorbis.webm", Format.AAC, startMs = 500, endMs = 2_500)
        assertAudio(r.file, AudioConverter.MIME_AAC, 2_000, tolMs = 300)
    }

    @Test
    fun mp3InMp4_canBeCopiedToMp3() {
        val info = AudioConverter.probe(app, fixture("h264_mp3.mp4"))
        assertTrue(info.canCopyToMp3)
        val r = convert("h264_mp3.mp4", Format.MP3)
        assertAudio(r.file, AudioConverter.MIME_MP3, 3_000, tolMs = 300)
    }

    @Test
    fun mp3InMp4_canAlsoBecomeAac() {
        val r = convert("h264_mp3.mp4", Format.AAC, endMs = 2_000)
        assertAudio(r.file, AudioConverter.MIME_AAC, 2_000, tolMs = 300)
    }

    @Test
    fun surround96kFlac_isDownmixedAndResampled() {
        val info = AudioConverter.probe(app, fixture("h264_flac51_96k.mkv"))
        assertEquals(6, info.channels)
        assertEquals(96_000, info.sampleRate)
        val r = convert("h264_flac51_96k.mkv", Format.AAC)
        assertAudio(r.file, AudioConverter.MIME_AAC, 3_000, tolMs = 300)
    }

    @Test
    fun plainMp3Song_becomesAac() {
        val info = AudioConverter.probe(app, fixture("song.mp3"))
        assertTrue(!info.hasVideo)
        val r = convert("song.mp3", Format.AAC)
        assertAudio(r.file, AudioConverter.MIME_AAC, 3_000, tolMs = 300)
    }

    @Test
    fun videoWithoutSound_isReported() {
        try {
            AudioConverter.probe(app, fixture("silent_video.mp4"))
            fail("expected NO_AUDIO")
        } catch (e: AudioConverter.ConversionException) {
            assertEquals(AudioConverter.Reason.NO_AUDIO, e.reason)
        }
    }

    @Test
    fun mp3Output_fromNonMp3Source_isRefused() {
        try {
            convert("vp9_opus.mkv", Format.MP3)
            fail("expected UNSUPPORTED")
        } catch (e: AudioConverter.ConversionException) {
            assertEquals(AudioConverter.Reason.UNSUPPORTED, e.reason)
        }
    }

    @Test
    fun convertedClip_goesIntoLibrary_andPhoneRingtones() {
        val r = convert("vp9_opus.mkv", Format.AAC)
        val sound = SoundLibrary.addConverted(app, r.file, "Test tone", r.durationMs)
        try {
            assertNotNull(SoundLibrary.fileFor(app, sound.id))
            val uri = RingtoneExporter.export(app, sound.file(app), "Voice Alarm test tone", Format.AAC.mime)
            assertNotNull("MediaStore insert failed", uri)
            // The exported ringtone is readable through its content Uri.
            val info = AudioConverter.probe(app, uri!!)
            assertEquals(AudioConverter.MIME_AAC, info.audioMime)
            app.contentResolver.delete(uri, null, null)
        } finally {
            SoundLibrary.delete(app, sound.id)
        }
    }

    private companion object {
        const val TAG = "ConverterTest"
    }
}
