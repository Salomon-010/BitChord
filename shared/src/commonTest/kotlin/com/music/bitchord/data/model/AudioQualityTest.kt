package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioQualityTest {
    private fun tier(suffix: String?, depth: Int? = null, rate: Int? = null, kbps: Int? = null) =
        AudioQuality.classify(AudioFormat(suffix, depth, rate, kbps))?.tier

    private fun detail(suffix: String?, depth: Int? = null, rate: Int? = null, kbps: Int? = null) =
        AudioQuality.classify(AudioFormat(suffix, depth, rate, kbps))?.let { AudioQuality.detail(it) }

    @Test
    fun cdQualityLosslessIsLossless() {
        assertEquals(AudioQualityTier.LOSSLESS, tier("flac", 16, 44_100))
        assertEquals(AudioQualityTier.LOSSLESS, tier("flac", 16, 48_000))
        assertEquals(AudioQualityTier.LOSSLESS, tier("wav", 16, 44_100))
        assertEquals(AudioQualityTier.LOSSLESS, tier("alac", 16, 44_100))
    }

    @Test
    fun twentyFourBitsIsHiResAtAnyRate() {
        assertEquals(AudioQualityTier.HI_RES, tier("flac", 24, 44_100))
        assertEquals(AudioQualityTier.HI_RES, tier("flac", 24, 48_000))
        assertEquals(AudioQualityTier.HI_RES, tier("flac", 32, 44_100))
    }

    @Test
    fun aboveFortyEightKilohertzIsHiResEvenAtSixteenBits() {
        assertEquals(AudioQualityTier.HI_RES, tier("flac", 16, 88_200))
        assertEquals(AudioQualityTier.HI_RES, tier("flac", 24, 96_000))
        assertEquals(AudioQualityTier.HI_RES, tier("flac", null, 192_000))
    }

    @Test
    fun exactlyFortyEightKilohertzAtSixteenBitsIsNotHiRes() {
        assertEquals(AudioQualityTier.LOSSLESS, tier("flac", 16, 48_000))
        assertEquals(AudioQualityTier.LOSSLESS, tier("flac", 20, 48_000))
    }

    @Test
    fun losslessWithNothingStatedOrAnUnusualRateIsLossless() {
        assertEquals(AudioQualityTier.LOSSLESS, tier("flac"))
        assertEquals(AudioQualityTier.LOSSLESS, tier("flac", 0, 0))
        assertEquals(AudioQualityTier.LOSSLESS, tier("flac", 16, 32_000))
        assertEquals(AudioQualityTier.LOSSLESS, tier("aiff", 16, 22_050))
        assertEquals(AudioQualityTier.LOSSLESS, tier("ape", 16, 44_100))
        assertEquals(AudioQualityTier.LOSSLESS, tier("wv", 16, 44_100))
    }

    @Test
    fun lossyFormatsAreCompressedWhateverTheyState() {
        assertEquals(AudioQualityTier.COMPRESSED, tier("mp3", null, 44_100, 320))
        assertEquals(AudioQualityTier.COMPRESSED, tier("aac", null, 48_000, 256))
        assertEquals(AudioQualityTier.COMPRESSED, tier("opus", null, 48_000, 160))
        assertEquals(AudioQualityTier.COMPRESSED, tier("ogg", null, 44_100, 192))
        assertEquals(AudioQualityTier.COMPRESSED, tier("wma"))
        // A lossy file at 96 kHz is still compressed.
        assertEquals(AudioQualityTier.COMPRESSED, tier("mp3", 24, 96_000, 320))
    }

    @Test
    fun m4aIsAlacWithABitDepthAndAacWithout() {
        assertEquals("ALAC · 24 bits · 96 kHz", detail("m4a", 24, 96_000, 4_608))
        assertEquals(AudioQualityTier.HI_RES, tier("m4a", 24, 96_000))
        assertEquals("AAC · 256 kbps", detail("m4a", null, 44_100, 256))
        assertEquals(AudioQualityTier.COMPRESSED, tier("m4a", 0, 44_100, 256))
    }

    @Test
    fun dsdIsHiResAndNamedByItsRate() {
        assertEquals(AudioQualityTier.HI_RES, tier("dsf", 1, 2_822_400))
        assertEquals("DSD64", detail("dsf", 1, 2_822_400))
        assertEquals("DSD128", detail("dff", 1, 5_644_800))
        assertEquals("DSD", detail("dsf"))
    }

    @Test
    fun unknownFormatsHaveNoTier() {
        assertNull(tier(null))
        assertNull(tier(""))
        assertNull(tier("mid", 16, 44_100))
        assertNull(tier("webm"))
    }

    @Test
    fun suffixIsReadWhateverItsCase() {
        assertEquals(AudioQualityTier.LOSSLESS, tier("FLAC", 16, 44_100))
        assertEquals(AudioQualityTier.COMPRESSED, tier(" Mp3 ", null, null, 320))
    }

    @Test
    fun detailLines() {
        assertEquals("FLAC · 24 bits · 96 kHz", detail("flac", 24, 96_000, 3_000))
        assertEquals("FLAC · 16 bits · 44.1 kHz", detail("flac", 16, 44_100, 900))
        assertEquals("FLAC · 24 bits · 176.4 kHz", detail("flac", 24, 176_400))
        assertEquals("FLAC", detail("flac"))
        assertEquals("FLAC · 48 kHz", detail("flac", null, 48_000))
        assertEquals("MP3 · 320 kbps", detail("mp3", null, 44_100, 320))
        assertEquals("Opus", detail("opus"))
    }

    @Test
    fun detailUsesTheGivenSeparatorAndBitsWord() {
        val info = AudioQuality.classify(AudioFormat("flac", 16, 44_100))!!
        assertEquals("FLAC · 16-bit · 44,1 kHz", AudioQuality.detail(info, ',') { "$it-bit" })
    }

    @Test
    fun kilohertzRoundsToOneDecimal() {
        assertEquals("44.1", AudioQuality.kilohertz(44_100))
        assertEquals("48", AudioQuality.kilohertz(48_000))
        assertEquals("88.2", AudioQuality.kilohertz(88_200))
        assertEquals("22.1", AudioQuality.kilohertz(22_050))
        assertEquals("44,1", AudioQuality.kilohertz(44_100, ','))
    }
}
