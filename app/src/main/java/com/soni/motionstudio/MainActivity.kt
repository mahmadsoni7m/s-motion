@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.soni.motionstudio

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

val ASPECTS = listOf(Triple("9:16", 9, 16), Triple("16:9", 16, 9), Triple("1:1", 1, 1), Triple("4:5", 4, 5))
val VIOLET = Color(0xFF8B5CF6)
val CYAN = Color(0xFF22D3EE)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = VIOLET, secondary = CYAN, background = Color(0xFF08080C), surface = Color(0xFF17171F), onBackground = Color.White, onSurface = Color.White)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Studio() }
            }
        }
    }
}

fun dims(a: Int, b: Int, short: Int): Pair<Int, Int> {
    fun e(x: Float) = (x / 2).roundToInt() * 2
    return if (a >= b) Pair(e(short * a / b.toFloat()), short) else Pair(short, e(short * b / a.toFloat()))
}

fun loadBitmap(ctx: Context, uri: Uri): Bitmap? = try {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1
    while (o.outWidth / s > 1600 || o.outHeight / s > 1600) s *= 2
    val o2 = BitmapFactory.Options().apply { inSampleSize = s }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
} catch (e: Exception) { null }

fun saveToGallery(ctx: Context, f: File): Uri? {
    val v = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SONI")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return null
    ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
    return uri
}

@Composable
fun Chips(options: List<String>, sel: Int, onSel: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o -> FilterChip(selected = i == sel, onClick = { onSel(i) }, label = { Text(o) }) }
    }
}

@Composable
fun Sl(label: String, v: Float, r: ClosedFloatingPointRange<Float>, fmt: String = "%.2f", onC: (Float) -> Unit) {
    Text(label + ": " + fmt.format(v), fontSize = 12.sp)
    Slider(value = v.coerceIn(r.start, r.endInclusive), onValueChange = onC, valueRange = r)
}

@Composable
fun ColorRow(colors: List<Int>, sel: Int, onSel: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { col ->
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(col)).border(if (col == sel) 3.dp else 1.dp, if (col == sel) Color.White else Color.DarkGray, CircleShape).clickable { onSel(col) })
        }
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = CYAN, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        content()
    }
}

@Composable
fun Studio() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var cfg by remember { mutableStateOf(Cfg()) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var an by remember { mutableStateOf<Analysis?>(null) }
    var playing by remember { mutableStateOf(false) }
    var t by remember { mutableFloatStateOf(0f) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Uri?>(null) }
    var exporter by remember { mutableStateOf<Exporter?>(null) }
    var pname by remember { mutableStateOf("My project") }
    var names by remember { mutableStateOf(Store.names(ctx)) }
    var confirmText by remember { mutableStateOf("") }
    var confirmAct by remember { mutableStateOf<(() -> Unit)?>(null) }
    val bands = remember { FloatArray(BANDS) }
    val vk = remember { runCatching { Native.vulkanInfo() }.getOrDefault("Vulkan: n/a") }

    SideEffect { view.keepScreenOn = exporting }

    val pickImg = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { val b = loadBitmap(ctx, uri); if (b != null) { cover = b; cfg = cfg.copy(useImage = true) } else status = "That image could not be read." }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            audioUri = uri; an = null; status = "Analyzing audio..."
            scope.launch {
                val a = withContext(Dispatchers.Default) { AudioDecoder.decode(ctx, uri) }
                an = a
                status = if (a == null) "This audio file could not be decoded." else "Audio ready (" + fmtT(a.durSec) + ")"
            }
        }
    }
    val pickJson = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val loaded = try { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }?.let { cfgFromJson(JSONObject(it)) } } catch (e: Exception) { null }
            if (loaded != null) { cfg = loaded; status = "Project imported." } else status = "That file is not a valid SONI project."
        }
    }

    val aDur = an?.durSec
    val trimEnd = if (aDur != null) (if (cfg.trimEnd > cfg.trimStart) min(cfg.trimEnd, aDur) else aDur) else 0f
    val effDur = (if (aDur != null) min(cfg.dur, trimEnd - cfg.trimStart) else cfg.dur).coerceAtLeast(1f)
    val spec = Spec(cfg.style, cfg.accent, cfg.title, cfg.artist, cfg.handle, effDur, cfg.fps, cover, cfg)
    val (outW, outH) = dims(ASPECTS[cfg.ar].second, ASPECTS[cfg.ar].third, cfg.res)

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var mp: MediaPlayer? = null
        try {
            audioUri?.let { u ->
                if (cfg.audioOn) mp = MediaPlayer.create(ctx, u)?.apply {
                    val v = if (cfg.mute) 0f else cfg.vol
                    setVolume(v, v); seekTo(((cfg.trimStart + t) * 1000).toInt()); start()
                }
            }
            val t0 = withFrameNanos { it } - (t * 1e9).toLong()
            while (true) {
                val n = withFrameNanos { it }
                t = (n - t0) / 1e9f
                mp?.let { val v = if (cfg.mute) 0f else cfg.vol; it.setVolume(v, v) }
                if (t >= effDur) { t = 0f; playing = false; break }
            }
        } finally { mp?.release() }
    }

    LaunchedEffect(exporting) {
        while (exporting) { progress = exporter?.progress() ?: 0f; delay(300) }
    }

    if (confirmAct != null) {
        AlertDialog(
            onDismissRequest = { confirmAct = null },
            title = { Text("Are you sure?") }, text = { Text(confirmText) },
            confirmButton = { TextButton(onClick = { confirmAct?.invoke(); confirmAct = null }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = { confirmAct = null }) { Text("Cancel") } }
        )
    }

    Column(Modifier.fillMaxSize()) {
        Text("SONI MOTION STUDIO", color = VIOLET, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 16.dp, top = 28.dp, bottom = 6.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(300.dp).background(Color.Black), contentAlignment = Alignment.Center) {
            val aspect = ASPECTS[cfg.ar].second.toFloat() / ASPECTS[cfg.ar].third
            val fitW = if (maxWidth / maxHeight < aspect) maxWidth else maxHeight * aspect
            Canvas(Modifier.size(fitW, fitW / aspect)) {
                val tt = t
                val an0 = an
                if (an0 != null) an0.at(tt + cfg.trimStart, bands) else synthBands(tt, bands)
                drawIntoCanvas { Scene.draw(it.nativeCanvas, size.width.toInt(), size.height.toInt(), tt, spec, bands) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { playing = !playing }, enabled = !exporting) { Text(if (playing) "Pause" else "Play") }
            Slider(value = t.coerceIn(0f, effDur), onValueChange = { playing = false; t = it }, valueRange = 0f..effDur, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            Text(fmtT(t) + " / " + fmtT(effDur), fontSize = 12.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("LAYOUT AND COLORS") {
                Chips(Style.values().map { it.label }, cfg.style.ordinal) { cfg = cfg.copy(style = Style.values()[it]) }
                Text("Primary", fontSize = 12.sp); ColorRow(ACCENTS, cfg.accent) { cfg = cfg.copy(accent = it) }
                Text("Secondary (base)", fontSize = 12.sp); ColorRow(BASES, cfg.base) { cfg = cfg.copy(base = it) }
            }
            Section("BACKGROUND") {
                OutlinedButton(onClick = { pickImg.launch("image/*") }) { Text(if (cover == null) "Upload image" else "Change image") }
                if (cover != null) Row(verticalAlignment = Alignment.CenterVertically) { Switch(cfg.useImage, { cfg = cfg.copy(useImage = it) }); Spacer(Modifier.width(8.dp)); Text("Use my image", fontSize = 13.sp) }
                Text("Library (" + BGS.size + ")", fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BGS.forEachIndexed { i, d ->
                        FilterChip(selected = !cfg.useImage && cfg.bg == i || (cover == null && cfg.bg == i), onClick = { cfg = cfg.copy(bg = i, accent = d.accent, base = d.base, useImage = false) }, label = { Text(d.name, fontSize = 12.sp) })
                    }
                }
                Sl("Brightness", cfg.bright, .5f..1.5f) { cfg = cfg.copy(bright = it) }
                Sl("Contrast", cfg.contrast, .5f..1.5f) { cfg = cfg.copy(contrast = it) }
                Sl("Saturation", cfg.sat, 0f..2f) { cfg = cfg.copy(sat = it) }
                Sl("Blur", cfg.blur, 0f..10f, "%.1f") { cfg = cfg.copy(blur = it) }
                Sl("Camera zoom", cfg.zoom, .5f..2f) { cfg = cfg.copy(zoom = it) }
                Sl("Rotation", cfg.rot, -45f..45f, "%.0f") { cfg = cfg.copy(rot = it) }
            }
            Section("ANIMATION") {
                Chips(PRESETS.keys.toList(), PRESETS.keys.indexOf(cfg.preset)) { i ->
                    val name = PRESETS.keys.toList()[i]
                    val set = if (name == "Random Mix") FXIDS.filter { Math.random() < .35 }.toSet() else PRESETS[name]!!
                    cfg = cfg.copy(preset = name, fx = set)
                }
                Text("Combine effects", fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FXS.forEach { (id, label) ->
                        FilterChip(selected = id in cfg.fx, onClick = { cfg = cfg.copy(fx = if (id in cfg.fx) cfg.fx - id else cfg.fx + id, preset = "Custom") }, label = { Text(label, fontSize = 12.sp) })
                    }
                }
                Sl("Animation speed", cfg.speed, .2f..3f) { cfg = cfg.copy(speed = it) }
                Sl("Motion intensity", cfg.motion, 0f..2f) { cfg = cfg.copy(motion = it) }
                Sl("Effect intensity", cfg.fxi, 0f..2f) { cfg = cfg.copy(fxi = it) }
                Sl("Glow intensity", cfg.glow, 0f..2f) { cfg = cfg.copy(glow = it) }
                Sl("Particle density", cfg.dens, 0f..2f) { cfg = cfg.copy(dens = it) }
            }
            Section("MUSIC") {
                OutlinedButton(onClick = { pickAudio.launch("audio/*") }) { Text(if (audioUri == null) "Choose music" else "Change music") }
                val a = an
                if (a != null) {
                    Sl("Start (s)", cfg.trimStart, 0f..(a.durSec - 1f).coerceAtLeast(0.1f), "%.1f") { cfg = cfg.copy(trimStart = it); t = 0f; playing = false }
                    Sl("End (s, 0 = until the end)", cfg.trimEnd, 0f..a.durSec, "%.1f") { cfg = cfg.copy(trimEnd = it); t = 0f; playing = false }
                }
                Sl("Volume", cfg.vol, 0f..1f) { cfg = cfg.copy(vol = it) }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(cfg.mute, { cfg = cfg.copy(mute = it) }); Spacer(Modifier.width(8.dp)); Text("Mute", fontSize = 13.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(cfg.audioOn, { cfg = cfg.copy(audioOn = it) }); Spacer(Modifier.width(8.dp)); Text("Include audio in export", fontSize = 13.sp) }
                Text("Use only music and images you own or may use.", fontSize = 11.sp, color = Color.Gray)
            }
            Section("TEXT") {
                OutlinedTextField(cfg.title, { cfg = cfg.copy(title = it.take(60)) }, label = { Text("Card title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cfg.artist, { cfg = cfg.copy(artist = it.take(60)) }, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cfg.handle, { cfg = cfg.copy(handle = it.take(40)) }, label = { Text("Handle or brand") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cfg.text, { cfg = cfg.copy(text = it.take(80)) }, label = { Text("Overlay text") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Chips(FONT_NAMES, cfg.font) { cfg = cfg.copy(font = it) }
                Sl("Size", cfg.tsize, 2f..25f, "%.1f") { cfg = cfg.copy(tsize = it) }
                Sl("Position X %", cfg.tx, 0f..100f, "%.0f") { cfg = cfg.copy(tx = it) }
                Sl("Position Y %", cfg.ty, 0f..100f, "%.0f") { cfg = cfg.copy(ty = it) }
                ColorRow(TCOLORS, cfg.tcolor) { cfg = cfg.copy(tcolor = it) }
            }
            Section("EXPORT") {
                Sl("Duration (s)", cfg.dur, 5f..60f, "%.0f") { cfg = cfg.copy(dur = it); t = 0f; playing = false }
                Chips(ASPECTS.map { it.first }, cfg.ar) { cfg = cfg.copy(ar = it) }
                Chips(listOf("720p", "1080p"), if (cfg.res == 720) 0 else 1) { cfg = cfg.copy(res = if (it == 0) 720 else 1080) }
                Chips(listOf("24 fps", "30 fps", "60 fps"), when (cfg.fps) { 24 -> 0; 60 -> 2; else -> 1 }) { cfg = cfg.copy(fps = intArrayOf(24, 30, 60)[it]) }
                Text("Output: ${outW}x$outH MP4 (H.264 + AAC), ${cfg.fps} fps" + if (cfg.res == 1080 || cfg.fps == 60) ". Heavy: may be slow or fail on weaker phones." else "", fontSize = 12.sp, color = Color.Gray)
                if (!exporting) {
                    Button(onClick = {
                        playing = false; result = null; exporting = true; progress = 0f; status = "Rendering... keep the screen on."
                        val ex = Exporter(ctx); exporter = ex
                        val glitch = if ("glitch" in cfg.fx) 0.6f * cfg.fxi else 0f
                        val gain = if (cfg.mute) 0f else cfg.vol
                        try {
                            ex.start(spec, outW, outH, an, if (cfg.audioOn) audioUri else null, cfg.trimStart, effDur, glitch, gain) { file, err ->
                                exporting = false
                                if (file != null) { result = saveToGallery(ctx, file); status = if (result != null) "Saved to Movies/SONI" else "Rendered, but saving to the gallery failed." }
                                else status = "Export failed: $err"
                            }
                        } catch (e: Exception) { exporting = false; status = "Export failed: " + (e.message ?: "unknown error") }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Start export") }
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { exporter?.cancel(); exporting = false; status = "Export cancelled." }) { Text("Cancel") }
                }
                if (status.isNotEmpty()) Text(status, color = CYAN, fontSize = 13.sp)
                result?.let { u ->
                    Button(onClick = {
                        val i = Intent(Intent.ACTION_SEND).apply { type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        ctx.startActivity(Intent.createChooser(i, "Share video"))
                    }) { Text("Share video") }
                }
            }
            Section("PROJECT") {
                OutlinedTextField(pname, { pname = it.take(40) }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (pname.isNotBlank()) { Store.save(ctx, pname.trim(), cfg); names = Store.names(ctx); status = "Project saved (settings only)." } }) { Text("Save") }
                    OutlinedButton(onClick = {
                        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, cfg.toJson().toString(2)) }
                        ctx.startActivity(Intent.createChooser(i, "Export project JSON"))
                    }) { Text("Export JSON") }
                    OutlinedButton(onClick = { pickJson.launch("*/*") }) { Text("Import") }
                }
                if (names.isEmpty()) Text("No saved projects yet.", fontSize = 12.sp, color = Color.Gray)
                names.forEach { n ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n, Modifier.weight(1f))
                        TextButton(onClick = { Store.load(ctx, n)?.let { cfg = it; pname = n; status = "Loaded " + n } ?: run { status = "Could not load that project." } }) { Text("Load") }
                        TextButton(onClick = { confirmText = "Delete project \"$n\"?"; confirmAct = { Store.delete(ctx, n); names = Store.names(ctx) } }) { Text("Delete") }
                    }
                }
                OutlinedButton(onClick = { confirmText = "Reset all settings?"; confirmAct = { cfg = Cfg(); t = 0f; playing = false } }) { Text("Reset project") }
                Text("Projects store settings only. Your image and music must be chosen again after restarting the app.", fontSize = 11.sp, color = Color.Gray)
            }
            Text("Render: Canvas + OpenGL ES effects via Media3 Transformer. $vk (probe only). Glitch is applied in the exported video only.", fontSize = 11.sp, color = Color.Gray)
        }
    }
}
