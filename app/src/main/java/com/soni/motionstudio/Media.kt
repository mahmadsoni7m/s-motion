package com.soni.motionstudio

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.max
import kotlin.math.min

/** Reads frames of a user video so it can be used as the background (preview: fast keyframes, export: exact frames). */
class VideoBg(ctx: Context, uri: Uri) {
    private val r = MediaMetadataRetriever()
    private val durUs: Long
    private val vw: Int
    private val vh: Int

    init {
        r.setDataSource(ctx, uri)
        durUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1000L) * 1000L
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 720
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1280
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot == 90 || rot == 270) { vw = h; vh = w } else { vw = w; vh = h }
    }

    /** The video loops when the project is longer than the clip. */
    fun frame(tSec: Float, maxSide: Int, accurate: Boolean): Bitmap? {
        val us = (tSec * 1e6).toLong() % max(durUs, 1L)
        val k = min(1f, maxSide / max(vw, vh).toFloat())
        val sw = max(2, (vw * k).toInt()); val sh = max(2, (vh * k).toInt())
        val opt = if (accurate) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        return try { r.getScaledFrameAtTime(us, opt, sw, sh) } catch (e: Exception) { null }
    }

    fun close() { try { r.release() } catch (e: Exception) { } }
}

object BgVideo {
    @Volatile var exportSrc: VideoBg? = null
}
