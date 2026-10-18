package com.soni.motionstudio

import android.graphics.*
import kotlin.math.*

enum class Style(val label: String) { PLAYER("Player card"), GLASS("Glass cover"), NEON("Neon timer"), RING("Ring logo"), NONE("Effects only"), MINIMAL("Minimal wave"), VINYL("Vinyl"), ORBIT("Orbit"), STACK("Bars wall") }

class Spec(val style: Style, val accent: Int, val title: String, val artist: String, val handle: String, val dur: Float, val fps: Int, val cover: Bitmap?, val cfg: Cfg = Cfg(), val bgFrame: Bitmap? = null) {
    fun withFrame(f: Bitmap?) = Spec(style, accent, title, artist, handle, dur, fps, cover, cfg, f)
}

/** Draws one frame of every style with the Android Canvas. Used for both preview and export. */
object StyleRenderer {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = java.util.Random(11)
    private val px = FloatArray(120) { rnd.nextFloat() }
    private val py = FloatArray(120) { rnd.nextFloat() }
    private val ps = FloatArray(120) { rnd.nextFloat() }
    private var blurSrc: Bitmap? = null
    private var blur: Bitmap? = null

    private fun pr(): Paint { p.reset(); p.isAntiAlias = true; return p }
    private fun lvl(b: FloatArray, i: Int, n: Int) = b[min(b.size - 1, i * b.size / n)]

    private fun crop(c: Canvas, b: Bitmap, x: Float, y: Float, w: Float, h: Float, paint: Paint?) {
        val s = max(w / b.width, h / b.height)
        val sw = w / s; val sh = h / s
        val sx = (b.width - sw) / 2; val sy = (b.height - sh) / 2
        c.drawBitmap(b, Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()), RectF(x, y, x + w, y + h), paint)
    }

    private fun blurred(b: Bitmap): Bitmap {
        if (blurSrc !== b) { blurSrc = b; blur = Bitmap.createScaledBitmap(b, 24, max(1, 24 * b.height / b.width), true) }
        return blur!!
    }

    private fun bg(c: Canvas, w: Float, h: Float, s: Spec, dim: Int, mono: Boolean) {
        c.drawColor(0xFF050507.toInt())
        val cv = s.cover
        if (cv != null) {
            val q = pr(); q.isFilterBitmap = true
            if (mono) {
                val cm = ColorMatrix(); cm.setSaturation(0f)
                val r = Color.red(s.accent) / 255f * 1.4f; val g = Color.green(s.accent) / 255f * 1.4f; val bl = Color.blue(s.accent) / 255f * 1.4f
                cm.postConcat(ColorMatrix(floatArrayOf(r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, bl, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
                q.colorFilter = ColorMatrixColorFilter(cm)
                crop(c, cv, 0f, 0f, w, h, q)
            } else crop(c, blurred(cv), 0f, 0f, w, h, q)
        }
        var q = pr(); q.color = dim; c.drawRect(0f, 0f, w, h, q)
        q = pr(); q.shader = RadialGradient(w / 2, h * .72f, h * .7f, (s.accent and 0xFFFFFF) or 0x55000000, 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, q)
    }

    fun backdrop(c: Canvas, W: Float, H: Float, s: Spec) {
        when (s.style) {
            Style.PLAYER -> bg(c, W, H, s, 0xB0000000.toInt(), false)
            Style.GLASS -> bg(c, W, H, s, 0x99000000.toInt(), false)
            Style.NEON -> bg(c, W, H, s, 0x66000000, true)
            Style.RING -> bg(c, W, H, s, 0xCC000000.toInt(), false)
            Style.MINIMAL -> bg(c, W, H, s, 0xDD101010.toInt(), false)
            Style.VINYL -> bg(c, W, H, s, 0xAA000000.toInt(), false)
            Style.ORBIT -> bg(c, W, H, s, 0x99000000.toInt(), false)
            Style.STACK -> bg(c, W, H, s, 0xB0000000.toInt(), false)
            Style.NONE -> s.cover?.let { val q = pr(); q.isFilterBitmap = true; crop(c, it, 0f, 0f, W, H, q) }
        }
    }

    private fun text(c: Canvas, str: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean, align: Paint.Align, maxW: Float = 1e9f, serif: Boolean = false) {
        val q = pr(); q.textSize = size; q.color = color; q.textAlign = align
        q.typeface = Typeface.create(if (serif) Typeface.SERIF else Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        val tw = q.measureText(str)
        if (tw > maxW) q.textScaleX = maxW / tw
        c.drawText(str, x, y, q)
    }

    private fun cover(c: Canvas, s: Spec, x: Float, y: Float, sz: Float, rad: Float) {
        c.save()
        val path = Path(); path.addRoundRect(RectF(x, y, x + sz, y + sz), rad, rad, Path.Direction.CW); c.clipPath(path)
        val cv = s.cover
        val q = pr()
        if (cv != null) { q.isFilterBitmap = true; crop(c, cv, x, y, sz, sz, q) }
        else { q.shader = LinearGradient(x, y, x + sz, y + sz, s.accent, 0xFF111118.toInt(), Shader.TileMode.CLAMP); c.drawRect(x, y, x + sz, y + sz, q) }
        c.restore()
    }

    private fun particles(c: Canvas, w: Float, h: Float, t: Float, acc: Int, bass: Float, n: Int) {
        val q = pr(); q.color = acc
        for (i in 0 until n) {
            val x = px[i] * w + sin(t * .6f + i) * w * .02f
            val y = (((py[i] - t * .04f * (.3f + ps[i])) % 1f) + 1f) % 1f * h
            q.alpha = (80 + 150 * ps[i]).toInt()
            val r = min(w, h) * .004f * (1 + ps[i] * 2) * (1 + bass)
            if (i % 5 == 0) { q.strokeWidth = r * .8f; c.drawLine(x - r * 2, y, x + r * 2, y, q); c.drawLine(x, y - r * 2, x, y + r * 2, q) }
            else c.drawCircle(x, y, r, q)
        }
    }

    private fun player(c: Canvas, W: Float, H: Float, t: Float, s: Spec, b: FloatArray, bass: Float) {
                val cw = min(W * .9f, H * 1.05f); val ch = cw * .48f
        c.save(); c.translate(W / 2, H * .45f); c.rotate(-1.5f); val k = 1f + .025f * bass; c.scale(k, k)
        val r = RectF(-cw / 2, -ch / 2, cw / 2, ch / 2)
        var q = pr(); q.color = 0xDD0A0A0C.toInt(); q.setShadowLayer(cw * .05f * (.4f + bass), 0f, 0f, s.accent)
        c.drawRoundRect(r, cw * .04f, cw * .04f, q)
        q = pr(); q.style = Paint.Style.STROKE; q.strokeWidth = cw * .004f; q.color = s.accent; c.drawRoundRect(r, cw * .04f, cw * .04f, q)
        val sz = ch * .74f; val x0 = -cw / 2 + cw * .04f; val y0 = -ch / 2 + ch * .13f
        cover(c, s, x0, y0, sz, cw * .02f)
        val tx = x0 + sz + cw * .05f; val rt = cw / 2 - cw * .04f; val bw = rt - tx
        text(c, s.title, tx, y0 + sz * .12f, cw * .05f, Color.WHITE, true, Paint.Align.LEFT, bw)
        text(c, "Artist : " + s.artist, tx, y0 + sz * .26f, cw * .04f, Color.WHITE, false, Paint.Align.LEFT, bw)
        text(c, "Slowed Audios", tx, y0 + sz * .36f, cw * .03f, 0xAAFFFFFF.toInt(), false, Paint.Align.LEFT, bw)
        val by = y0 + sz * .56f; val bh = sz * .26f; val n = 34
        q = pr(); q.style = Paint.Style.STROKE; q.strokeWidth = cw * .003f; q.color = 0x55FFFFFF
        c.drawRoundRect(RectF(tx - cw * .01f, by - bh * .7f, rt + cw * .01f, by + bh * .7f), bh * .7f, bh * .7f, q)
        val prog = t / s.dur
        for (i in 0 until n) {
            val v = .2f + .8f * (.35f + .65f * lvl(b, i, n)) * (.5f + .5f * px[i])
            q = pr(); q.color = if (i / n.toFloat() < prog) s.accent else 0x66FFFFFF
            c.drawRect(tx + i * bw / n, by - bh * v / 2, tx + i * bw / n + bw / n * .5f, by + bh * v / 2, q)
        }
        val cy = y0 + sz * .86f; val u = cw * .02f
        text(c, "\u2665", tx + u, cy + u * .8f, cw * .045f, Color.WHITE, false, Paint.Align.CENTER)
        val mx = tx + bw * .5f; q = pr(); q.color = Color.WHITE
        c.drawRect(mx - u * .9f, cy - u, mx - u * .2f, cy + u, q); c.drawRect(mx + u * .2f, cy - u, mx + u * .9f, cy + u, q)
        for (d in intArrayOf(-1, 1)) {
            val x = mx + d * bw * .24f; val path = Path()
            path.moveTo(x - d * u, cy - u); path.lineTo(x + d * u, cy); path.lineTo(x - d * u, cy + u); path.close(); c.drawPath(path, q)
        }
        c.drawCircle(rt - u, cy - u * .8f, u * .15f, q); c.drawCircle(rt - u, cy, u * .15f, q); c.drawCircle(rt - u, cy + u * .8f, u * .15f, q)
        val ry = ch * .44f
        text(c, fmtT(t), tx, ry, cw * .03f, Color.WHITE, false, Paint.Align.LEFT)
        text(c, "${s.fps} Fps", tx + bw / 2, ry, cw * .03f, Color.WHITE, false, Paint.Align.CENTER)
        text(c, s.handle, rt, ry, cw * .03f, Color.WHITE, false, Paint.Align.RIGHT)
        val ne = 48; q = pr(); q.color = s.accent
        for (i in 0 until ne) {
            val hh = ch * .04f + ch * .3f * lvl(b, i, ne); val x = -cw / 2 + cw * i / ne
            c.drawRect(x, ch / 2 + ch * .03f, x + cw / ne * .5f, ch / 2 + ch * .03f + hh, q)
        }
        c.restore()
    }

    private fun glass(c: Canvas, W: Float, H: Float, t: Float, s: Spec, b: FloatArray, bass: Float) {
                val cz = min(W * .38f, H * .26f); val cy0 = H * .26f; val k = 1f + .02f * bass
        var q = pr(); q.color = (s.accent and 0xFFFFFF) or 0x66000000; q.setShadowLayer(W * .06f, 0f, 0f, s.accent)
        c.drawRoundRect(RectF(W * .10f, cy0 + cz * .55f, W * .90f, cy0 + cz + W * .5f), W * .07f, W * .07f, q)
        c.save(); c.scale(k, k, W / 2, cy0 + cz / 2); cover(c, s, (W - cz) / 2, cy0, cz, cz * .08f); c.restore()
        val lx = W * .14f; val rx = W * .86f; val ty = cy0 + cz + W * .08f
        text(c, s.title, lx, ty, W * .05f, Color.WHITE, false, Paint.Align.LEFT, rx - lx)
        text(c, s.artist, lx, ty + W * .06f, W * .04f, 0xCCFFFFFF.toInt(), false, Paint.Align.LEFT, rx - lx)
        val ly = ty + W * .13f; val prog = (t / s.dur).coerceIn(0f, 1f)
        q = pr(); q.color = 0x55FFFFFF; q.strokeWidth = W * .006f; q.strokeCap = Paint.Cap.ROUND; c.drawLine(lx, ly, rx, ly, q)
        q.color = Color.WHITE; c.drawLine(lx, ly, lx + (rx - lx) * prog, ly, q)
        text(c, fmtT(t), lx, ly + W * .05f, W * .028f, 0xCCFFFFFF.toInt(), false, Paint.Align.LEFT)
        text(c, "-" + fmtT(max(0f, s.dur - t)), rx, ly + W * .05f, W * .028f, 0xCCFFFFFF.toInt(), false, Paint.Align.RIGHT)
        val wy = ly + W * .12f
        for (side in 0..1) {
            val x0 = if (side == 0) lx else W * .58f; val x1 = if (side == 0) W * .42f else rx
            val path = Path(); val n = 40
            for (i in 0..n) {
                val x = x0 + (x1 - x0) * i / n
                val y = wy + sin(i * .9f + t * 6f + side) * W * .03f * (.3f + lvl(b, i, n))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            q = pr(); q.style = Paint.Style.STROKE; q.strokeWidth = W * .004f; q.color = Color.WHITE; c.drawPath(path, q)
            q = pr(); q.color = Color.WHITE; c.drawCircle(if (side == 0) W * .445f else W * .555f, wy, W * .012f, q)
        }
        text(c, s.handle, W / 2, wy + W * .02f, W * .06f, Color.WHITE, true, Paint.Align.CENTER, W * .18f)
    }

    private fun neon(c: Canvas, W: Float, H: Float, t: Float, s: Spec, b: FloatArray, bass: Float) {
                val m = min(W, H)
        for (k in 0..1) {
            val q = pr(); q.style = Paint.Style.STROKE; q.strokeWidth = m * .025f * (1f + bass); q.color = s.accent
            q.strokeCap = Paint.Cap.ROUND; q.setShadowLayer(m * .05f, 0f, 0f, s.accent)
            c.drawArc(RectF(-W * .25f, H * (.55f + k * .12f), W * .6f, H * (.95f + k * .1f)), 200f + t * 25f * (k + 1), 110f, false, q)
        }
        val q = pr(); q.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC); q.textSize = m * .22f; q.textAlign = Paint.Align.CENTER
        q.color = Color.argb(255, min(255, Color.red(s.accent) + 70), min(255, Color.green(s.accent) + 70), min(255, Color.blue(s.accent) + 70))
        q.setShadowLayer(m * .08f * (1f + bass), 0f, 0f, s.accent)
        c.drawText(fmtT(t), W * .55f, H * .82f, q)
        text(c, s.title, W * .06f, H * .93f, W * .05f, Color.WHITE, true, Paint.Align.LEFT, W * .6f)
        text(c, s.handle, W * .94f, H * .93f, W * .04f, 0xCCFFFFFF.toInt(), false, Paint.Align.RIGHT, W * .3f)
    }

    private fun ring(c: Canvas, W: Float, H: Float, t: Float, s: Spec, b: FloatArray, bass: Float) {
                val m = min(W, H); val cx = W / 2; val cy = H * .44f; val r0 = m * .24f * (1f + .05f * bass); val n = 72
        var q = pr(); q.style = Paint.Style.STROKE; q.strokeWidth = m * .008f; q.strokeCap = Paint.Cap.ROUND; q.color = s.accent
        q.setShadowLayer(m * .02f, 0f, 0f, s.accent)
        for (i in 0 until n) {
            val a = (2 * PI * i / n).toFloat() + t * .1f
            val v = lvl(b, if (i < n / 2) i else n - 1 - i, n / 2)
            val l = m * .012f + m * .12f * v
            c.drawLine(cx + cos(a) * r0, cy + sin(a) * r0, cx + cos(a) * (r0 + l), cy + sin(a) * (r0 + l), q)
        }
        c.save()
        val path = Path(); path.addCircle(cx, cy, r0 * .85f, Path.Direction.CW); c.clipPath(path)
        val cv = s.cover
        if (cv != null) { q = pr(); q.isFilterBitmap = true; crop(c, cv, cx - r0, cy - r0, r0 * 2, r0 * 2, q) }
        else { q = pr(); q.color = 0xFF0A0A0C.toInt(); c.drawCircle(cx, cy, r0, q); text(c, s.title.take(1), cx, cy + m * .06f, m * .18f, s.accent, true, Paint.Align.CENTER) }
        c.restore()
        q = pr(); q.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); q.textSize = m * .14f; q.textAlign = Paint.Align.CENTER
        q.color = s.accent; q.setShadowLayer(m * .04f, 0f, 0f, s.accent)
        c.drawText(fmtT(t), cx, H * .78f, q)
        text(c, s.title, cx, H * .86f, m * .045f, Color.WHITE, true, Paint.Align.CENTER, W * .8f)
        text(c, s.handle, cx, H * .91f, m * .035f, 0xAAFFFFFF.toInt(), false, Paint.Align.CENTER, W * .6f)
    }

    fun cropFill(c: Canvas, b: Bitmap, W: Float, H: Float) { val q = pr(); q.isFilterBitmap = true; crop(c, b, 0f, 0f, W, H, q) }

    private fun minimal(c: Canvas, W: Float, H: Float, t: Float, s: Spec, b: FloatArray, bass: Float) {
        val cz = W * .22f; val x0 = (W - cz) / 2; val y0 = H * .2f
        var q = pr(); q.style = Paint.Style.STROKE; q.strokeWidth = W * .003f; q.color = 0x88FFFFFF.toInt()
        c.drawRoundRect(RectF(x0, y0, x0 + cz, y0 + cz), W * .012f, W * .012f, q)
        cover(c, s, x0, y0, cz, W * .012f)
        text(c, s.title, W / 2, y0 + cz + W * .1f, W * .045f, Color.WHITE, true, Paint.Align.CENTER, W * .9f, true)
        text(c, s.artist, W / 2, y0 + cz + W * .17f, W * .035f, 0xCCFFFFFF.toInt(), false, Paint.Align.CENTER, W * .8f, true)
        val ry = y0 + cz + W * .29f; val r = W * .055f
        q = pr(); q.color = 0x33FFFFFF; c.drawCircle(W * .15f, ry, r, q)
        text(c, s.fps.toString(), W * .15f, ry + r * .35f, r * .9f, 0xAAFFFFFF.toInt(), false, Paint.Align.CENTER)
        q = pr(); q.color = 0x55FFFFFF; c.drawCircle(W * .85f, ry, r, q)
        q = pr(); q.color = 0xFF202020.toInt(); c.drawRect(W * .85f - r * .3f, ry - r * .3f, W * .85f + r * .3f, ry + r * .3f, q)
        val n = 64; val lx = W * .26f; val lw = W * .48f
        for (i in 0 until n) {
            val v = lvl(b, i, n); val hh = W * .004f + (if (i % 8 == 0) W * .03f * v else W * .008f * v)
            q = pr(); q.color = 0xCCFFFFFF.toInt(); c.drawRect(lx + i * lw / n, ry - hh, lx + i * lw / n + lw / n * .5f, ry + hh, q)
        }
        val wy = ry + W * .22f; val nb = 46
        for (i in 0 until nb) {
            val v = lvl(b, i, nb); val hh = W * .02f + W * .05f * v * (if (i % 2 == 0) 1f else .5f)
            q = pr(); q.color = if (i / nb.toFloat() < t / s.dur) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt()
            c.drawRoundRect(RectF(i * W / nb + 2f, wy - hh, i * W / nb + W / nb * .45f, wy + hh), 3f, 3f, q)
        }
        val bw = W * .32f; val bh = W * .16f
        q = pr(); q.color = 0xFFF2F2F2.toInt(); q.setShadowLayer(W * .02f * (1f + bass), 0f, 0f, s.accent)
        c.drawRoundRect(RectF(W / 2 - bw / 2, wy - bh / 2, W / 2 + bw / 2, wy + bh / 2), W * .008f, W * .008f, q)
        for (i in 0 until 9) {
            val v = lvl(b, i * 3, 27); val hh = bh * (.12f + .3f * v); val bx = W / 2 - bw / 2 + bw * (.1f + i * .09f)
            q = pr(); q.color = 0xFF2A2A2A.toInt(); c.drawRoundRect(RectF(bx, wy - hh, bx + bw * .04f, wy + hh), 3f, 3f, q)
        }
        text(c, s.handle, W / 2, wy + bh / 2 + W * .09f, W * .04f, Color.WHITE, true, Paint.Align.CENTER, W * .6f, true)
    }

    private fun vinyl(c: Canvas, W: Float, H: Float, t: Float, s: Spec, b: FloatArray, bass: Float) {
        val cx = W / 2; val cy = H * .38f; val R = W * .32f; val n = 64
        var q = pr(); q.style = Paint.Style.STROKE; q.strokeCap = Paint.Cap.ROUND; q.strokeWidth = W * .008f; q.color = s.accent; q.setShadowLayer(W * .02f, 0f, 0f, s.accent)
        for (i in 0 until n) {
            val a = (2 * PI * i / n).toFloat(); val l = W * .01f + W * .08f * lvl(b, i % 32, 32)
            c.drawLine(cx + cos(a) * R * 1.08f, cy + sin(a) * R * 1.08f, cx + cos(a) * (R * 1.08f + l), cy + sin(a) * (R * 1.08f + l), q)
        }
        c.save(); c.rotate(t * 40f, cx, cy)
        q = pr(); q.color = 0xFF0B0B0D.toInt(); c.drawCircle(cx, cy, R, q)
        q = pr(); q.style = Paint.Style.STROKE; q.strokeWidth = 1.5f; q.color = 0x22FFFFFF
        var rr = R * .42f
        while (rr < R) { c.drawCircle(cx, cy, rr, q); rr += R * .045f }
        q = pr(); q.shader = SweepGradient(cx, cy, intArrayOf(0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF), null); c.drawCircle(cx, cy, R, q)
        c.save(); val path = Path(); path.addCircle(cx, cy, R * .36f, Path.Direction.CW); c.clipPath(path)
        val cv = s.cover
        q = pr()
        if (cv != null) { q.isFilterBitmap = true; crop(c, cv, cx - R * .36f, cy - R * .36f, R * .72f, R * .72f, q) } else { q.color = s.accent; c.drawCircle(cx, cy, R * .36f, q) }
        c.restore()
        q = pr(); q.color = 0xFF050507.toInt(); c.drawCircle(cx, cy, R * .04f, q)
        c.restore()
        text(c, s.title, W / 2, cy + R + W * .14f, W * .055f, Color.WHITE, true, Paint.Align.CENTER, W * .88f)
        text(c, s.artist, W / 2, cy + R + W * .21f, W * .04f, 0xCCFFFFFF.toInt(), false, Paint.Align.CENTER, W * .8f)
        val ly = cy + R + W * .3f; val lx = W * .14f; val rx = W * .86f; val prog = (t / s.dur).coerceIn(0f, 1f)
        q = pr(); q.color = 0x55FFFFFF; q.strokeWidth = W * .006f; q.strokeCap = Paint.Cap.ROUND; c.drawLine(lx, ly, rx, ly, q)
        q.color = s.accent; c.drawLine(lx, ly, lx + (rx - lx) * prog, ly, q)
        text(c, fmtT(t), lx, ly + W * .05f, W * .03f, 0xCCFFFFFF.toInt(), false, Paint.Align.LEFT)
        text(c, s.handle, rx, ly + W * .05f, W * .03f, 0xCCFFFFFF.toInt(), false, Paint.Align.RIGHT)
    }

    private fun orbit(c: Canvas, W: Float, H: Float, t: Float, s: Spec, b: FloatArray, bass: Float) {
        val cx = W / 2; val cy = H * .4f; val r = W * .24f * (1f + .03f * bass); val ro = r * 1.22f
        var q = pr(); q.style = Paint.Style.STROKE; q.strokeWidth = W * .012f; q.color = 0x33FFFFFF; c.drawCircle(cx, cy, ro, q)
        q = pr(); q.style = Paint.Style.STROKE; q.strokeWidth = W * .012f; q.strokeCap = Paint.Cap.ROUND; q.color = s.accent; q.setShadowLayer(W * .03f, 0f, 0f, s.accent)
        c.drawArc(RectF(cx - ro, cy - ro, cx + ro, cy + ro), -90f, 360f * (t / s.dur).coerceIn(0f, 1f), false, q)
        for (k in 0 until 3) {
            val a = t * (.6f + k * .3f) + k * 2.1f; val rr = r * (1.45f + k * .12f)
            q = pr(); q.color = s.accent; q.alpha = 160; c.drawCircle(cx + cos(a) * rr, cy + sin(a) * rr, W * (.012f + .006f * k) * (1f + bass), q)
        }
        c.save(); val path = Path(); path.addCircle(cx, cy, r, Path.Direction.CW); c.clipPath(path)
        val cv = s.cover; q = pr()
        if (cv != null) { q.isFilterBitmap = true; crop(c, cv, cx - r, cy - r, r * 2, r * 2, q) }
        else { q.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, s.accent, 0xFF111118.toInt(), Shader.TileMode.CLAMP); c.drawCircle(cx, cy, r, q) }
        c.restore()
        text(c, s.title, cx, cy + ro + W * .12f, W * .055f, Color.WHITE, true, Paint.Align.CENTER, W * .88f)
        text(c, s.artist, cx, cy + ro + W * .19f, W * .04f, 0xCCFFFFFF.toInt(), false, Paint.Align.CENTER, W * .8f)
        text(c, fmtT(t) + " / " + fmtT(s.dur), cx, cy + ro + W * .26f, W * .035f, 0xAAFFFFFF.toInt(), false, Paint.Align.CENTER)
        val n = 28; val bw = W * .5f / n; val by = cy + ro + W * .42f
        for (i in 0 until n) { q = pr(); q.color = s.accent; val hh = W * .01f + W * .07f * lvl(b, i, n); c.drawRoundRect(RectF(W * .25f + i * bw, by - hh, W * .25f + i * bw + bw * .6f, by + hh), 3f, 3f, q) }
    }

    private fun stack(c: Canvas, W: Float, H: Float, t: Float, s: Spec, b: FloatArray, bass: Float) {
        val cy = H * .5f; val n = 34; val bw = W / n
        for (i in 0 until n) {
            val q = pr(); q.color = s.accent; q.setShadowLayer(W * .02f, 0f, 0f, s.accent); q.alpha = 230
            val hh = H * .02f + H * .22f * lvl(b, i, n)
            c.drawRoundRect(RectF(i * bw + bw * .18f, cy - hh, i * bw + bw * .82f, cy + hh), bw * .2f, bw * .2f, q)
        }
        text(c, s.title, W / 2, cy + W * .035f, W * .1f * (1f + .04f * bass), Color.WHITE, true, Paint.Align.CENTER, W * .92f)
        text(c, s.artist, W / 2, cy + W * .13f, W * .04f, 0xDDFFFFFF.toInt(), false, Paint.Align.CENTER, W * .8f)
        val ly = H * .9f; val lx = W * .1f; val rx = W * .9f
        val q = pr(); q.color = 0x55FFFFFF; q.strokeWidth = W * .006f; q.strokeCap = Paint.Cap.ROUND; c.drawLine(lx, ly, rx, ly, q)
        q.color = Color.WHITE; c.drawLine(lx, ly, lx + (rx - lx) * (t / s.dur).coerceIn(0f, 1f), ly, q)
        text(c, fmtT(t), lx, ly + W * .05f, W * .03f, 0xCCFFFFFF.toInt(), false, Paint.Align.LEFT)
        text(c, s.handle, rx, ly + W * .05f, W * .03f, 0xCCFFFFFF.toInt(), false, Paint.Align.RIGHT)
    }

    fun draw(c: Canvas, w: Int, h: Int, t: Float, s: Spec, b: FloatArray) {
        val W = w.toFloat(); val H = h.toFloat(); val bass = (b[0] + b[1] + b[2]) / 3f
        val k = s.cfg.cardScale; c.save(); c.scale(k, k, W / 2, H * .5f)
        when (s.style) {
            Style.PLAYER -> player(c, W, H, t, s, b, bass)
            Style.GLASS -> glass(c, W, H, t, s, b, bass)
            Style.NEON -> neon(c, W, H, t, s, b, bass)
            Style.RING -> ring(c, W, H, t, s, b, bass)
            Style.NONE -> {}
            Style.MINIMAL -> minimal(c, W, H, t, s, b, bass)
            Style.VINYL -> vinyl(c, W, H, t, s, b, bass)
            Style.ORBIT -> orbit(c, W, H, t, s, b, bass)
            Style.STACK -> stack(c, W, H, t, s, b, bass)
        }
        c.restore()
    }
}
