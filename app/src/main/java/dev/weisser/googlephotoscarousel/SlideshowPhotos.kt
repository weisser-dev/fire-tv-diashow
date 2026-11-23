package dev.weisser.googlephotoscarousel

import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Scale

@Composable
internal fun SlideshowPhotos(
    current: String, currentIndex: Int, standby: String?, standbyIndex: Int, advancing: Boolean,
    paused: Boolean, motionEnabled: Boolean, seconds: Int, motionSeconds: Int, mode: String,
    padding: Dp, fadeMillis: Int, onAdvance: (String, Boolean) -> Unit, onFailed: (String) -> Unit,
    onMotionRequired: (Boolean) -> Unit
) {
    val advance by rememberUpdatedState(onAdvance)
    val failed by rememberUpdatedState(onFailed)
    val reportMotion by rememberUpdatedState(onMotionRequired)
    val context = LocalContext.current
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(padding).clipToBounds().background(Color.Black)) {
        val decodeEdge = maxOf(constraints.maxWidth, constraints.maxHeight).coerceAtLeast(1)
        val viewportAspect = maxWidth.value / maxHeight.value
        var currentReady by remember(current) { mutableStateOf(false) }
        val opacity = remember(current, standby) { mutableFloatStateOf(1f) }
        // Both slots use the same call site and photo key. Promotion keeps the decoded
        // painter, measured image and first camera pose of the staged slot intact.
        for (photo in listOfNotNull(current, standby?.takeIf { it != current })) key(photo) {
            val front = photo == current
            val request = remember(photo, decodeEdge) {
                ImageRequest.Builder(context)
                    .data(photo.removePrefix("uri:").removePrefix("file:"))
                    .size(decodeEdge, decodeEdge).scale(Scale.FIT).crossfade(false).build()
            }
            val loader = rememberAsyncImagePainter(request)
            val state = loader.state
            val loaded = state as? AsyncImagePainter.State.Success
            LaunchedEffect(front, advancing, state) {
                if (!front && state is AsyncImagePainter.State.Error) failed(photo)
            }
            if (loaded != null) {
                SideEffect { if (front) currentReady = true }
                val drawable = loaded.result.drawable
                val aspect = if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
                    drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight else viewportAspect
                val horizontal = motionEnabled && aspect > viewportAspect * 1.015f
                val vertical = motionEnabled && aspect < viewportAspect / 1.015f
                val width = if (motionEnabled && aspect > viewportAspect) maxHeight * aspect else maxWidth
                val height = if (motionEnabled && aspect < viewportAspect) maxWidth / aspect else maxHeight
                val direction = if ((if (front) currentIndex else standbyIndex) % 2 == 0) 1f else -1f
                val travelX = if (horizontal) with(density) { ((width * 1.045f - maxWidth) / 2f).toPx() } * direction else 0f
                val travelY = if (vertical) with(density) { ((height * 1.045f - maxHeight) / 2f).toPx() } * direction else 0f
                var movement by remember { mutableFloatStateOf(0f) }
                val driving = if (advancing) !front else front
                val duration = if (horizontal || vertical) maxOf(seconds, motionSeconds) else seconds
                LaunchedEffect(front, horizontal, vertical) {
                    if (front) reportMotion(horizontal || vertical)
                }
                // Render the staged texture once behind the opaque front before starting
                // the fade. During steady playback only the front slot needs to draw.
                var warming by remember(loaded) { mutableStateOf(!front) }
                LaunchedEffect(loaded, currentReady) {
                    if (warming && currentReady) {
                        withFrameNanos { }
                        withFrameNanos { }
                        warming = false
                    }
                }
                // The incoming slot drives both phases from the same vsync timestamps.
                // Its effect stays alive across promotion (driving remains true), so the
                // first motion frame needs neither a new animator nor a recomposition.
                LaunchedEffect(driving, paused, motionEnabled, duration, travelX, travelY, fadeMillis) {
                    if (!driving) {
                        if (!front) movement = 0f
                        return@LaunchedEffect
                    }
                    val entering = !front
                    if (!entering && paused) return@LaunchedEffect
                    movement = 0f
                    if (entering) withFrameNanos { }
                    val fadeNs = if (entering) fadeMillis * 1_000_000L else 0L
                    val motionNs = if (motionEnabled && !paused) duration * 1_000_000_000L else 0L
                    val start = withFrameNanos { it }
                    if (entering && BuildConfig.DEBUG) Log.i("StillframeFade", "fade started")
                    var promoted = !entering
                    var elapsed = 0L
                    do {
                        if (elapsed < fadeNs) {
                            opacity.floatValue = 1f - elapsed.toFloat() / fadeNs
                        } else {
                            if (motionNs > 0) movement = ((elapsed - fadeNs).toFloat() / motionNs).coerceIn(0f, 1f)
                            if (!promoted) {
                                opacity.floatValue = 0f
                                promoted = true
                                advance(photo, horizontal || vertical)
                            }
                        }
                        if (elapsed >= fadeNs + motionNs) break
                        elapsed = withFrameNanos { it - start }
                    } while (true)
                }
                // Opaque, screen-filling photos need only one bitmap alpha multiply.
                // Photos with transparency or letterboxing also blend their black matte.
                val translucent = (drawable as? BitmapDrawable)?.bitmap?.hasAlpha() != false
                val matte = when {
                    !front -> Modifier // The viewport behind the staged photo is already black.
                    !advancing -> Modifier.background(Color.Black)
                    translucent -> Modifier.graphicsLayer { alpha = opacity.floatValue }.background(Color.Black)
                    else -> Modifier.drawWithCache {
                        // Only letterboxing needs black paint while fading an opaque bitmap.
                        // Calculate its bounds once from the frozen camera pose.
                        val p = if (motionEnabled) movement else 0f
                        val fit = !motionEnabled && mode == "fit"
                        val w = if (fit) minOf(size.width, size.height * aspect) else width.toPx()
                        val h = if (fit) minOf(size.height, size.width / aspect) else height.toPx()
                        val halfW = w * (1f + 0.045f * p) / 2f
                        val halfH = h * (1f + 0.045f * p) / 2f
                        val cx = size.width / 2f + travelX * (2f * p - 1f)
                        val cy = size.height / 2f + travelY * (2f * p - 1f)
                        val hasBars = cx - halfW > 0f || cy - halfH > 0f ||
                            cx + halfW < size.width || cy + halfH < size.height
                        onDrawBehind {
                            if (hasBars) clipRect(cx - halfW, cy - halfH, cx + halfW, cy + halfH, ClipOp.Difference) {
                                drawRect(Color.Black, alpha = opacity.floatValue)
                            }
                        }
                    }
                }
                Box(
                    Modifier.fillMaxSize().zIndex(if (front) 1f else 0f)
                        .drawWithContent { if (front || advancing || (warming && currentReady)) drawContent() }
                        .then(matte),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = loaded.painter, contentDescription = if (front) "Slideshow Foto" else null,
                        modifier = Modifier.requiredSize(width, height).graphicsLayer {
                            val p = if (motionEnabled) movement else 0f
                            scaleX = 1f + 0.045f * p
                            scaleY = scaleX
                            translationX = travelX * (2f * p - 1f)
                            translationY = travelY * (2f * p - 1f)
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                            alpha = if (front && !translucent) opacity.floatValue else 1f
                        },
                        contentScale = when {
                            motionEnabled || mode == "stretch" -> ContentScale.FillBounds
                            mode == "fill" -> ContentScale.Crop
                            else -> ContentScale.Fit
                        }
                    )
                }
            }
        }
    }
}
