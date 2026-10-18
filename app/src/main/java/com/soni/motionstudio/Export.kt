@file:OptIn(UnstableApi::class)

package com.soni.motionstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.opengl.GLES20
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

/** Frame source for Media3 Transformer: renders the selected style with the Canvas at the export size. */
class CardOverlay(private val w: Int, private val h: Int, private val spec: Spec, private val an: Analysis?, private val audioStart: Float) : BitmapOverlay() {
    private val bmps = arrayOf(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
    private var idx = 0
    private val bands = FloatArray(BANDS)
    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val t = presentationTimeUs / 1e6f
        idx = 1 - idx
        val bm = bmps[idx]
        bm.eraseColor(Color.BLACK)
        if (an != null) an.at(t + audioStart, bands) else synthBands(t, bands)
        StyleRenderer.draw(Canvas(bm), w, h, t, spec, bands)
        return bm
    }
}

/** OpenGL ES post effect: RGB split, beat glitch slices, vignette. */
class FxEffect(private val an: Analysis?, private val start: Float, private val glitch: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = FxProgram(an, start, glitch, useHdr)
}

class FxProgram(private val an: Analysis?, private val start: Float, private val glitch: Float, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private val gl = GlProgram(VS, FS)
    private val bands = FloatArray(BANDS)
    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)
    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val t = presentationTimeUs / 1e6f
        if (an != null) an.at(t + start, bands) else synthBands(t, bands)
        val env = (bands[0] + bands[1] + bands[2]) / 3f
        gl.use()
        gl.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        gl.setFloatUniform("uTime", t)
        gl.setFloatUniform("uEnv", env)
        gl.setFloatUniform("uGlitch", glitch)
        gl.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        gl.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
    override fun release() { super.release(); gl.delete() }
    companion object {
        const val VS = "attribute vec4 aFramePosition; varying vec2 vTexSamplingCoord; void main(){ gl_Position = aFramePosition; vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5; }"
        const val FS = "precision highp float; uniform sampler2D uTexSampler; uniform float uTime; uniform float uEnv; uniform float uGlitch; varying vec2 vTexSamplingCoord;\n" +
            "float h(float n){ return fract(sin(n * 12.9898) * 43758.5453); }\n" +
            "void main(){ vec2 uv = vTexSamplingCoord; float band = floor(uv.y * 24.0);\n" +
            "float g = step(0.82, h(band + floor(uTime * 12.0))) * uGlitch * uEnv; uv.x += g * (h(band) - 0.5) * 0.12;\n" +
            "float ab = 0.0008 + 0.004 * uEnv * uGlitch;\n" +
            "vec3 col = vec3(texture2D(uTexSampler, uv + vec2(ab, 0.0)).r, texture2D(uTexSampler, uv).g, texture2D(uTexSampler, uv - vec2(ab, 0.0)).b);\n" +
            "float v = smoothstep(0.95, 0.25, distance(uv, vec2(0.5))); col *= mix(0.6, 1.0, v); col += uEnv * 0.05;\n" +
            "gl_FragColor = vec4(col, 1.0); }"
    }
}

class Exporter(private val ctx: Context) {
    private var transformer: Transformer? = null

    fun start(spec: Spec, w: Int, h: Int, an: Analysis?, audioUri: Uri?, audioStart: Float, durSec: Float, glitch: Float, onDone: (File?, String?) -> Unit) {
        val out = File(ctx.cacheDir, "soni_${System.currentTimeMillis()}.mp4")
        val base = File(ctx.cacheDir, "black.png")
        if (!base.exists()) {
            val b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888); b.eraseColor(Color.BLACK)
            base.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val videoEffects = listOf<Effect>(
            Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
            OverlayEffect(ImmutableList.of<TextureOverlay>(CardOverlay(w, h, spec, an, audioStart))),
            FxEffect(an, audioStart, glitch)
        )
        // Image inputs need their duration on MediaItem, not EditedMediaItem.
        val imageItem = MediaItem.Builder()
            .setUri(Uri.fromFile(base))
            .setImageDurationMs((durSec * 1000f).toLong())
            .build()
        val video = EditedMediaItem.Builder(imageItem)
            .setFrameRate(30)
            .setEffects(Effects(emptyList(), videoEffects)).build()
        val seqs = mutableListOf(EditedMediaItemSequence(listOf(video)))
        if (audioUri != null) {
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs((audioStart * 1000).toLong()).setEndPositionMs(((audioStart + durSec) * 1000).toLong()).build()
            val ai = EditedMediaItem.Builder(MediaItem.Builder().setUri(audioUri).setClippingConfiguration(clip).build()).build()
            seqs.add(EditedMediaItemSequence(listOf(ai)))
        }
        val t = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { onDone(out, null) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { onDone(null, exportException.message ?: "unknown error") }
            }).build()
        transformer = t
        t.start(Composition.Builder(seqs).build(), out.absolutePath)
    }

    fun cancel() { transformer?.cancel() }

    fun progress(): Float {
        val t = transformer ?: return 0f
        val holder = ProgressHolder()
        return if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress / 100f else 0f
    }
}
