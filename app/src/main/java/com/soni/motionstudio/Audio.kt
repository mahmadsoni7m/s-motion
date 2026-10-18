package com.soni.motionstudio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sin

const val BANDS = 32
const val AFPS = 30

fun fmtT(t: Float) = "%d:%02d".format((t / 60).toInt(), (t % 60).toInt())

class Analysis(private val data: FloatArray, val durSec: Float) {
    fun at(t: Float, out: FloatArray) {
        val f = (t * AFPS).toInt()
        if (f < 0 || (f + 1) * BANDS > data.size) { out.fill(0f); return }
        System.arraycopy(data, f * BANDS, out, 0, BANDS)
    }
}

fun synthBands(t: Float, out: FloatArray) {
    val k = (1.0 - ((t * 2f) % 1f)).pow(3).toFloat()
    for (i in out.indices) {
        val base = 0.25f + 0.2f * sin(i * 0.5f + t * 3f)
        out[i] = (base * (0.4f + 0.6f * k * (1f - i / out.size.toFloat() * 0.6f))).coerceIn(0f, 1f)
    }
}

object AudioDecoder {
    /** Decodes with MediaCodec to mono PCM, then analyses with the C++ (NDK) code. */
    fun decode(ctx: Context, uri: Uri, maxSec: Int = 300): Analysis? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; fmt = f; break }
            }
            if (idx < 0 || fmt == null) return null
            ex.selectTrack(idx)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var buf = ShortArray(sr * 30)
            var n = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone && n < sr * maxSec) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10000)
                    if (i >= 0) {
                        val ib = codec.getInputBuffer(i)!!
                        val sz = ex.readSampleData(ib, 0)
                        if (sz < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10000)
                if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = sb.remaining() / ch
                    for (f in 0 until frames) {
                        var sum = 0
                        for (c in 0 until ch) sum += sb.get()
                        if (n >= buf.size) buf = buf.copyOf(buf.size * 2)
                        buf[n++] = (sum / ch).toShort()
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    codec.releaseOutputBuffer(o, false)
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    sr = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            codec.stop(); codec.release()
            if (n < sr) return null
            return Analysis(Native.analyze(buf.copyOf(n), sr, AFPS, BANDS), n / sr.toFloat())
        } catch (e: Exception) {
            return null
        } finally {
            ex.release()
        }
    }
}
