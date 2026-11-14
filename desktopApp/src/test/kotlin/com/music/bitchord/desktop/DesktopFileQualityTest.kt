package com.music.bitchord.desktop

import com.music.bitchord.data.model.AudioFormat
import com.music.bitchord.data.model.AudioQuality
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopFileQualityTest {
    private val cd = AudioQuality.classify(AudioFormat("flac", 16, 44_100))!!
    private val mp3 = AudioQuality.classify(AudioFormat("mp3", null, 44_100, 320))!!

    private fun <T> inLanguage(tag: String, block: () -> T): T {
        val before = DesktopStrings.language.value
        return try {
            DesktopStrings.setLanguage(tag)
            block()
        } finally {
            DesktopStrings.setLanguage(before)
        }
    }

    @Test
    fun `the detail line follows the app's language, not the system's`() {
        assertEquals("FLAC · 16 bits · 44.1 kHz", inLanguage("en") { DesktopFileQuality.detail(cd) })
        assertEquals("FLAC · 16 bits · 44,1 kHz", inLanguage("fr") { DesktopFileQuality.detail(cd) })
        assertEquals("FLAC · 16 Bit · 44,1 kHz", inLanguage("de") { DesktopFileQuality.detail(cd) })
    }

    @Test
    fun `Hi-Res and Lossless stay in English, Compressed is translated`() {
        assertEquals("Lossless", inLanguage("fr") { DesktopFileQuality.label(cd) })
        assertEquals("Compressé", inLanguage("fr") { DesktopFileQuality.label(mp3) })
        assertEquals("Compressed", inLanguage("en") { DesktopFileQuality.label(mp3) })
    }
}
