package com.zain.holoos.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.SystemClock
import android.util.Range
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.zain.holoos.holo.HoloColors
import com.zain.holoos.holo.holoText
import com.zain.holoos.tracking.handHologram
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone-as-sensor: the front camera feeds MediaPipe on the phone's GPU and only landmark
 * coordinates are streamed to the PC over the USB cable (`adb reverse tcp:47475`), in the
 * same packet format as the desktop webcam tracker. The camera image is never displayed,
 * stored or sent: the screen shows a skeleton only.
 */
private val DEFAULT_DELEGATE = Delegate.GPU

class SensorActivity : ComponentActivity() {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var hands: HandLandmarker? = null
    private var pose: PoseLandmarker? = null
    private val outbox = AtomicReference<ByteArray?>(null)
    @Volatile private var running = true

    private var status by mutableStateOf("Starting camera…")
    private var linkUp by mutableStateOf(false)
    private var fps by mutableFloatStateOf(0f)
    private var inferMs by mutableFloatStateOf(0f)
    private var handCount by mutableIntStateOf(0)
    private val skeletons = arrayOf(FloatArray(42), FloatArray(42))
    private var frameW = 640; private var frameH = 480

    @Volatile private var shoulders: FloatArray? = null
    private var frameId = 0
    private var fpsCount = 0; private var fpsStart = 0L
    private val pending = HashMap<Long, Pair<Long, Double>>() // timestamp -> (start uptime, capture wall ms)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        useBack = intent.getStringExtra("camera")?.let { it != "front" } ?: prefs.getBoolean("back", true)
        setContent { SensorScreen() }
        Thread(::sendLoop, "holo-link").apply { isDaemon = true }.start()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) start()
        else registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) start() else status = "Camera permission denied" }
            .launch(Manifest.permission.CAMERA)
    }

    /** "gpu" or "cpu"; pass `--es delegate cpu` to compare. Defaults to whichever measured faster. */
    private val delegate: Delegate by lazy {
        when (intent.getStringExtra("delegate")) { "gpu" -> Delegate.GPU; "cpu" -> Delegate.CPU; else -> DEFAULT_DELEGATE }
    }
    /** Run the body model on every Nth frame (0 = off). `--ei poseEvery 0` to compare. */
    private val poseEvery: Int by lazy { intent.getIntExtra("poseEvery", 6) }
    private var convMs = 0f
    private var lastLog = 0L

    private fun start() {
        analysisExecutor.execute {
            runCatching {
                hands = HandLandmarker.createFromOptions(this, HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(delegate).build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.5f).setMinHandPresenceConfidence(0.5f).setMinTrackingConfidence(0.5f)
                    .setResultListener(::onHands)
                    .build())
                pose = PoseLandmarker.createFromOptions(this, PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").setDelegate(delegate).build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumPoses(1)
                    .setResultListener { r, _ ->
                        val lm = r.landmarks().firstOrNull()
                        shoulders = lm?.let { floatArrayOf(it[11].x(), it[11].y(), it[12].x(), it[12].y()) }
                    }
                    .build())
            }.onFailure { status = "MediaPipe failed: ${it.message}"; return@execute }
            runOnUiThread { bindCamera() }
        }
    }

    /**
     * The back camera is the default: a far better sensor (less noise in low light means
     * steadier landmarks). The phone's screen then faces away, which is fine: it only
     * shows a skeleton. Tap the screen to switch; the choice is remembered.
     */
    private var useBack by mutableStateOf(true)
    private val prefs by lazy { getSharedPreferences("sensor", MODE_PRIVATE) }

    /** Highest frame rate the chosen camera offers, up to 60. */
    private fun bestFpsRange(): Range<Int>? = runCatching {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val facing = if (useBack) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
        cm.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper <= 60 }?.maxWithOrNull(compareBy({ it.upper }, { it.lower }))
    }.getOrNull()

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val builder = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            bestFpsRange()?.let { Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            val analysis = builder.build().also { it.setAnalyzer(analysisExecutor, ::analyze) }
            provider.unbindAll()
            provider.bindToLifecycle(this, if (useBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            status = "${if (useBack) "Back" else "Front"} camera · range ${bestFpsRange() ?: "default"} fps"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        val captureWall = System.currentTimeMillis().toDouble()
        val t0 = SystemClock.uptimeMillis()
        val src = image.toBitmap()
        // Upright and mirrored, exactly like the desktop tracker's input.
        val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()); postScale(-1f, 1f) }
        val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        image.close()
        frameW = bmp.width; frameH = bmp.height
        convMs = convMs * 0.9f + (SystemClock.uptimeMillis() - t0) * 0.1f
        val mp = BitmapImageBuilder(bmp).build()
        val ts = t0
        synchronized(pending) {
            pending[ts] = t0 to captureWall
            // LIVE_STREAM drops frames under load; forget timings that will never resolve.
            if (pending.size > 32) pending.keys.removeAll { it < ts - 1000 }
        }
        hands?.detectAsync(mp, ts)
        if (poseEvery > 0 && frameId % poseEvery == 0) pose?.detectAsync(mp, ts)
        frameId++
    }

    private fun onHands(r: HandLandmarkerResult, @Suppress("UNUSED_PARAMETER") input: com.google.mediapipe.framework.image.MPImage) {
        val (t0, captureWall) = synchronized(pending) { pending.remove(r.timestampMs()) } ?: return
        inferMs = inferMs * 0.9f + (SystemClock.uptimeMillis() - t0) * 0.1f
        val now = SystemClock.uptimeMillis()
        if (fpsStart == 0L) fpsStart = now
        fpsCount++
        if (now - fpsStart >= 1000) { fps = fpsCount * 1000f / (now - fpsStart); fpsCount = 0; fpsStart = now }
        if (now - lastLog > 2000) { lastLog = now; android.util.Log.i("HoloSensor", "delegate=$delegate fps=${fps.toInt()} total=${inferMs.toInt()}ms convert=${convMs.toInt()}ms size=${frameW}x$frameH") }

        val n = r.landmarks().size.coerceAtMost(2)
        val buf = ByteBuffer.allocate(64 + n * (5 + 63 * 4)).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("HOLO".toByteArray()).put(1).putDouble(captureWall).putInt(frameId).putFloat(fps)
        buf.putShort(frameW.toShort()).putShort(frameH.toShort())
        val sh = shoulders
        if (sh != null) { buf.put(1); sh.forEach { buf.putFloat(it) } } else { buf.put(0); repeat(4) { buf.putFloat(0f) } }
        buf.put(n.toByte())
        val a = frameW.toFloat() / frameH
        for (i in 0 until n) {
            val cat = r.handedness()[i].first()
            buf.put(if (cat.categoryName() == "Right") 1 else 0).putFloat(cat.score())
            val lm = r.landmarks()[i]
            for ((j, p) in lm.withIndex()) {
                buf.putFloat(p.x()).putFloat(p.y()).putFloat(p.z())
                skeletons[i][j * 2] = p.x() * a; skeletons[i][j * 2 + 1] = p.y()
            }
        }
        handCount = n
        outbox.set(buf.array().copyOf(buf.position()))
    }

    /** Keeps a TCP link to the PC (through adb reverse) and sends only the newest packet. */
    private fun sendLoop() {
        while (running) {
            try {
                Socket().use { s ->
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress("127.0.0.1", 47475), 1000)
                    linkUp = true
                    val out = DataOutputStream(s.getOutputStream().buffered())
                    while (running) {
                        val p = outbox.getAndSet(null)
                        if (p == null) { Thread.sleep(1); continue }
                        out.writeShort(p.size); out.write(p); out.flush()
                    }
                }
            } catch (_: Exception) {
                linkUp = false
                Thread.sleep(1000)
            }
        }
    }

    override fun onDestroy() {
        running = false
        analysisExecutor.shutdown()
        hands?.close(); pose?.close()
        super.onDestroy()
    }

    private fun switchCamera() {
        useBack = !useBack
        prefs.edit().putBoolean("back", useBack).apply()
        bindCamera()
    }

    @androidx.compose.runtime.Composable
    private fun SensorScreen() {
        Box(Modifier.fillMaxSize().background(HoloColors.Void).clickable { switchCamera() }) {
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE") val tick = fps + inferMs + handCount
                val a = frameW.toFloat() / frameH
                val s = minOf(size.width / a, size.height) * 0.9f
                val ox = (size.width - a * s) / 2; val oy = (size.height - s) / 2
                shoulders?.let { sh ->
                    drawLine(HoloColors.CyanDim, Offset(ox + sh[0] * a * s, oy + sh[1] * s), Offset(ox + sh[2] * a * s, oy + sh[3] * s), 3f)
                }
                for (i in 0 until handCount) {
                    val pts = FloatArray(42) { k -> if (k % 2 == 0) ox + skeletons[i][k] * s else oy + skeletons[i][k] * s }
                    handHologram(Offset.Zero, pts, HoloColors.Cyan, 1f)
                }
            }
            Column(Modifier.align(Alignment.TopStart).padding(24.dp)) {
                BasicText("HOLO OS · SENSOR", style = holoText(18.sp, HoloColors.Cyan, spacing = 4.sp))
                BasicText(if (linkUp) "LINKED TO PC OVER USB" else "WAITING FOR PC · START HOLOOS ON THE LAPTOP", style = holoText(12.sp, if (linkUp) HoloColors.Amber else HoloColors.Muted))
                BasicText("${fps.toInt()} FPS · ${inferMs.toInt()} MS (${delegate.name}) · $handCount HANDS", style = holoText(12.sp, HoloColors.Text, glow = 0f))
                BasicText(status.uppercase() + " · TAP TO SWITCH CAMERA", style = holoText(10.sp, HoloColors.Muted, glow = 0f))
            }
            BasicText(
                "SKELETON ONLY · NO CAMERA IMAGE IS SHOWN, STORED OR SENT",
                style = holoText(10.sp, HoloColors.Muted, glow = 0f, spacing = 2.sp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }
}
