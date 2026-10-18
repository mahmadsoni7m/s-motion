package com.soni.motionstudio

import android.graphics.*
import kotlin.math.*

enum class Style(val label: String) { PLAYER("Player card"), GLASS("Glass cover"), NEON("Neon timer"), RING("Ring logo"), NONE("Effects only") }

class Spec(val style: Style, val accent: Int, val title: String, val artist: String, val handle: String, val dur: Float, val fps: Int, val cover: Bitmap?, val cfg: Cfg = Cfg())

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
            Style.NONE -> s.cover?.let { val q = pr(); q.isFilterBitmap = true; crop(c, it, 0f, 0f, W, H, q) }
        }
    }

    private fun text(c: Canvas, str: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean, align: Paint.Align, maxW: Float = 1e9f) {
        val q = pr(); q.textSize = size; q.color = color; q.textAlign = align
        q.typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
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

    fun draw(c: Canvas, w: Int, h: Int, t: Float, s: Spec, b: FloatArray) {
        val W = w.toFloat(); val H = h.toFloat(); val bass = (b[0] + b[1] + b[2]) / 3f
        when (s.style) {
            Style.PLAYER -> player(c, W, H, t, s, b, bass)
            Style.GLASS -> glass(c, W, H, t, s, b, bass)
            Style.NEON -> neon(c, W, H, t, s, b, bass)
            Style.RING -> ring(c, W, H, t, s, b, bass)
            Style.NONE -> {}
        }
    }
}
