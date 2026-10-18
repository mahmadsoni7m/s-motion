@file:OptIn(ExperimentalMaterial3Api::class)

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
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

val ACCENTS = listOf(0xFF8B5CF6.toInt(), 0xFFEF4444.toInt(), 0xFFFACC15.toInt(), 0xFF22C55E.toInt(), 0xFF22D3EE.toInt(), 0xFFEC4899.toInt())
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
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = CYAN, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        content()
    }
}

@Composable
fun Studio() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var style by remember { mutableStateOf(Style.PLAYER) }
    var accent by remember { mutableIntStateOf(ACCENTS[1]) }
    var title by remember { mutableStateOf("ULTRA SLOWED") }
    var artist by remember { mutableStateOf("Artist") }
    var handle by remember { mutableStateOf("@soni") }
    var dur by remember { mutableFloatStateOf(15f) }
    var arIdx by remember { mutableIntStateOf(0) }
    var short by remember { mutableIntStateOf(720) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var an by remember { mutableStateOf<Analysis?>(null) }
    var audioStart by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var t by remember { mutableFloatStateOf(0f) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Uri?>(null) }
    var exporter by remember { mutableStateOf<Exporter?>(null) }
    val bands = remember { FloatArray(BANDS) }
    val vk = remember { runCatching { Native.vulkanInfo() }.getOrDefault("Vulkan: n/a") }

    SideEffect { view.keepScreenOn = exporting }

    val pickImg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            cover = loadBitmap(ctx, uri)
        }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            audioUri = uri; an = null; audioStart = 0f; status = "Analyzing audio..."
            scope.launch {
                val a = withContext(Dispatchers.Default) { AudioDecoder.decode(ctx, uri) }
                an = a
                status = if (a == null) "This audio file could not be decoded." else "Audio ready (" + fmtT(a.durSec) + ")"
            }
        }
    }

    val effDur = (an?.let { min(dur, it.durSec - audioStart) } ?: dur).coerceAtLeast(1f)
    val spec = Spec(style, accent, title, artist, handle, effDur, 30, cover)

    LaunchedEffect(playing, audioUri) {
        if (!playing) return@LaunchedEffect
        var mp: MediaPlayer? = null
        try {
            audioUri?.let { uri ->
                val player = MediaPlayer()
                mp = player
                player.setOnPreparedListener {
                    it.seekTo(((audioStart + t) * 1000).toInt())
                    it.start()
                }
                player.setOnErrorListener { _, what, extra ->
                    status = "Music playback failed ($what/$extra)"
                    playing = false
                    true
                }
                player.setDataSource(ctx, uri)
                player.prepareAsync()
            }
            val t0 = withFrameNanos { it } - (t * 1e9).toLong()
            while (true) {
                val n = withFrameNanos { it }
                t = (n - t0) / 1e9f
                if (t >= effDur) { t = 0f; playing = false; break }
            }
        } catch (e: Exception) {
            status = "Music playback failed: " + (e.message ?: "cannot open audio")
            playing = false
        } finally {
            runCatching { mp?.release() }
        }
    }

    LaunchedEffect(exporting) {
        while (exporting) { progress = exporter?.progress() ?: 0f; delay(300) }
    }

    Column(Modifier.fillMaxSize()) {
        Text("SONI MOTION STUDIO", color = VIOLET, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 16.dp, top = 28.dp, bottom = 6.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(300.dp).background(Color.Black), contentAlignment = Alignment.Center) {
            val (_, a, b) = ASPECTS[arIdx]
            val aspect = a.toFloat() / b
            val fitW = if (maxWidth / maxHeight < aspect) maxWidth else maxHeight * aspect
            Canvas(Modifier.size(fitW, fitW / aspect)) {
                val tt = t
                if (an != null) an!!.at(tt + audioStart, bands) else synthBands(tt, bands)
                drawIntoCanvas { StyleRenderer.draw(it.nativeCanvas, size.width.toInt(), size.height.toInt(), tt, spec, bands) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { playing = !playing }, enabled = !exporting) { Text(if (playing) "Pause" else "Play") }
            Slider(value = t.coerceIn(0f, effDur), onValueChange = { playing = false; t = it }, valueRange = 0f..effDur, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            Text(fmtT(t) + " / " + fmtT(effDur), fontSize = 12.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("STYLE") {
                Chips(Style.values().map { it.label }, style.ordinal) { style = Style.values()[it] }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ACCENTS.forEach { col ->
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(col)).border(if (col == accent) 3.dp else 0.dp, Color.White, CircleShape).clickable { accent = col })
                    }
                }
            }
            Section("MEDIA") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickImg.launch(arrayOf("image/*")) }) { Text(if (cover == null) "Choose image" else "Image set") }
                    OutlinedButton(onClick = { pickAudio.launch(arrayOf("audio/*")) }) { Text(if (audioUri == null) "Choose music" else "Music set") }
                }
                an?.let { a ->
                    Text("Start: " + fmtT(audioStart), fontSize = 12.sp)
                    Slider(value = audioStart, onValueChange = { audioStart = it; t = 0f; playing = false }, valueRange = 0f..(a.durSec - 1f).coerceAtLeast(0.1f))
                }
                Text("Use only music and images you own or may use.", fontSize = 11.sp, color = Color.Gray)
            }
            Section("TEXT") {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(handle, { handle = it }, label = { Text("Handle or brand") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Section("EXPORT") {
                Text("Duration: " + dur.roundToInt() + " s", fontSize = 12.sp)
                Slider(value = dur, onValueChange = { dur = it; t = 0f; playing = false }, valueRange = 5f..60f)
                Chips(ASPECTS.map { it.first }, arIdx) { arIdx = it }
                Chips(listOf("720p", "1080p"), if (short == 720) 0 else 1) { short = if (it == 0) 720 else 1080 }
                val (w, h) = dims(ASPECTS[arIdx].second, ASPECTS[arIdx].third, short)
                Text("Output: ${w}x$h MP4 (H.264 + AAC), 30 fps", fontSize = 12.sp, color = Color.Gray)
                if (!exporting) {
                    Button(onClick = {
                        playing = false; result = null; exporting = true; progress = 0f; status = "Rendering... keep the screen on."
                        val ex = Exporter(ctx); exporter = ex
                        val glitch = when (style) { Style.NEON -> 0.9f; Style.PLAYER -> 0.5f; Style.RING -> 0.3f; Style.GLASS -> 0.15f }
                        try {
                            ex.start(spec, w, h, an, audioUri, audioStart, effDur, glitch) { file, err ->
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
            Text("Render: Canvas + OpenGL ES effects via Media3 Transformer. $vk (probe only).", fontSize = 11.sp, color = Color.Gray)
        }
    }
}
