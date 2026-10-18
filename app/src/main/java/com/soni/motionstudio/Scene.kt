package com.soni.motionstudio

import android.graphics.*
import kotlin.math.*

/** Full frame pipeline: filtered/camera-moved background, effects, layout card, text overlay. Used by preview and export. */
object Scene {
    private val rng = java.util.Random(5)
    private val r1 = FloatArray(400) { rng.nextFloat() }
    private val r2 = FloatArray(400) { rng.nextFloat() }
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    private var small: Bitmap? = null
    private val fonts by lazy {
        listOf(Typeface.SANS_SERIF, Typeface.SERIF, Typeface.MONOSPACE, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC))
            .map { Typeface.create(it, Typeface.BOLD) }
    }

    private fun p(): Paint { pt.reset(); pt.isAntiAlias = true; return pt }
    private fun a(c: Int, alpha: Int) = (c and 0xFFFFFF) or (alpha shl 24)
    private fun lv(b: FloatArray, i: Int, n: Int) = b[min(b.size - 1, i * b.size / n)]

    private fun filter(g: Cfg): ColorMatrixColorFilter {
        val cm = ColorMatrix(); cm.setSaturation(g.sat)
        val k = g.contrast; val o = 128f * (1 - k)
        cm.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, o, 0f, k, 0f, 0f, o, 0f, 0f, k, 0f, o, 0f, 0f, 0f, 1f, 0f)))
        val br = ColorMatrix(); br.setScale(g.bright, g.bright, g.bright, 1f); cm.postConcat(br)
        return ColorMatrixColorFilter(cm)
    }

    fun draw(c: Canvas, w: Int, h: Int, t: Float, s: Spec, b: FloatArray) {
        val g = s.cfg; val W = w.toFloat(); val H = h.toFloat(); val m = min(W, H)
        val fx = g.fx; val e = g.fxi; val sp = t * g.speed; val D = max(1f, s.dur)
        val bass = (b[0] + b[1] + b[2]) / 3f
        val frame = if (g.useVideo) (s.bgFrame ?: BgVideo.exportSrc?.frame(t, max(w, h), true)) else null
        val filtered = g.bright != 1f || g.contrast != 1f || g.sat != 1f
        val layer = if (filtered) { val lp = Paint(); lp.colorFilter = filter(g); c.saveLayer(0f, 0f, W, H, lp) } else c.save()
        var z = g.zoom
        if ("zoomin" in fx) z *= 1f + .2f * t / D * g.motion
        if ("zoomout" in fx) z *= 1f + .2f * (1f - t / D) * g.motion
        if ("pulse" in fx) z *= 1f + .05f * bass * e
        var sx = 0f; var sy = 0f
        if ("pan" in fx) { sx = sin(sp * .4f) * W * .04f * g.motion; sy = cos(sp * .3f) * H * .03f * g.motion }
        if ("shake" in fx) { sx += (rng.nextFloat() - .5f) * W * .012f * g.motion * (1 + bass * 3); sy += (rng.nextFloat() - .5f) * H * .012f * g.motion * (1 + bass * 3) }
        c.translate(W / 2 + sx, H / 2 + sy); c.rotate(g.rot); c.scale(z, z); c.translate(-W / 2, -H / 2)
        if (g.blur > 0.2f) {
            val k = 1f / (1f + g.blur * 1.5f)
            val sw = max(2, (W * k).toInt()); val sh = max(2, (H * k).toInt())
            var bm = small
            if (bm == null || bm.width != sw || bm.height != sh) { bm = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888); small = bm }
            bm.eraseColor(0)
            val sc = Canvas(bm); sc.scale(k, k)
            backdrop(sc, W, H, sp, s, bass, frame)
            val q = p(); q.isFilterBitmap = true
            val bg = p(); bg.color = g.base; c.drawRect(-W, -H, 2 * W, 2 * H, bg)
            c.drawBitmap(bm, null, RectF(0f, 0f, W, H), q)
        } else backdrop(c, W, H, sp, s, bass, frame)
        c.restoreToCount(layer)
        effects(c, W, H, t, s, b, bass)
        if (g.style != Style.NONE) StyleRenderer.draw(c, w, h, t, s, b)
        if (g.text.isNotEmpty()) {
            val q = p(); q.color = g.tcolor; q.textSize = g.tsize / 100f * m; q.textAlign = Paint.Align.CENTER; q.typeface = fonts[g.font]
            q.setShadowLayer(m * .01f, 0f, 0f, 0xAA000000.toInt())
            val tw = q.measureText(g.text); if (tw > W * .95f) q.textScaleX = W * .95f / tw
            c.drawText(g.text, g.tx / 100f * W, g.ty / 100f * H + q.textSize * .35f, q)
        }
    }

    private fun gridFloor(c: Canvas, W: Float, H: Float, t: Float, acc: Int) {
        val hz = H * .58f; val q = p(); q.color = acc; q.style = Paint.Style.STROKE; q.strokeWidth = max(1f, W * .002f); q.alpha = 180
        for (i in -10..10) c.drawLine(W / 2 + i * W * .04f, hz, W / 2 + i * W * .4f, H, q)
        for (j in 0 until 10) { val pp = ((j + t * .5f) % 10f) / 10f; val y = hz + (H - hz) * pp * pp; c.drawLine(0f, y, W, y, q) }
    }

    private fun backdrop(c: Canvas, W: Float, H: Float, t: Float, s: Spec, bass: Float, frame: Bitmap?) {
        val g = s.cfg; val acc = g.accent; val m = min(W, H)
        var q = p(); q.color = g.base; c.drawRect(-W, -H, 2 * W, 2 * H, q)
        if (frame != null) { StyleRenderer.cropFill(c, frame, W, H); q = p(); q.color = 0x55000000; c.drawRect(-W, -H, 2 * W, 2 * H, q); return }
        if (g.useImage && s.cover != null) { StyleRenderer.backdrop(c, W, H, s); return }
        val def = BGS[g.bg.coerceIn(0, BGS.size - 1)]
        q = p(); q.shader = RadialGradient(W / 2, H * .7f, H * .8f, a(acc, 0x88), a(acc, 0), Shader.TileMode.CLAMP); c.drawRect(-W, -H, 2 * W, 2 * H, q)
        when (def.kind) {
            "blobs" -> for (i in 0 until 6) {
                val cx = W * (.5f + .4f * sin(t * .3f + i * 2)); val cy = H * (.5f + .4f * cos(t * .25f + i * 3)); val r = m * (.3f + r1[i] * .3f)
                q = p(); q.shader = RadialGradient(cx, cy, r, a(acc, 0xAA), a(acc, 0), Shader.TileMode.CLAMP); c.drawCircle(cx, cy, r, q)
            }
            "city" -> for (i in 0 until 26) {
                val bw = W / 14; val bh = H * (.12f + r1[i] * .38f); val bx = i * W / 22 - bw * .3f
                q = p(); q.color = 0xCC000000.toInt(); c.drawRect(bx, H - bh, bx + bw, H, q)
                q = p(); q.color = a(acc, 0xBB)
                for (j in 0 until 8) if (r2[i * 8 + j] > .6f) {
                    val wx = bx + bw * .15f + (j % 3) * bw * .28f; val wy = H - bh + bh * .08f * (1 + (j / 3) * 2)
                    c.drawRect(wx, wy, wx + bw * .14f, wy + bh * .04f, q)
                }
            }
            "mount" -> for (l in 0 until 3) {
                val path = Path(); path.moveTo(-W, H)
                var x = -W
                while (x <= 2 * W) { path.lineTo(x, H * (.55f + .12f * l) + sin(x * .008f * (l + 1) + l * 2) * H * .06f + sin(x * .03f + l) * H * .015f); x += W / 40 }
                path.lineTo(2 * W, H); path.close()
                q = p(); q.color = if (l == 0) a(acc, 0x44) else Color.argb((.3f + l * .25f).times(255).toInt(), 0, 0, 0); c.drawPath(path, q)
            }
            "waves" -> for (l in 0 until 6) {
                val path = Path(); path.moveTo(0f, H)
                var x = 0f
                while (x <= W) { path.lineTo(x, H * (.35f + .1f * l) + sin(x / W * 6f + t * .8f + l) * H * .03f); x += W / 40 }
                path.lineTo(W, H); path.close()
                q = p(); q.color = a(acc, 0x26); c.drawPath(path, q)
            }
            "sun" -> {
                val cy = H * .45f; val r = m * .28f
                q = p(); q.shader = LinearGradient(0f, cy - r, 0f, cy + r, 0xFFFDE047.toInt(), acc, Shader.TileMode.CLAMP); c.drawCircle(W / 2, cy, r, q)
                q = p(); q.color = g.base
                for (i in 0 until 6) c.drawRect(W / 2 - r, cy + r * (.1f + i * .15f), W / 2 + r, cy + r * (.1f + i * .15f) + r * .03f * (i + 1), q)
                c.drawRect(0f, H * .6f, W, H, q)
            }
            "grid" -> gridFloor(c, W, H, t, acc)
            "tunnel" -> for (i in 0 until 16) {
                val pp = ((i + t * .7f) % 16f) / 16f; val r = pp * pp * m * 1.1f
                q = p(); q.style = Paint.Style.STROKE; q.strokeWidth = m * .004f; q.color = a(acc, (pp * 200 + 30).toInt()); c.drawRect(W / 2 - r, H / 2 - r, W / 2 + r, H / 2 + r, q)
            }
            "rain" -> {
                q = p(); q.color = 0x55FFFFFF; q.strokeWidth = max(1f, m * .002f)
                for (i in 0 until 140) { val px = r1[i % 400] * W; val py = (r2[i] * H + t * H * .5f * (.5f + r1[i])) % H; c.drawLine(px, py, px - 3f, py + m * .04f, q) }
            }
        }
    }

    private fun effects(c: Canvas, W: Float, H: Float, t: Float, s: Spec, b: FloatArray, bass: Float) {
        val g = s.cfg; val fx = g.fx; val acc = g.accent; val e = g.fxi; val m = min(W, H); val sp = t * g.speed
        val gl = if ("glow" in fx) g.glow else 0f
        fun fp(stroke: Boolean): Paint {
            val q = p(); q.color = acc; q.strokeWidth = max(2f, m * .006f); q.strokeCap = Paint.Cap.ROUND
            q.style = if (stroke) Paint.Style.STROKE else Paint.Style.FILL
            if (gl > 0f) q.setShadowLayer(max(1f, gl * m * .03f), 0f, 0f, acc)
            return q
        }
        if ("grid" in fx && BGS[g.bg.coerceIn(0, BGS.size - 1)].kind != "grid") gridFloor(c, W, H, sp, acc)
        if ("particles" in fx) {
            val q = fp(false); val n = (160 * g.dens).toInt().coerceIn(0, 399)
            for (i in 0 until n) {
                val x = (r1[i] * W + sin(sp * .5f + i) * m * .03f + W) % W
                val y = (((r2[i] - sp * .04f * (.3f + r1[i])) % 1f) + 1f) % 1f * H
                q.alpha = (80 + 140 * r2[(i + 9) % 400]).toInt()
                c.drawCircle(x, y, m * .004f * (1 + r1[i] * 2) * (1 + bass * e), q)
            }
        }
        if ("stars" in fx) {
            val q = p(); q.color = Color.WHITE
            for (i in 0 until 120) {
                val pp = (r1[i] + sp * .15f * e) % 1f; val r = pp * pp
                val x = W / 2 + (r2[i] - .5f) * W * 2.2f * r; val y = H / 2 + (r1[(i + 50) % 400] - .5f) * H * 2.2f * r
                q.alpha = (255 * r).toInt().coerceIn(0, 255); c.drawRect(x, y, x + 1f + m * .006f * r, y + 1f + m * .006f * r, q)
            }
        }
        if ("geo" in fx) for (k in 0 until 4) {
            c.save(); c.translate(W / 2, H / 2); c.rotate(((sp * .4f * (if (k % 2 == 1) -1 else 1) + k) * 57.2958f))
            val r = m * (.15f + .07f * k) * (1f + bass * e * .3f); val n = 3 + k; val path = Path()
            for (i in 0..n) { val x = cos(i.toFloat() / n * 6.2832f) * r; val y = sin(i.toFloat() / n * 6.2832f) * r; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            c.drawPath(path, fp(true)); c.restore()
        }
        if ("bars" in fx) {
            val q = fp(false); val n = 32; val bw = W / n; q.alpha = 220
            for (i in 0 until n) { val hh = max(3f, lv(b, i, n) * H * .35f * e); c.drawRect(i * bw + bw * .15f, H - hh - H * .04f, i * bw + bw * .85f, H - H * .04f, q) }
        }
        if ("wave" in fx) {
            val path = Path()
            for (i in 0 until 64) { val x = i / 63f * W; val y = H * .5f + sin(i * .5f + sp * 3f) * lv(b, i, 64) * H * .15f * e; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            c.drawPath(path, fp(true))
        }
        if ("ring" in fx) {
            val q = fp(true); val r0 = m * .2f * (1f + bass * .15f * e)
            for (i in 0 until 64) {
                val an = i / 64f * 6.2832f + sp * .2f; val l = lv(b, i, 64) * m * .18f * e + m * .01f
                c.drawLine(W / 2 + cos(an) * r0, H / 2 + sin(an) * r0, W / 2 + cos(an) * (r0 + l), H / 2 + sin(an) * (r0 + l), q)
            }
        }
        if ("sweep" in fx) {
            val pp = (sp * .25f % 1f) * 3f - 1f
            val q = p(); q.shader = LinearGradient((pp - .2f) * W, 0f, (pp + .2f) * W, H * .4f, intArrayOf(0x00FFFFFF, 0x40FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H, q)
        }
    }
}
