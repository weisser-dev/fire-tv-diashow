package dev.weisser.googlephotoscarousel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import android.util.AttributeSet
import android.view.TextureView
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A low-overhead two-texture compositor for the slideshow's fade transitions. */
internal class GpuSlideshowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imageLoader: ImageLoader = context.imageLoader
    private val renderer = PhotoGlRenderer()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var currentModel: String? = null
    private var preparedModel: String? = null
    private var desiredNextModel: String? = null
    private var loadingModel: String? = null
    private var transitionRunning = false
    private var targetDurationMs = 1000
    private var targetScaleMode = "fit"
    private var motionEnabled = true
    private var slideSeconds = 6
    private var motionSeconds = 15
    private var rendererOptionsInitialized = false
    private var appliedScaleMode = "fit"
    private var appliedMotionEnabled = true
    private var appliedSlideSeconds = 6
    private var appliedMotionSeconds = 15
    private var onPhotoReady: (String) -> Unit = {}
    private var onPhotoFailed: (String) -> Unit = {}
    private var onMotionRequired: (Boolean) -> Unit = {}
    private var onRendererFailure: () -> Unit = {}
    private var lastMotionReport: Pair<String, Boolean>? = null
    private var released = false

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun configure(
        current: String,
        next: String?,
        durationMs: Int,
        scaleMode: String,
        motion: Boolean,
        seconds: Int,
        panDuration: Int,
        onReady: (String) -> Unit,
        onFailed: (String) -> Unit,
        onMotionRequired: (Boolean) -> Unit,
        onRendererFailure: () -> Unit
    ) {
        if (released) return
        targetDurationMs = durationMs.coerceAtLeast(1)
        targetScaleMode = scaleMode
        motionEnabled = motion
        slideSeconds = seconds
        motionSeconds = panDuration
        onPhotoReady = onReady
        onPhotoFailed = onFailed
        this.onMotionRequired = onMotionRequired
        this.onRendererFailure = onRendererFailure
        desiredNextModel = next?.takeUnless { it == current }
        if (!rendererOptionsInitialized || targetScaleMode != appliedScaleMode || motionEnabled != appliedMotionEnabled ||
            slideSeconds != appliedSlideSeconds || motionSeconds != appliedMotionSeconds) {
            rendererOptionsInitialized = true
            appliedScaleMode = targetScaleMode
            appliedMotionEnabled = motionEnabled
            appliedSlideSeconds = slideSeconds
            appliedMotionSeconds = motionSeconds
            renderer.updateOptions(targetScaleMode, motionEnabled, slideSeconds, motionSeconds)
        }

        if (currentModel == null) {
            currentModel = current
            loadPhoto(current, initial = true)
            return
        }
        if (current != currentModel) {
            if (current == preparedModel) {
                // The prepared next texture becomes the incoming image. Keep only the
                // outgoing and incoming textures alive until the shader finishes.
                preparedModel = null
                currentModel = current
                transitionRunning = true
                renderer.promotePrepared(current, targetDurationMs)
            } else {
                currentModel = current
                transitionRunning = true
                loadPhoto(current, initial = false)
            }
            return
        }
        if (!transitionRunning) preloadDesiredNext()
    }

    private fun loadPhoto(model: String, initial: Boolean) {
        if (loadingModel == model || released) return
        loadingModel = model
        ioScope.launch {
            val bitmap = decode(model)
            mainHandler.post {
                loadingModel = null
                if (released) return@post
                        if (bitmap == null) {
                    if (initial) onPhotoFailed(model) else onPhotoFailed(model)
                    if (!initial) {
                        transitionRunning = false
                        currentModel = renderer.currentModel
                        preloadDesiredNext()
                    }
                    return@post
                }
                if (initial) {
                    renderer.setInitial(bitmap, targetScaleMode, motionEnabled, slideSeconds, motionSeconds) {
                        reportMotion(model, bitmap.width, bitmap.height)
                        preloadDesiredNext()
                    }
                } else {
                    renderer.transitionTo(model, bitmap, targetDurationMs, targetScaleMode, motionEnabled, slideSeconds, motionSeconds)
                }
            }
        }
    }

    private fun preloadDesiredNext() {
        val next = desiredNextModel ?: return
        if (released || transitionRunning || next == currentModel || next == preparedModel || loadingModel == next) return
        loadingModel = next
        ioScope.launch {
            val bitmap = decode(next)
            mainHandler.post {
                loadingModel = null
                if (released) return@post
                if (bitmap == null) {
                    onPhotoFailed(next)
                    return@post
                }
                if (next != desiredNextModel || next == currentModel) return@post
                preparedModel = next
                renderer.prepare(bitmap) { onPhotoReady(next) }
            }
        }
    }

    private suspend fun decode(model: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(model)
                .size(1920, 1920)
                .precision(Precision.INEXACT)
                .allowHardware(false)
                .build()
            val drawable = imageLoader.execute(request).drawable ?: return@runCatching null
            if (drawable is BitmapDrawable) drawable.bitmap
            else {
                val width = drawable.intrinsicWidth.coerceIn(1, 1920)
                val height = drawable.intrinsicHeight.coerceIn(1, 1920)
                val ratio = min(1f, 1920f / max(width, height))
                val bitmap = Bitmap.createBitmap((width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.bounds = Rect(0, 0, bitmap.width, bitmap.height)
                drawable.draw(canvas)
                bitmap
            }
        }.getOrNull()
    }

    private fun reportMotion(model: String, width: Int, height: Int) {
        val viewAspect = if (this.width > 0 && this.height > 0) this.width.toFloat() / this.height else 1f
        val imageAspect = width.toFloat() / height
        val required = abs(imageAspect - viewAspect) > max(imageAspect, viewAspect) * 0.015f
        val report = model to required
        if (lastMotionReport != report) {
            lastMotionReport = report
            onMotionRequired(required)
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        renderer.start(surface, width, height,
            { imageWidth, imageHeight -> mainHandler.post { onTransitionCompleted(imageWidth, imageHeight) } },
            { mainHandler.post { onRendererFailure() } })
        currentModel?.let { model ->
            if (preparedModel == null && loadingModel == null) loadPhoto(model, initial = true)
        }
    }

    private fun onTransitionCompleted(imageWidth: Int, imageHeight: Int) {
        transitionRunning = false
        preparedModel = null
        currentModel?.let { reportMotion(it, imageWidth, imageHeight) }
        preloadDesiredNext()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = renderer.resize(width, height)
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderer.stop()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        released = true
        ioScope.cancel()
        renderer.stop()
    }

    private inner class PhotoGlRenderer {
        @Volatile var currentModel: String? = null
            private set
        private val commands = ConcurrentLinkedQueue<() -> Unit>()
        private val lock = Object()
        private var renderThread: Thread? = null
        private var running = AtomicBoolean(false)
        private var dirty = true
        @Volatile private var animate = false
        @Volatile private var dirtyFrame = true
        private var surfaceWidth = 1
        private var surfaceHeight = 1
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var shader = 0
        private var positionLocation = -1
        private var texCoordLocation = -1
        private var textureALocation = -1
        private var textureBLocation = -1
        private var sizeALocation = -1
        private var sizeBLocation = -1
        private var viewportLocation = -1
        private var modeLocation = -1
        private var progressLocation = -1
        private var motionALocation = -1
        private var motionProgressALocation = -1
        private var movingALocation = -1
        private var motionBLocation = -1
        private var motionProgressBLocation = -1
        private var movingBLocation = -1
        private var currentTexture = 0
        private var nextTexture = 0
        private var currentWidth = 1
        private var currentHeight = 1
        private var nextWidth = 1
        private var nextHeight = 1
        private var transitionStart = 0L
        private var transitionDuration = 1000L
        private var transitionMotionEnabled = false
        private var transitionMotionProgress = 0f
        private var transitionIncomingProgress = 0f
        private var transitioningModel: String? = null
        private var completion: ((Int, Int) -> Unit)? = null
        private var scaleMode = "fit"
        private var motion = true
        private var movementDuration = 6000L
        private var movementStart = System.nanoTime()
        private var motionStartProgress = 0f
        private var motionDirection = 1f
        private var perfWindowStartNanos = 0L
        private var perfLastFrameStartNanos = 0L
        private var perfFrames = 0
        private var perfLate20 = 0
        private var perfLate33 = 0
        private var perfTotalGapNanos = 0L
        private var perfMaxGapNanos = 0L
        private var perfTotalDrawNanos = 0L
        private var perfMaxDrawNanos = 0L
        private var perfTotalSwapNanos = 0L
        private var perfMaxSwapNanos = 0L
        private var slideDurationSeconds = 6
        private var panDurationSeconds = 15
        private val vertices = floatBuffer(floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f
        ))

        fun start(surface: SurfaceTexture, width: Int, height: Int, onComplete: (Int, Int) -> Unit, onFailure: () -> Unit) {
            stop()
            surfaceWidth = width.coerceAtLeast(1)
            surfaceHeight = height.coerceAtLeast(1)
            completion = onComplete
            running.set(true)
            renderThread = Thread({ renderLoop(surface, onFailure) }, "stillframe-gles").apply { isDaemon = true; start() }
        }

        fun resize(width: Int, height: Int) {
            surfaceWidth = width.coerceAtLeast(1)
            surfaceHeight = height.coerceAtLeast(1)
            wake(true)
        }

        fun setInitial(bitmap: Bitmap, mode: String, moving: Boolean, seconds: Int, panSeconds: Int, ready: () -> Unit) {
            scaleMode = mode
            motion = moving
            slideDurationSeconds = seconds
            panDurationSeconds = panSeconds
            movementDuration = (seconds * 1000L).coerceAtLeast(1000L)
            command {
                deleteTexture(currentTexture)
                currentTexture = upload(bitmap)
                currentWidth = bitmap.width
                currentHeight = bitmap.height
                currentModel = this@GpuSlideshowView.currentModel
                movementStart = System.nanoTime()
                motionStartProgress = 0f
                motionDirection = 1f
                movementDuration = durationFor(bitmap.width, bitmap.height)
                updateAnimationMode()
                mainHandler.post { ready() }
            }
        }

        fun updateOptions(mode: String, moving: Boolean, seconds: Int, panSeconds: Int) {
            scaleMode = mode
            motion = moving
            slideDurationSeconds = seconds
            panDurationSeconds = panSeconds
            command {
                if (currentTexture != 0) movementDuration = durationFor(currentWidth, currentHeight)
                movementStart = System.nanoTime()
                updateAnimationMode()
            }
        }

        fun prepare(bitmap: Bitmap, ready: () -> Unit) {
            command {
                deleteTexture(nextTexture)
                nextTexture = upload(bitmap)
                nextWidth = bitmap.width
                nextHeight = bitmap.height
                mainHandler.post { ready() }
            }
        }

        fun promotePrepared(model: String, durationMs: Int) {
            command {
                if (nextTexture == 0) return@command
                transitioningModel = model
                beginTransition(durationMs)
                movementDuration = durationFor(nextWidth, nextHeight)
            }
        }

        fun transitionTo(model: String, bitmap: Bitmap, durationMs: Int, mode: String, moving: Boolean, seconds: Int, panSeconds: Int) {
            val nextMovementDuration = durationFor(bitmap.width, bitmap.height, moving, seconds, panSeconds)
            command {
                deleteTexture(nextTexture)
                nextTexture = upload(bitmap)
                nextWidth = bitmap.width
                nextHeight = bitmap.height
                transitioningModel = model
                // Capture the outgoing image's actual motion phase before adopting the
                // incoming photo's aspect ratio or motion duration.
                beginTransition(durationMs)
                scaleMode = mode
                motion = moving
                movementDuration = nextMovementDuration
            }
        }

        private fun beginTransition(durationMs: Int) {
            val now = System.nanoTime()
            transitionMotionEnabled = motion && currentTexture != 0
            // Hold A on its final frame throughout the dissolve. B enters on the
            // first frame of its own pan, then starts moving once the dissolve ends.
            transitionMotionProgress = if (transitionMotionEnabled && motionDirection > 0f) 1f else 0f
            transitionIncomingProgress = if (transitionMotionEnabled && motionDirection > 0f) 1f else 0f
            transitionDuration = durationMs.coerceAtLeast(1).toLong()
            transitionStart = now
            animate = true
            dirty = true
        }

        private fun command(block: () -> Unit) {
            commands.add(block)
            wake(true)
        }

        private fun wake(markDirty: Boolean) {
            synchronized(lock) {
                if (markDirty) { dirty = true; dirtyFrame = true }
                lock.notifyAll()
            }
        }

        fun stop() {
            running.set(false)
            synchronized(lock) { lock.notifyAll() }
            renderThread?.interrupt()
            runCatching { renderThread?.join(500) }
            renderThread = null
            releaseEgl()
        }

        private fun renderLoop(surface: SurfaceTexture, onFailure: () -> Unit) {
            try {
                createEgl(surface)
                shader = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
                cacheShaderLocations()
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
                while (running.get()) {
                    while (true) (commands.poll() ?: break).invoke()
                    val shouldDraw = synchronized(lock) {
                        if (!dirtyFrame && !animate) lock.wait(10000)
                        dirtyFrame || animate
                    }
                    if (!running.get()) break
                    if (!shouldDraw) continue
                    val now = System.nanoTime()
                    val frameStartNanos = System.nanoTime()
                    if (BuildConfig.DEBUG) recordFrameStart(frameStartNanos)
                    val drawStartNanos = System.nanoTime()
                    draw(now)
                    val drawElapsedNanos = System.nanoTime() - drawStartNanos
                    val swapStartNanos = System.nanoTime()
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                    val swapElapsedNanos = System.nanoTime() - swapStartNanos
                    if (BuildConfig.DEBUG) recordFrameWork(drawElapsedNanos, swapElapsedNanos, System.nanoTime())
                    if (transitionStart > 0L && (now - transitionStart) / 1_000_000f >= transitionDuration) {
                        if (nextTexture != 0) {
                            deleteTexture(currentTexture)
                            currentTexture = nextTexture
                            currentWidth = nextWidth
                            currentHeight = nextHeight
                            nextTexture = 0
                        }
                        currentModel = transitioningModel
                        transitioningModel = null
                        if (transitionMotionEnabled) motionDirection *= -1f
                        motionStartProgress = if (transitionMotionEnabled) transitionMotionProgress else 0f
                        transitionStart = 0L
                        movementStart = now
                        animate = hasMotionRemaining(now)
                        wake(false)
                        completion?.invoke(currentWidth, currentHeight)
                    } else {
                        animate = transitionStart > 0L || hasMotionRemaining(now)
                    }
                    dirtyFrame = false
                    // eglSwapBuffers already applies display pacing on Fire OS. Sleeping
                    // here as well serialized a second frame wait after every swap and
                    // capped this 1080x1920 renderer at roughly 16 fps on AFTM hardware.
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                // Surface destruction during shutdown is expected on Fire OS.
                if (running.get()) onFailure()
            } finally {
                releaseEgl()
            }
        }

        private fun recordFrameStart(frameStartNanos: Long) {
            if (perfWindowStartNanos == 0L) perfWindowStartNanos = frameStartNanos
            if (perfLastFrameStartNanos != 0L) {
                val gap = frameStartNanos - perfLastFrameStartNanos
                perfTotalGapNanos += gap
                perfMaxGapNanos = max(perfMaxGapNanos, gap)
                if (gap > 20_000_000L) perfLate20++
                if (gap > 33_000_000L) perfLate33++
            }
            perfLastFrameStartNanos = frameStartNanos
            perfFrames++
        }

        private fun recordFrameWork(drawNanos: Long, swapNanos: Long, now: Long) {
            perfTotalDrawNanos += drawNanos
            perfMaxDrawNanos = max(perfMaxDrawNanos, drawNanos)
            perfTotalSwapNanos += swapNanos
            perfMaxSwapNanos = max(perfMaxSwapNanos, swapNanos)
            val elapsed = now - perfWindowStartNanos
            if (elapsed < 5_000_000_000L) return
            val frames = perfFrames.coerceAtLeast(1)
            fun avgMs(total: Long) = total / frames / 1_000_000f
            val locale = java.util.Locale.US
            Log.i("StillframePerf", String.format(
                locale,
                "GL %dx%d fps=%.1f gapAvg=%.1fms gapMax=%.1fms >20ms=%d >33ms=%d drawAvg=%.2fms drawMax=%.2fms swapAvg=%.2fms swapMax=%.2fms",
                surfaceWidth, surfaceHeight, perfFrames * 1_000_000_000f / elapsed,
                avgMs(perfTotalGapNanos), perfMaxGapNanos / 1_000_000f, perfLate20, perfLate33,
                avgMs(perfTotalDrawNanos), perfMaxDrawNanos / 1_000_000f,
                avgMs(perfTotalSwapNanos), perfMaxSwapNanos / 1_000_000f
            ))
            perfWindowStartNanos = now
            perfLastFrameStartNanos = 0L
            perfFrames = 0
            perfLate20 = 0
            perfLate33 = 0
            perfTotalGapNanos = 0L
            perfMaxGapNanos = 0L
            perfTotalDrawNanos = 0L
            perfMaxDrawNanos = 0L
            perfTotalSwapNanos = 0L
            perfMaxSwapNanos = 0L
        }

        private fun createEgl(surface: SurfaceTexture) {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(eglDisplay != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(eglDisplay, IntArray(2), 0, IntArray(2), 0))
            val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0)
            val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttrs, 0)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(eglContext != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_DITHER)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
        }

        private fun draw(now: Long) {
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (shader == 0 || currentTexture == 0) return
            GLES20.glUseProgram(shader)
            vertices.position(0)
            GLES20.glEnableVertexAttribArray(positionLocation)
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
            vertices.position(2)
            GLES20.glEnableVertexAttribArray(texCoordLocation)
            GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
            bindTexture(textureALocation, currentTexture, 0)
            bindTexture(textureBLocation, if (nextTexture == 0) currentTexture else nextTexture, 1)
            GLES20.glUniform2f(sizeALocation, currentWidth.toFloat(), currentHeight.toFloat())
            GLES20.glUniform2f(sizeBLocation, if (nextTexture == 0) currentWidth.toFloat() else nextWidth.toFloat(), if (nextTexture == 0) currentHeight.toFloat() else nextHeight.toFloat())
            GLES20.glUniform2f(viewportLocation, surfaceWidth.toFloat(), surfaceHeight.toFloat())
            GLES20.glUniform1i(modeLocation, when (scaleMode) { "stretch" -> 2; "fill", "ken_burns" -> 1; else -> 0 })
            val inTransition = transitionStart > 0L && nextTexture != 0
            val progress = if (inTransition) ((now - transitionStart).toDouble() / (transitionDuration * 1_000_000.0)).toFloat().coerceIn(0f, 1f) else 0f
            GLES20.glUniform1f(progressLocation, progress)
            val outgoingMotion = if (inTransition) transitionMotionEnabled else motion
            val outgoingProgress = if (inTransition) transitionMotionProgress else if (motion) motionProgress(now) else 0f
            val incomingMotion = inTransition && transitionMotionEnabled
            GLES20.glUniform1f(motionALocation, if (outgoingMotion) 1.0f + 0.045f * outgoingProgress else 1f)
            GLES20.glUniform1f(motionProgressALocation, outgoingProgress)
            GLES20.glUniform1f(movingALocation, if (outgoingMotion) 1f else 0f)
            GLES20.glUniform1f(motionBLocation, if (incomingMotion) 1.0f + 0.045f * transitionIncomingProgress else 1f)
            GLES20.glUniform1f(motionProgressBLocation, if (incomingMotion) transitionIncomingProgress else 0f)
            GLES20.glUniform1f(movingBLocation, if (incomingMotion) 1f else 0f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionLocation)
            GLES20.glDisableVertexAttribArray(texCoordLocation)
        }

        private fun cacheShaderLocations() {
            positionLocation = GLES20.glGetAttribLocation(shader, "aPosition")
            texCoordLocation = GLES20.glGetAttribLocation(shader, "aTexCoord")
            textureALocation = GLES20.glGetUniformLocation(shader, "uTextureA")
            textureBLocation = GLES20.glGetUniformLocation(shader, "uTextureB")
            sizeALocation = GLES20.glGetUniformLocation(shader, "uSizeA")
            sizeBLocation = GLES20.glGetUniformLocation(shader, "uSizeB")
            viewportLocation = GLES20.glGetUniformLocation(shader, "uViewport")
            modeLocation = GLES20.glGetUniformLocation(shader, "uMode")
            progressLocation = GLES20.glGetUniformLocation(shader, "uProgress")
            motionALocation = GLES20.glGetUniformLocation(shader, "uMotionA")
            motionProgressALocation = GLES20.glGetUniformLocation(shader, "uMotionProgressA")
            movingALocation = GLES20.glGetUniformLocation(shader, "uMovingA")
            motionBLocation = GLES20.glGetUniformLocation(shader, "uMotionB")
            motionProgressBLocation = GLES20.glGetUniformLocation(shader, "uMotionProgressB")
            movingBLocation = GLES20.glGetUniformLocation(shader, "uMovingB")
        }

        private fun upload(bitmap: Bitmap): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            return textures[0]
        }

        private fun deleteTexture(texture: Int) {
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        }

        private fun updateAnimationMode() {
            animate = hasMotionRemaining(System.nanoTime())
        }

        private fun hasMotionRemaining(now: Long): Boolean {
            if (!motion || currentTexture == 0) return false
            val progress = motionProgress(now)
            return if (motionDirection > 0f) progress < 1f else progress > 0f
        }

        private fun motionProgress(now: Long): Float =
            (motionStartProgress + motionDirection * ((now - movementStart).toDouble() / (movementDuration * 1_000_000.0)).toFloat()).coerceIn(0f, 1f)

        private fun durationFor(width: Int, height: Int, moving: Boolean = motion, seconds: Int = slideDurationSeconds, panSeconds: Int = panDurationSeconds): Long {
            val imageAspect = width.toFloat() / height.coerceAtLeast(1)
            val viewAspect = surfaceWidth.toFloat() / surfaceHeight.coerceAtLeast(1)
            val requiresPan = abs(imageAspect - viewAspect) > max(imageAspect, viewAspect) * 0.015f
            return (if (moving && requiresPan) max(seconds, panSeconds) else seconds).coerceAtLeast(1) * 1000L
        }

        private fun releaseEgl() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
            shader = 0
            currentTexture = 0
            nextTexture = 0
            currentModel = null
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vUv;
            void main() { gl_Position = vec4(aPosition, 0.0, 1.0); vUv = aTexCoord; }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTextureA;
            uniform sampler2D uTextureB;
            uniform vec2 uSizeA;
            uniform vec2 uSizeB;
            uniform vec2 uViewport;
            uniform int uMode;
            uniform float uProgress;
            uniform float uMotionA;
            uniform float uMotionProgressA;
            uniform float uMovingA;
            uniform float uMotionB;
            uniform float uMotionProgressB;
            uniform float uMovingB;
            vec2 mapUv(vec2 uv, vec2 size, float motionScale, bool moving, float motionProgress) {
                float imageAspect = size.x / size.y;
                float viewAspect = uViewport.x / uViewport.y;
                vec2 crop = vec2(1.0);
                if (uMode == 1 || moving) {
                    if (imageAspect > viewAspect) crop.x = viewAspect / imageAspect;
                    else crop.y = imageAspect / viewAspect;
                }
                if (moving) {
                    crop /= motionScale;
                    if (imageAspect >= viewAspect) crop.x = min(crop.x, 1.0);
                    else crop.y = min(crop.y, 1.0);
                }
                vec2 offset = vec2(0.0);
                if (moving) {
                    float travel = (motionProgress - 0.5) * (1.0 - crop.x);
                    float travelY = (motionProgress - 0.5) * (1.0 - crop.y);
                    if (imageAspect >= viewAspect) offset.x = travel;
                    else offset.y = travelY;
                }
                vec2 mapped = vec2(0.5) + (uv - 0.5) * crop + offset;
                if (uMode == 0 && !moving) {
                    float coverage = imageAspect / viewAspect;
                    if (coverage < 1.0) {
                        float margin = (1.0 - coverage) * 0.5;
                        if (uv.x < margin || uv.x > 1.0 - margin) return vec2(-1.0);
                        mapped.x = (uv.x - margin) / coverage;
                    } else {
                        float fraction = viewAspect / imageAspect;
                        float margin = (1.0 - fraction) * 0.5;
                        if (uv.y < margin || uv.y > 1.0 - margin) return vec2(-1.0);
                        mapped.y = (uv.y - margin) / fraction;
                    }
                } else if (uMode == 2 && !moving) mapped = uv;
                return mapped;
            }
            void main() {
                bool movingA = uMovingA > 0.5;
                vec2 uvA = mapUv(vUv, uSizeA, uMotionA, movingA, uMotionProgressA);
                vec4 a = (uvA.x < 0.0 || uvA.y < 0.0) ? vec4(0.0, 0.0, 0.0, 1.0) : texture2D(uTextureA, uvA);
                if (uProgress <= 0.0) { gl_FragColor = a; return; }
                bool movingB = uMovingB > 0.5;
                vec2 uvB = mapUv(vUv, uSizeB, uMotionB, movingB, uMotionProgressB);
                vec4 b = (uvB.x < 0.0 || uvB.y < 0.0) ? vec4(0.0, 0.0, 0.0, 1.0) : texture2D(uTextureB, uvB);
                gl_FragColor = mix(a, b, uProgress);
            }
        """

        private fun floatBuffer(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }

        private fun createProgram(vertex: String, fragment: String): Int {
            fun compile(type: Int, source: String): Int {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val status = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
                check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
                return shader
            }
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            return GLES20.glCreateProgram().also { program ->
                GLES20.glAttachShader(program, vs)
                GLES20.glAttachShader(program, fs)
                GLES20.glLinkProgram(program)
                val status = IntArray(1)
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
                check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(program) }
                GLES20.glDeleteShader(vs)
                GLES20.glDeleteShader(fs)
            }
        }

        private fun bindTexture(location: Int, texture: Int, unit: Int) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(location, unit)
        }
    }
}
