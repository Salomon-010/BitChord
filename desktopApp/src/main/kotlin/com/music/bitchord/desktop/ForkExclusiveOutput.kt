package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork: one exclusive-mode format: the rate, the bits each sample takes in the buffer
 * ([containerBits]) and how many of them carry audio ([validBits]). "24-in-32" is 32 / 24.
 */
internal data class ForkExclusiveFormat(val rate: Int, val containerBits: Int, val validBits: Int) {
    val bytesPerSample: Int get() = containerBits / 8

    /** "96 kHz · 24-in-32", "96 kHz · 24-bit packed", for the log and the pipeline panel. */
    val label: String
        get() {
            val khz = if (rate % 1000 == 0) "${rate / 1000}" else "%.1f".format(java.util.Locale.ROOT, rate / 1000.0)
            val depth = when {
                containerBits == 32 && validBits == 24 -> "24-in-32"
                containerBits == 32 -> "32-bit"
                containerBits == 24 -> "24-bit packed"
                else -> "$validBits-bit"
            }
            return "$khz kHz · $depth"
        }
}

/**
 * Fork: which exclusive format to ask for, decided without a device so it can be tested.
 *
 * The rate, for a source at [sourceRate]: its own, when the device takes it in any container; else
 * the highest whole multiple of it the device takes (44.1 → 88.2 / 176.4, 48 → 96 / 192); else the
 * highest rate the device takes, the app's own converter ([DesktopRateConverter]) going up to it.
 * The container at that rate, in this order: 24-in-32 (most USB Audio Class 2 devices), 32-bit
 * (devices that only state 32), 24-bit packed (USB Audio Class 1 devices), 16-bit. The other rates
 * follow as fall-backs, best first, for a driver that accepts a format at the probe and then refuses
 * it at Initialize.
 */
internal object ForkExclusiveChoice {

    val RATES = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000)

    /** Container / valid bits, best first. */
    val CONTAINERS = listOf(32 to 24, 32 to 32, 24 to 24, 16 to 16)

    /** Every format the device is asked about. */
    fun candidates(): List<ForkExclusiveFormat> =
        RATES.flatMap { rate -> CONTAINERS.map { (container, valid) -> ForkExclusiveFormat(rate, container, valid) } }

    /** The rate the output runs at for [sourceRate], or null when the device takes nothing. */
    fun rateFor(sourceRate: Int, accepted: List<ForkExclusiveFormat>): Int? {
        val rates = accepted.map { it.rate }.toSet()
        if (rates.isEmpty()) return null
        if (sourceRate in rates) return sourceRate
        if (sourceRate > 0) rates.filter { it > sourceRate && it % sourceRate == 0 }.maxOrNull()?.let { return it }
        return rates.max()
    }

    /** What to try opening, in order: the chosen rate's containers first, then every other rate's. */
    fun order(sourceRate: Int, accepted: List<ForkExclusiveFormat>): List<ForkExclusiveFormat> {
        val rate = rateFor(sourceRate, accepted) ?: return emptyList()
        val rank = CONTAINERS.withIndex().associate { (index, pair) -> pair to index }
        val byContainer = compareBy<ForkExclusiveFormat> { rank[it.containerBits to it.validBits] ?: Int.MAX_VALUE }
        val first = accepted.filter { it.rate == rate }.sortedWith(byContainer)
        val rest = accepted.filter { it.rate != rate }
            .sortedWith(compareByDescending<ForkExclusiveFormat> { it.rate }.then(byContainer))
        return first + rest
    }
}

/**
 * Fork: float samples to the bytes of one exclusive format, little-endian: dithered to 24 bits
 * ([ForkPcmQuantizer]; to 16 for a 16-bit device) and left-justified in the container, so a
 * 32-bit container carries the 24 bits in its top three bytes and zero below. A 24-bit (or 16-bit)
 * file at unity gain with nothing processing it comes out bit for bit.
 */
internal class ForkExclusivePacker(val format: ForkExclusiveFormat, seed: Long = 0x2545F4914F6CDD1DL) {

    private val quantizer = ForkPcmQuantizer(minOf(format.validBits, 24), seed)
    private val shift = format.containerBits - quantizer.bits

    /** [count] samples × [gain] into [out], [ForkExclusiveFormat.bytesPerSample] bytes each. */
    fun pack(samples: FloatArray, count: Int, gain: Float, out: ByteArray) {
        val width = format.bytesPerSample
        for (index in 0 until count) {
            val value = quantizer.quantize(samples[index], gain) shl shift
            val at = index * width
            out[at] = value.toByte()
            out[at + 1] = (value shr 8).toByte()
            if (width >= 3) out[at + 2] = (value shr 16).toByte()
            if (width == 4) out[at + 3] = (value shr 24).toByte()
        }
    }
}

/** Fork: what [ForkExclusiveOutput] needs of the device; [ForkWasapiExclusive] natively, a fake in tests. */
internal interface ForkExclusiveBackend {
    val available: Boolean
    /** Per candidate, whether the device takes it exclusive; null when the device can't be asked. */
    fun probe(device: String, channels: Int, candidates: List<ForkExclusiveFormat>): BooleanArray?
    /** The device buffer in bytes, or 0 when refused. */
    fun open(device: String, channels: Int, format: ForkExclusiveFormat): Int
    /** Frames taken, or -1 when the device went away or failed. */
    fun write(bytes: ByteArray, frames: Int): Int
    fun framesPlayed(): Long
    fun pause(paused: Boolean)
    fun flush()
    fun drain()
    fun close()
    fun deviceName(): String?
    fun lastError(): String
}

/** Fork: the native side (fork_exclusive_audio.h in bitchord_window.dll, which [DesktopWindowsAudio] loads). */
internal object ForkWasapiExclusive : ForkExclusiveBackend {
    override val available: Boolean get() = DesktopWindowsAudio.forkAvailable

    override fun probe(device: String, channels: Int, candidates: List<ForkExclusiveFormat>): BooleanArray? =
        if (!available) null else nativeProbe(
            device, channels,
            candidates.map { it.rate }.toIntArray(),
            candidates.map { it.containerBits }.toIntArray(),
            candidates.map { it.validBits }.toIntArray(),
        )

    override fun open(device: String, channels: Int, format: ForkExclusiveFormat): Int =
        if (!available) 0 else nativeOpen(device, format.rate, channels, format.containerBits, format.validBits)

    override fun write(bytes: ByteArray, frames: Int): Int = if (available) nativeWrite(bytes, frames) else -1
    override fun framesPlayed(): Long = if (available) nativeFramesPlayed() else 0L
    override fun pause(paused: Boolean) { if (available) nativePause(paused) }
    override fun flush() { if (available) nativeFlush() }
    override fun drain() { if (available) nativeDrain() }
    override fun close() { if (available) nativeClose() }
    override fun deviceName(): String? = if (available) nativeDeviceName().takeIf(String::isNotBlank) else null
    override fun lastError(): String = if (available) nativeLastError() else "native Windows audio is unavailable"

    @JvmStatic private external fun nativeProbe(device: String, channels: Int, rates: IntArray, containers: IntArray, valids: IntArray): BooleanArray?
    @JvmStatic private external fun nativeOpen(device: String, rate: Int, channels: Int, containerBits: Int, validBits: Int): Int
    @JvmStatic private external fun nativeWrite(bytes: ByteArray, frames: Int): Int
    @JvmStatic private external fun nativeFramesPlayed(): Long
    @JvmStatic private external fun nativePause(paused: Boolean)
    @JvmStatic private external fun nativeFlush()
    @JvmStatic private external fun nativeDrain()
    @JvmStatic private external fun nativeClose()
    @JvmStatic private external fun nativeDeviceName(): String
    @JvmStatic private external fun nativeLastError(): String
}

/**
 * Fork: the exclusive line [DesktopAudioSink] plays through while "Exclusive mode" is on. It asks the
 * device what it takes (once per device and channel count; [forget] clears that when the device
 * list changes), opens the best format for the source's rate, and packs what the engine writes.
 * The engine runs at [ForkExclusiveFormat.rate]: its converter brings every track to it. Any refusal
 * is a null from [open] or a -1 from [write], and the sink goes back to shared mode.
 */
internal class ForkExclusiveOutput(private val backend: ForkExclusiveBackend) {

    private val probes = ConcurrentHashMap<String, List<ForkExclusiveFormat>>()
    private var packer: ForkExclusivePacker? = null
    private var staging = ByteArray(0)
    private var channels = 0

    /** The open format, null while closed. */
    var format: ForkExclusiveFormat? = null
        private set

    var bufferBytes = 0
        private set

    val available: Boolean get() = backend.available

    /**
     * What the device takes for [channels] channels, asked once and kept; empty when it can't be asked,
     * which is not kept (the device may be back next time).
     */
    fun accepted(device: String, channels: Int): List<ForkExclusiveFormat> {
        val key = "$device|$channels"
        probes[key]?.let { return it }
        val candidates = ForkExclusiveChoice.candidates()
        val answers = backend.probe(device, channels, candidates)
        val accepted = if (answers == null) emptyList() else candidates.filterIndexed { index, _ -> answers.getOrElse(index) { false } }
        val takes = when {
            answers == null -> "nothing that could be asked (${backend.lastError()})"
            accepted.isEmpty() -> "no format"
            else -> accepted.joinToString { it.label }
        }
        DesktopTrackLog.log("exclusive mode: ${device.ifBlank { "system default" }} takes $takes")
        if (answers != null) probes[key] = accepted
        return accepted
    }

    /** The rate the output would run at for a source at [sourceRate]; null when exclusive mode can't play it. */
    fun rateFor(device: String, sourceRate: Int, channels: Int): Int? =
        ForkExclusiveChoice.rateFor(sourceRate, accepted(device, channels))

    /** Opens the best format for [sourceRate]; null, with the reason logged, when the device refuses every one. */
    fun open(device: String, sourceRate: Int, channels: Int): ForkExclusiveFormat? {
        close()
        val order = ForkExclusiveChoice.order(sourceRate, accepted(device, channels))
        for (candidate in order) {
            val bytes = backend.open(device, channels, candidate)
            if (bytes > 0) {
                format = candidate
                bufferBytes = bytes
                this.channels = channels
                packer = ForkExclusivePacker(candidate)
                return candidate
            }
            DesktopTrackLog.log("exclusive mode: ${candidate.label} refused at open: ${backend.lastError()}")
        }
        return null
    }

    /** Packs and writes [count] samples; the frames taken, or -1 when the line failed. */
    fun write(samples: FloatArray, count: Int, gain: Float): Int {
        val current = packer ?: return -1
        val frameCount = count / channels.coerceAtLeast(1)
        if (frameCount <= 0) return 0
        val used = frameCount * channels
        val bytes = used * current.format.bytesPerSample
        if (staging.size < bytes) staging = ByteArray(bytes)
        current.pack(samples, used, gain, staging)
        return backend.write(staging, frameCount)
    }

    fun framesPlayed(): Long = backend.framesPlayed()
    fun pause() = backend.pause(true)
    fun resume() = backend.pause(false)
    fun flush() = backend.flush()
    fun drain() = backend.drain()
    fun deviceName(): String? = backend.deviceName()

    fun close() {
        if (format != null) backend.close()
        format = null
        packer = null
        bufferBytes = 0
        channels = 0
    }

    /** Forgets what devices took: a device was plugged or unplugged. */
    fun forget() = probes.clear()
}

/** Fork: the "Exclusive mode" setting; off by default. The engine reopens its output when it changes. */
internal object ForkExclusiveSetting {

    const val KEY = "fork_wasapi_exclusive"

    private val persistence by lazy { DesktopPersistence() }
    private val _enabled = MutableStateFlow(persistence.boolean(KEY, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Set by the engine: reopen the output in the new mode. */
    @Volatile var onChanged: (() -> Unit)? = null

    /** Bumped on every change, so a line that failed is tried again once the setting is touched. */
    @Volatile var changes = 0
        private set

    fun set(on: Boolean) {
        if (_enabled.value == on) return
        _enabled.value = on
        changes++
        persistence.saveBoolean(KEY, on)
        onChanged?.invoke()
    }
}

/** Fork: Settings → Output precision's "Exclusive mode (WASAPI)" row; Windows only. */
@Composable
internal fun ForkExclusiveRow() {
    if (!DesktopPlatform.isWindows) return
    val on by ForkExclusiveSetting.enabled.collectAsState()
    SettingsToggle(
        DesktopStrings["fork_exclusive_mode", "Exclusive mode (WASAPI)"],
        DesktopStrings[
            "fork_exclusive_mode_subtitle",
            "Plays straight to the device, without Windows' mixer: the best rate it takes, 24-bit with dither. Other apps can't play on it meanwhile; shared mode is used whenever the device refuses",
        ],
        on,
        ForkExclusiveSetting::set,
    )
}
