package com.zain.holoos.tracking

import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val PORT = 47474
/** Phone sensor port, forwarded over USB with `adb reverse tcp:47475 tcp:47475`. */
private const val PHONE_PORT = 47475

private class UdpTrackerLink : TrackerLink {
    @Volatile override var latest: TrackFrame? = null
    @Volatile override var status: TrackerStatus = TrackerStatus.Starting
    @Volatile override var source: String = "WEBCAM"
    private var process: Process? = null
    private val socket = DatagramSocket(PORT, InetAddress.getLoopbackAddress()).apply { soTimeout = 1500 }
    private val phoneServer = java.net.ServerSocket(PHONE_PORT, 1, InetAddress.getLoopbackAddress())
    @Volatile private var lastPhoneMs = 0L

    init {
        process = launchTracker()
        if (process == null && System.getenv("HOLOOS_TRACKER") != "external") status = TrackerStatus.NotInstalled
        Thread(::receive, "holo-tracker-rx").apply { isDaemon = true }.start()
        Thread(::phoneLoop, "holo-phone-rx").apply { isDaemon = true }.start()
        Thread(::adbReverseLoop, "holo-adb").apply { isDaemon = true }.start()
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    private val phoneActive get() = System.currentTimeMillis() - lastPhoneMs < 1500

    /** Accepts the phone's TCP stream: [u16 length][packet] repeated. */
    private fun phoneLoop() {
        while (!phoneServer.isClosed) {
            try {
                phoneServer.accept().use { s ->
                    s.tcpNoDelay = true
                    val inp = java.io.DataInputStream(s.getInputStream().buffered())
                    val buf = ByteArray(4096)
                    while (true) {
                        val len = inp.readUnsignedShort()
                        inp.readFully(buf, 0, len)
                        val f = parse(ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)) ?: continue
                        lastPhoneMs = System.currentTimeMillis()
                        if (source != "PHONE") { source = "PHONE"; stopWebcamTracker() }
                        latest = f; status = TrackerStatus.Running
                    }
                }
            } catch (_: Exception) {
                if (phoneServer.isClosed) return
            }
            source = "WEBCAM"
        }
    }

    /** Re-applies the USB port forward every few seconds, so plugging the phone in later just works. */
    private fun adbReverseLoop() {
        val adb = findAdb() ?: return
        while (!phoneServer.isClosed) {
            if (!phoneActive) runCatching {
                ProcessBuilder(adb.path, "reverse", "tcp:$PHONE_PORT", "tcp:$PHONE_PORT")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
            }
            Thread.sleep(4000)
        }
    }

    private fun findAdb(): File? {
        val roots = listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"), "D:\\AndroidSDK") +
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .map { File(it, "local.properties") }.filter { it.exists() }
                .mapNotNull { f -> f.readLines().firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")?.replace("\\:", ":")?.replace("\\\\", "\\") }
                .toList()
        return roots.map { File(it, "platform-tools/adb.exe") }.firstOrNull { it.exists() }
    }

    private fun stopWebcamTracker() {
        process?.let { it.descendants().forEach { d -> d.destroy() }; it.destroy() }
        process = null
    }

    private fun receive() {
        val buf = ByteArray(4096)
        val packet = DatagramPacket(buf, buf.size)
        while (!socket.isClosed) {
            try {
                socket.receive(packet)
                if (phoneActive) continue
                parse(ByteBuffer.wrap(buf, 0, packet.length).order(ByteOrder.LITTLE_ENDIAN))?.let {
                    latest = it; status = TrackerStatus.Running
                }
            } catch (_: java.net.SocketTimeoutException) {
                if (phoneActive) continue
                val p = process
                status = when {
                    p != null && !p.isAlive -> TrackerStatus.Failed
                    status == TrackerStatus.Running -> TrackerStatus.NoSignal
                    else -> status
                }
            } catch (_: Exception) {
                if (socket.isClosed) return
            }
        }
    }

    private fun parse(b: ByteBuffer): TrackFrame? {
        if (b.remaining() < 30 || b.get() != 'H'.code.toByte() || b.get() != 'O'.code.toByte() || b.get() != 'L'.code.toByte() || b.get() != 'O'.code.toByte()) return null
        if (b.get().toInt() != 1) return null
        val capture = b.double; val id = b.int; val fps = b.float
        val w = b.short.toInt() and 0xFFFF; val h = b.short.toInt() and 0xFFFF
        val poseValid = b.get().toInt() == 1
        val sh = FloatArray(4) { b.float }
        val n = b.get().toInt() and 0xFF
        val hands = ArrayList<TrackedHand>(n)
        repeat(n) {
            val right = b.get().toInt() == 1; val score = b.float
            hands += TrackedHand(right, score, FloatArray(63) { b.float })
        }
        return TrackFrame(id, capture, System.currentTimeMillis(), fps, w, h, if (poseValid) sh else null, hands)
    }

    /** Finds tools/tracker by walking up from the working directory. */
    private fun launchTracker(): Process? {
        if (System.getenv("HOLOOS_TRACKER") == "off" || System.getenv("HOLOOS_TRACKER") == "external") return null
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val script = File(dir, "tools/tracker/tracker.py")
            val python = File(dir, "tools/tracker/.venv/Scripts/python.exe")
            val model = File(dir, "tools/tracker/models/hand_landmarker.task")
            if (script.exists()) {
                if (!python.exists() || !model.exists()) return null
                val log = File(System.getProperty("java.io.tmpdir"), "holoos-tracker.log")
                return runCatching {
                    ProcessBuilder(python.path, script.path, "--port", PORT.toString())
                        .redirectErrorStream(true).redirectOutput(log).start()
                }.getOrNull()
            }
            dir = dir.parentFile
        }
        return null
    }

    override fun stop() {
        stopWebcamTracker()
        socket.close()
        runCatching { phoneServer.close() }
    }
}

actual fun startTracker(): TrackerLink? = runCatching { UdpTrackerLink() }.onFailure { it.printStackTrace() }.getOrNull()

private val settingsDir = File(System.getProperty("user.home"), ".holoos").apply { mkdirs() }
actual fun loadSetting(key: String): String? = File(settingsDir, "$key.txt").takeIf { it.exists() }?.readText()
actual fun saveSetting(key: String, value: String) { File(settingsDir, "$key.txt").writeText(value) }
