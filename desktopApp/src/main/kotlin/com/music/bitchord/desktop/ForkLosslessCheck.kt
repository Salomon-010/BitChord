package com.music.bitchord.desktop

import com.music.bitchord.audio.authenticity.AuthenticityKind
import com.music.bitchord.audio.authenticity.AuthenticityVerdict
import com.music.bitchord.audio.authenticity.LosslessAuthenticity
import com.music.bitchord.audio.authenticity.LossySource
import com.music.bitchord.audio.authenticity.PcmExcerpt
import com.music.bitchord.data.navidrome.NavidromeRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Fork: is a server song's lossless file really lossless ([LosslessAuthenticity])? The PC's side:
 * three [EXCERPT_SECONDS] excerpts of the original file (`streamUrl`, `format=raw`) decoded by
 * [DesktopAudioDecoder] as S32 at the file's own rate and channel count, so no downmix, resample
 * or float conversion touches the low bits; the verdict kept per song id and file size.
 *
 * Blocking (network and decode): call it off the UI thread. No UI uses it yet.
 */
internal object ForkLosslessCheck {

    const val EXCERPT_SECONDS = 3.0

    /** Where in the song the excerpts start, as fractions of its length: past the intro, before the fade. */
    val POSITIONS = listOf(0.20, 0.50, 0.75)

    /** A verdict and what it cost; [decodeMs] includes the network, [analyseMs] is the maths alone. */
    data class Outcome(val verdict: AuthenticityVerdict, val cached: Boolean, val decodeMs: Long, val analyseMs: Long)

    /** The excerpts of one file, with what the decoder said it is. */
    class Excerpts(val codec: String?, val excerpts: List<PcmExcerpt>)

    val cache: ForkLosslessCache by lazy {
        ForkLosslessCache(DesktopMediaCache.directory.resolve("lossless_check").resolve("verdicts.json"))
    }

    /**
     * The server song [id]'s verdict: from [store] when it holds one for this [sizeBytes] (the
     * file replaced on the server is measured again), else measured now and kept. Null when the
     * file is not lossless or could not be read. With no [sizeBytes], measured and not kept.
     */
    fun check(id: String, sizeBytes: Long?, store: ForkLosslessCache = cache): Outcome? {
        if (sizeBytes != null) store.get(id, sizeBytes)?.let { return Outcome(it, cached = true, decodeMs = 0, analyseMs = 0) }
        val url = NavidromeRepository.withLogin(NavidromeRepository.client().streamUrl(id))
        val started = System.nanoTime()
        val read = readExcerpts(url) ?: return null
        val decoded = System.nanoTime()
        val verdict = LosslessAuthenticity.analyse(read.excerpts)
        val done = System.nanoTime()
        if (sizeBytes != null) store.put(id, sizeBytes, verdict)
        return Outcome(verdict, cached = false, decodeMs = (decoded - started) / 1_000_000, analyseMs = (done - decoded) / 1_000_000)
    }

    /**
     * [EXCERPT_SECONDS] at each of [POSITIONS] of [url], or null when it is not a lossless file,
     * cannot be opened or seeked, or is too short for even one excerpt.
     */
    fun readExcerpts(url: String, headers: Map<String, String> = emptyMap(), windowed: Boolean = false): Excerpts? {
        var decoder = open(url, headers, windowed, channels = 2) ?: return null
        try {
            // The file's own channel count, so swresample has nothing to mix: reopened when it isn't stereo.
            val sourceChannels = decoder.measuredFormat?.channels ?: 2
            if (sourceChannels != 2) {
                decoder.close()
                decoder = open(url, headers, windowed, channels = sourceChannels) ?: return null
            }
            val format = decoder.measuredFormat ?: return null
            if (!format.isLossless) return null
            // Float PCM has no integer low bits to read; S32 out of it would be a conversion.
            if (format.codec.orEmpty().startsWith("pcm_f")) return null
            val bits = format.bitDepth?.takeIf { it in 8..32 } ?: return null
            val rate = decoder.outputFormat.sampleRate
            val channels = decoder.outputFormat.channels
            val wanted = (EXCERPT_SECONDS * rate).toInt()
            val durationUs = decoder.durationUs ?: return null
            val lastStartUs = durationUs - (EXCERPT_SECONDS * 1_000_000).toLong()
            if (lastStartUs < 0) return null

            val excerpts = POSITIONS.map { position ->
                val startUs = (durationUs * position).toLong().coerceAtMost(lastStartUs)
                if (!decoder.seek(startUs)) return null
                val samples = readFrames(decoder, wanted, channels, bits) ?: return null
                PcmExcerpt(samples, channels, rate, bits, startUs / 1_000_000.0)
            }
            return Excerpts(format.codec, excerpts)
        } finally {
            decoder.close()
        }
    }

    private fun open(url: String, headers: Map<String, String>, windowed: Boolean, channels: Int): DesktopAudioDecoder? {
        val decoder = DesktopAudioDecoder()
        val opened = decoder.open(
            url = url,
            headers = headers,
            // The rate is the file's whatever is asked (the decoder keeps the source's); S32, not float.
            requested = DesktopPcmFormat(sampleRate = 44_100, channels = channels, bytesPerSample = 4, isFloat = false),
            windowed = windowed,
        )
        if (opened.isFailure) {
            DesktopTrackLog.log("lossless check: could not open the file: ${opened.exceptionOrNull()?.message}")
            decoder.close()
            return null
        }
        return decoder
    }

    /**
     * [frames] interleaved frames from where [decoder] stands, as right-aligned [bits]-bit
     * integers: S32 holds a 16-bit sample shifted left 16 and a 24-bit one shifted left 8, so the
     * arithmetic shift back is exact. Null when the file ends first.
     */
    private fun readFrames(decoder: DesktopAudioDecoder, frames: Int, channels: Int, bits: Int): IntArray? {
        val out = IntArray(frames * channels)
        val shift = 32 - bits
        var filled = 0
        while (filled < out.size) {
            val block = decoder.read() ?: return null
            var at = 0
            while (at + 4 <= block.size && filled < out.size) {
                val s32 = (block[at].toInt() and 0xFF) or
                    ((block[at + 1].toInt() and 0xFF) shl 8) or
                    ((block[at + 2].toInt() and 0xFF) shl 16) or
                    (block[at + 3].toInt() shl 24)
                out[filled++] = s32 shr shift
                at += 4
            }
        }
        return out
    }
}

/**
 * Fork: [ForkLosslessCheck]'s verdicts on disk, one JSON file, keyed by song id. A verdict counts
 * only for the file size it was measured at and the [LosslessAuthenticity.VERSION] that measured
 * it. In a folder of its own under the media cache, so the cache's size trim (top-level files
 * only) leaves it alone.
 */
internal class ForkLosslessCache(private val file: Path) {

    @Serializable
    data class Record(
        val sizeBytes: Long,
        val version: Int,
        val kind: String,
        val confidence: Double,
        val cutoffHz: Double? = null,
        val lossySource: String? = null,
        val estimatedKbps: Int? = null,
        val declaredBits: Int,
        val effectiveBits: Int? = null,
        val sampleRate: Int,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Record.serializer())
    private var records: MutableMap<String, Record>? = null

    @Synchronized
    fun get(id: String, sizeBytes: Long): AuthenticityVerdict? {
        val record = loaded()[id] ?: return null
        if (record.sizeBytes != sizeBytes || record.version != LosslessAuthenticity.VERSION) return null
        return runCatching {
            AuthenticityVerdict(
                kind = AuthenticityKind.valueOf(record.kind),
                confidence = record.confidence,
                cutoffHz = record.cutoffHz,
                lossySource = record.lossySource?.let(LossySource::valueOf),
                estimatedKbps = record.estimatedKbps,
                declaredBits = record.declaredBits,
                effectiveBits = record.effectiveBits,
                sampleRate = record.sampleRate,
                excerpts = emptyList(),
            )
        }.getOrNull()
    }

    @Synchronized
    fun put(id: String, sizeBytes: Long, verdict: AuthenticityVerdict) {
        val all = loaded()
        all[id] = Record(
            sizeBytes = sizeBytes,
            version = LosslessAuthenticity.VERSION,
            kind = verdict.kind.name,
            confidence = verdict.confidence,
            cutoffHz = verdict.cutoffHz,
            lossySource = verdict.lossySource?.name,
            estimatedKbps = verdict.estimatedKbps,
            declaredBits = verdict.declaredBits,
            effectiveBits = verdict.effectiveBits,
            sampleRate = verdict.sampleRate,
        )
        runCatching {
            Files.createDirectories(file.parent)
            val temporary = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(temporary, json.encodeToString(serializer, all))
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { DesktopTrackLog.log("lossless check: could not save the verdicts: ${it.message}") }
    }

    private fun loaded(): MutableMap<String, Record> = records ?: runCatching {
        if (Files.isRegularFile(file)) json.decodeFromString(serializer, Files.readString(file)).toMutableMap() else null
    }.getOrNull().let { it ?: mutableMapOf() }.also { records = it }
}
