@file:OptIn(UnstableApi::class)

package com.soni.motionstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

private const val EXPORT_TAG = "SONI_EXPORT"

/** Renders the animated card for every exported frame. */
class CardOverlay(
    private val w: Int,
    private val h: Int,
    private val spec: Spec,
    private val an: Analysis?,
    private val audioStart: Float
) : BitmapOverlay() {

    private val bmps = arrayOf(
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    )

    private var idx = 0
    private val bands = FloatArray(BANDS)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val t = presentationTimeUs / 1_000_000f
        idx = 1 - idx
        val bm = bmps[idx]

        bm.eraseColor(Color.BLACK)

        if (an != null) {
            an.at(t + audioStart, bands)
        } else {
            synthBands(t, bands)
        }

        StyleRenderer.draw(Canvas(bm), w, h, t, spec, bands)
        return bm
    }
}

/** OpenGL ES post-processing effect. */
class FxEffect(
    private val an: Analysis?,
    private val start: Float,
    private val glitch: Float
) : GlEffect {

    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean
    ): GlShaderProgram {
        return FxProgram(an, start, glitch, useHdr)
    }
}

class FxProgram(
    private val an: Analysis?,
    private val start: Float,
    private val glitch: Float,
    useHdr: Boolean
) : BaseGlShaderProgram(useHdr, 1) {

    private val gl = GlProgram(VS, FS)
    private val bands = FloatArray(BANDS)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val t = presentationTimeUs / 1_000_000f

        if (an != null) {
            an.at(t + start, bands)
        } else {
            synthBands(t, bands)
        }

        val env = (bands[0] + bands[1] + bands[2]) / 3f

        gl.use()
        gl.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        gl.setFloatUniform("uTime", t)
        gl.setFloatUniform("uEnv", env)
        gl.setFloatUniform("uGlitch", glitch)

        gl.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )

        gl.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        super.release()
        gl.delete()
    }

    companion object {
        const val VS =
            "attribute vec4 aFramePosition;" +
            "varying vec2 vTexSamplingCoord;" +
            "void main(){" +
            "gl_Position=aFramePosition;" +
            "vTexSamplingCoord=aFramePosition.xy*0.5+0.5;" +
            "}"

        const val FS =
            "precision highp float;" +
            "uniform sampler2D uTexSampler;" +
            "uniform float uTime;" +
            "uniform float uEnv;" +
            "uniform float uGlitch;" +
            "varying vec2 vTexSamplingCoord;" +
            "float h(float n){" +
            "return fract(sin(n*12.9898)*43758.5453);" +
            "}" +
            "void main(){" +
            "vec2 uv=vTexSamplingCoord;" +
            "float band=floor(uv.y*24.0);" +
            "float g=step(0.82,h(band+floor(uTime*12.0)))" +
            "*uGlitch*uEnv;" +
            "uv.x+=g*(h(band)-0.5)*0.12;" +
            "float ab=0.0008+0.004*uEnv*uGlitch;" +
            "vec3 col=vec3(" +
            "texture2D(uTexSampler,uv+vec2(ab,0.0)).r," +
            "texture2D(uTexSampler,uv).g," +
            "texture2D(uTexSampler,uv-vec2(ab,0.0)).b);" +
            "float d=distance(uv,vec2(0.5));" +
            "float v=1.0-smoothstep(0.25,0.95,d);" +
            "col*=mix(0.6,1.0,v);" +
            "col+=uEnv*0.05;" +
            "gl_FragColor=vec4(col,1.0);" +
            "}"
    }
}

class Exporter(private val ctx: Context) {

    @Volatile
    private var transformer: Transformer? = null

    private fun audioDurationMs(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(ctx, uri)

            retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()
        } catch (e: Exception) {
            Log.e(EXPORT_TAG, "Cannot read audio duration: $uri", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun createBaseImage(): File {
        val base = File(ctx.cacheDir, "black.png")

        if (!base.exists() || base.length() == 0L) {
            val bitmap = Bitmap.createBitmap(
                16,
                16,
                Bitmap.Config.ARGB_8888
            )

            try {
                bitmap.eraseColor(Color.BLACK)

                base.outputStream().use { stream ->
                    check(
                        bitmap.compress(
                            Bitmap.CompressFormat.PNG,
                            100,
                            stream
                        )
                    ) {
                        "Could not encode the base PNG image"
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }

        check(base.exists() && base.length() > 0L) {
            "Base image is missing or empty"
        }

        return base
    }

    fun start(
        spec: Spec,
        w: Int,
        h: Int,
        an: Analysis?,
        audioUri: Uri?,
        audioStart: Float,
        durSec: Float,
        glitch: Float,
        onDone: (File?, String?) -> Unit
    ) {
        if (transformer != null) {
            onDone(null, "An export is already running")
            return
        }

        var out: File? = null

        try {
            require(w > 0 && h > 0) {
                "Invalid output dimensions: ${w}x$h"
            }

            require(durSec > 0f && durSec.isFinite()) {
                "Invalid export duration: $durSec"
            }

            require(audioStart >= 0f && audioStart.isFinite()) {
                "Invalid audio start position: $audioStart"
            }

            val durationMs = (durSec * 1000f).toLong()
            require(durationMs > 0L) {
                "Export duration is too short"
            }

            val base = createBaseImage()

            out = File(
                ctx.cacheDir,
                "soni_${System.currentTimeMillis()}.mp4"
            )

            if (out.exists()) out.delete()

            val videoEffects = listOf<Effect>(
                Presentation.createForWidthAndHeight(
                    w,
                    h,
                    Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                ),
                OverlayEffect(
                    ImmutableList.of<TextureOverlay>(
                        CardOverlay(w, h, spec, an, audioStart)
                    )
                ),
                FxEffect(an, audioStart, glitch)
            )

            // The duration of a still image belongs to MediaItem.
            val imageItem = MediaItem.Builder()
                .setUri(Uri.fromFile(base))
                .setImageDurationMs(durationMs)
                .build()

            val video = EditedMediaItem.Builder(imageItem)
                .setFrameRate(30)
                .setEffects(
                    Effects(
                        emptyList(),
                        videoEffects
                    )
                )
                .build()

            val sequences =
                mutableListOf(EditedMediaItemSequence(listOf(video)))

            if (audioUri != null) {
                val startMs = (audioStart * 1000f).toLong()
                val requestedEndMs = startMs + durationMs
                val sourceDurationMs = audioDurationMs(audioUri)

                // Never create an invalid clipping range.
                val endMs = if (sourceDurationMs != null) {
                    min(requestedEndMs, sourceDurationMs)
                } else {
                    requestedEndMs
                }

                if (endMs > startMs) {
                    val clipping = MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()

                    val audioItem = MediaItem.Builder()
                        .setUri(audioUri)
                        .setClippingConfiguration(clipping)
                        .build()

                    val audio = EditedMediaItem.Builder(audioItem)
                        .build()

                    sequences.add(
                        EditedMediaItemSequence(listOf(audio))
                    )
                } else {
                    Log.w(
                        EXPORT_TAG,
                        "Audio has no usable range. " +
                            "start=$startMs, end=$endMs"
                    )
                }
            }

            val composition = Composition.Builder(sequences).build()

            val newTransformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {

                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult
                    ) {
                        transformer = null

                        val resultFile = out

                        if (resultFile != null &&
                            resultFile.exists() &&
                            resultFile.length() > 0L
                        ) {
                            Log.i(
                                EXPORT_TAG,
                                "Export completed: ${resultFile.absolutePath}, " +
                                    "size=${resultFile.length()}"
                            )
                            onDone(resultFile, null)
                        } else {
                            onDone(
                                null,
                                "Export completed but output file is missing or empty"
                            )
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        transformer = null

                        val details = buildString {
                            appendLine("Media3 export failed")
                            appendLine(
                                "Message: ${
                                    exportException.message ?: "No message"
                                }"
                            )
                            appendLine(
                                "Cause: ${
                                    exportException.cause?.toString()
                                        ?: "No cause"
                                }"
                            )
                            appendLine(
                                "Output: ${out?.absolutePath}"
                            )
                        }

                        Log.e(
                            EXPORT_TAG,
                            details,
                            exportException
                        )

                        out?.let {
                            if (it.exists()) it.delete()
                        }

                        onDone(null, details)
                    }
                })
                .build()

            transformer = newTransformer

            Log.i(
                EXPORT_TAG,
                "Starting export: ${w}x${h}, " +
                    "duration=${durSec}s, audio=$audioUri"
            )

            newTransformer.start(
                composition,
                out.absolutePath
            )

        } catch (e: Exception) {
            transformer = null

            Log.e(EXPORT_TAG, "Could not start export", e)

            out?.let {
                if (it.exists()) it.delete()
            }

            onDone(
                null,
                "Could not start export: " +
                    "${e.javaClass.simpleName}: " +
                    "${e.message ?: "No details"}"
            )
        }
    }

    fun cancel() {
        try {
            transformer?.cancel()
        } catch (e: Exception) {
            Log.e(EXPORT_TAG, "Export cancellation failed", e)
        } finally {
            transformer = null
        }
    }

    fun progress(): Float {
        val current = transformer ?: return 0f
        val holder = ProgressHolder()

        return try {
            if (
                current.getProgress(holder) ==
                Transformer.PROGRESS_STATE_AVAILABLE
            ) {
                holder.progress / 100f
            } else {
                0f
            }
        } catch (e: Exception) {
            Log.e(EXPORT_TAG, "Cannot read export progress", e)
            0f
        }
    }
}
