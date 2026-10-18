package com.soni.motionstudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class BgDef(val name: String, val kind: String, val base: Int, val accent: Int)

private fun bgd(n: String, k: String, base: Long, acc: Long) = BgDef(n, k, base.toInt(), acc.toInt())

val BGS = listOf(
    bgd("Cyberpunk City", "city", 0xFF12002B, 0xFF00E5FF), bgd("Neon Night", "city", 0xFF050014, 0xFFFF2BD6),
    bgd("Galaxy", "blobs", 0xFF050018, 0xFF7C3AED), bgd("Deep Space", "grad", 0xFF000008, 0xFF4338CA),
    bgd("Mountains", "mount", 0xFF0F172A, 0xFF38BDF8), bgd("Ocean", "waves", 0xFF001B3A, 0xFF06B6D4),
    bgd("Sunset", "sun", 0xFF3B0764, 0xFFFB923C), bgd("Abstract Geometry", "grid", 0xFF0B0B14, 0xFF8B5CF6),
    bgd("Particles", "blobs", 0xFF02040F, 0xFF22D3EE), bgd("Liquid Gradient", "blobs", 0xFF1A0033, 0xFFF0ABFC),
    bgd("Black and Gold Luxury", "grad", 0xFF050505, 0xFFD4AF37), bgd("Anime Abstract", "blobs", 0xFF1E1B4B, 0xFFF472B6),
    bgd("Racing", "tunnel", 0xFF0A0A0A, 0xFFEF4444), bgd("Minimal", "grad", 0xFF111114, 0xFF52525B),
    bgd("Rainy Window", "rain", 0xFF0B1220, 0xFF60A5FA), bgd("Aurora", "waves", 0xFF020617, 0xFF34D399),
    bgd("Fire and Energy", "blobs", 0xFF1A0500, 0xFFF97316), bgd("Dreamscape", "mount", 0xFF2E1065, 0xFFF9A8D4),
    bgd("Digital Grid", "grid", 0xFF000A0A, 0xFF22D3EE), bgd("Retro Wave", "sun", 0xFF1A0033, 0xFFFF2BD6),
    bgd("Cosmic Tunnel", "tunnel", 0xFF02000F, 0xFF8B5CF6)
)

val FXS = listOf(
    "zoomin" to "Zoom in", "zoomout" to "Zoom out", "pan" to "Pan", "shake" to "Shake", "pulse" to "Beat pulse",
    "particles" to "Particles", "stars" to "Starfield", "wave" to "Waveform", "bars" to "Equalizer", "ring" to "Spectrum ring",
    "grid" to "Retro floor", "geo" to "Rotating geometry", "glitch" to "RGB glitch (export)", "sweep" to "Light sweep", "glow" to "Neon glow"
)
val FXIDS = FXS.map { it.first }.toSet()

val PRESETS: Map<String, Set<String>> = linkedMapOf(
    "Smooth Zoom In" to setOf("zoomin"), "Smooth Zoom Out" to setOf("zoomout"), "Ken Burns Pan" to setOf("pan", "zoomin"),
    "Neon Glow" to setOf("glow", "sweep"), "RGB Glitch" to setOf("glitch", "shake"), "Particle Field" to setOf("particles"),
    "Starfield" to setOf("stars"), "Audio Waveform" to setOf("wave"), "Circular Visualizer" to setOf("ring"),
    "Equalizer Bars" to setOf("bars"), "Spectrum Ring" to setOf("ring", "pulse", "glow"), "Retro Wave" to setOf("grid", "bars", "glow"),
    "Rotating Geometry" to setOf("geo", "glow"), "Liquid Gradient" to setOf("pan", "pulse"), "Cinematic Shake" to setOf("shake", "zoomin"),
    "Beat Pulse" to setOf("pulse"), "Light Sweep" to setOf("sweep"), "Parallax (simple)" to setOf("pan", "particles", "stars"),
    "Minimal Ambient" to setOf("pan"), "Random Mix" to emptySet()
)

val ACCENTS = listOf(0xFF8B5CF6, 0xFFEF4444, 0xFFFACC15, 0xFF22C55E, 0xFF22D3EE, 0xFFEC4899).map { it.toInt() }
val BASES = listOf(0xFF050507, 0xFF12002B, 0xFF001B3A, 0xFF1A0500, 0xFF0F172A, 0xFF111114).map { it.toInt() }
val TCOLORS = listOf(0xFFFFFFFF, 0xFFFACC15, 0xFF22D3EE, 0xFFEC4899, 0xFFEF4444, 0xFF22C55E).map { it.toInt() }
val FONT_NAMES = listOf("Sans", "Serif", "Mono", "Bold italic")

data class Cfg(
    val style: Style = Style.PLAYER, val accent: Int = ACCENTS[1], val base: Int = BASES[0],
    val title: String = "ULTRA SLOWED", val artist: String = "Artist", val handle: String = "@soni",
    val dur: Float = 15f, val ar: Int = 0, val res: Int = 720, val fps: Int = 30,
    val bg: Int = 0, val useImage: Boolean = true, val fx: Set<String> = setOf("pulse", "particles"), val preset: String = "Beat Pulse",
    val bright: Float = 1f, val contrast: Float = 1f, val sat: Float = 1f, val blur: Float = 0f, val zoom: Float = 1f, val rot: Float = 0f,
    val speed: Float = 1f, val motion: Float = 1f, val fxi: Float = 1f, val glow: Float = 1f, val dens: Float = 1f,
    val vol: Float = 1f, val mute: Boolean = false, val audioOn: Boolean = true, val trimStart: Float = 0f, val trimEnd: Float = 0f,
    val text: String = "", val font: Int = 0, val tsize: Float = 8f, val tx: Float = 50f, val ty: Float = 50f, val tcolor: Int = TCOLORS[0]
)

fun Cfg.toJson(): JSONObject = JSONObject().apply {
    put("style", style.ordinal); put("accent", accent); put("base", base); put("title", title); put("artist", artist); put("handle", handle)
    put("dur", dur.toDouble()); put("ar", ar); put("res", res); put("fps", fps); put("bg", bg); put("useImage", useImage)
    put("fx", JSONArray(fx.toList())); put("preset", preset)
    put("bright", bright.toDouble()); put("contrast", contrast.toDouble()); put("sat", sat.toDouble()); put("blur", blur.toDouble())
    put("zoom", zoom.toDouble()); put("rot", rot.toDouble()); put("speed", speed.toDouble()); put("motion", motion.toDouble())
    put("fxi", fxi.toDouble()); put("glow", glow.toDouble()); put("dens", dens.toDouble()); put("vol", vol.toDouble())
    put("mute", mute); put("audioOn", audioOn); put("trimStart", trimStart.toDouble()); put("trimEnd", trimEnd.toDouble())
    put("text", text); put("font", font); put("tsize", tsize.toDouble()); put("tx", tx.toDouble()); put("ty", ty.toDouble()); put("tcolor", tcolor)
}

private fun JSONObject.f(k: String, d: Float, lo: Float, hi: Float) = optDouble(k, d.toDouble()).toFloat().coerceIn(lo, hi)

/** Validates and clamps every field; returns null when the JSON is not usable. */
fun cfgFromJson(o: JSONObject): Cfg? = try {
    val d = Cfg()
    val arr = o.optJSONArray("fx") ?: JSONArray()
    val fx = (0 until arr.length()).map { arr.getString(it) }.filter { it in FXIDS }.toSet()
    Cfg(
        style = Style.values()[o.optInt("style", 0).coerceIn(0, Style.values().size - 1)],
        accent = o.optInt("accent", d.accent), base = o.optInt("base", d.base),
        title = o.optString("title", d.title).take(60), artist = o.optString("artist", d.artist).take(60), handle = o.optString("handle", d.handle).take(40),
        dur = o.f("dur", 15f, 5f, 60f), ar = o.optInt("ar", 0).coerceIn(0, 3), res = if (o.optInt("res", 720) >= 1080) 1080 else 720,
        fps = o.optInt("fps", 30).let { if (it == 24 || it == 60) it else 30 },
        bg = o.optInt("bg", 0).coerceIn(0, BGS.size - 1), useImage = o.optBoolean("useImage", true), fx = fx, preset = o.optString("preset", "Custom").take(40),
        bright = o.f("bright", 1f, .5f, 1.5f), contrast = o.f("contrast", 1f, .5f, 1.5f), sat = o.f("sat", 1f, 0f, 2f), blur = o.f("blur", 0f, 0f, 10f),
        zoom = o.f("zoom", 1f, .5f, 2f), rot = o.f("rot", 0f, -45f, 45f), speed = o.f("speed", 1f, .2f, 3f), motion = o.f("motion", 1f, 0f, 2f),
        fxi = o.f("fxi", 1f, 0f, 2f), glow = o.f("glow", 1f, 0f, 2f), dens = o.f("dens", 1f, 0f, 2f), vol = o.f("vol", 1f, 0f, 1f),
        mute = o.optBoolean("mute", false), audioOn = o.optBoolean("audioOn", true), trimStart = o.f("trimStart", 0f, 0f, 3600f), trimEnd = o.f("trimEnd", 0f, 0f, 3600f),
        text = o.optString("text", "").take(80), font = o.optInt("font", 0).coerceIn(0, FONT_NAMES.size - 1), tsize = o.f("tsize", 8f, 2f, 25f),
        tx = o.f("tx", 50f, 0f, 100f), ty = o.f("ty", 50f, 0f, 100f), tcolor = o.optInt("tcolor", d.tcolor)
    )
} catch (e: Exception) { null }

/** Projects are stored as settings only (no image or audio files). */
object Store {
    private fun sp(c: Context) = c.getSharedPreferences("soni", Context.MODE_PRIVATE)
    private fun all(c: Context): JSONObject = try { JSONObject(sp(c).getString("projects", "{}")!!) } catch (e: Exception) { JSONObject() }
    fun names(c: Context): List<String> = all(c).keys().asSequence().toList().sorted()
    fun save(c: Context, name: String, cfg: Cfg) { val o = all(c); o.put(name, cfg.toJson()); sp(c).edit().putString("projects", o.toString()).apply() }
    fun load(c: Context, name: String): Cfg? = all(c).optJSONObject(name)?.let { cfgFromJson(it) }
    fun delete(c: Context, name: String) { val o = all(c); o.remove(name); sp(c).edit().putString("projects", o.toString()).apply() }
}
