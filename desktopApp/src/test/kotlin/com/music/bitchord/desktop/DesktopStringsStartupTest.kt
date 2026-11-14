package com.music.bitchord.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Sigui: the catalogue chosen at start is really loaded at start.
 *
 * Every other test shares one JVM in which [DesktopStrings] was set up long before, and switches
 * language with `setLanguage` afterwards, so none of them could see a catalogue that fails while
 * the object is being built (a property declared below `init`, still null then). This one starts a
 * JVM of its own in French, with preferences in an empty folder of its own (never the listener's),
 * and reads a string straight away.
 */
class DesktopStringsStartupTest {
    @Test
    fun `a French system starts in French`() {
        assertEquals("Terminé", startIn("fr", "CA"))
    }

    @Test
    fun `an English system starts in English`() {
        assertEquals("Done", startIn("en", "US"))
    }

    private fun startIn(language: String, country: String): String {
        val data = Files.createTempDirectory("sigui-strings").toFile()
        try {
            val java = File(System.getProperty("java.home"), "bin/java").path
            val process = ProcessBuilder(
                java,
                "-Duser.language=$language",
                "-Duser.country=$country",
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-D${ForkPortable.DATA_PROPERTY}=${data.absolutePath}",
                "-Djava.util.prefs.PreferencesFactory=${ForkFilePreferencesFactory::class.java.name}",
                "-cp", System.getProperty("java.class.path"),
                DesktopStringsStartupProbe::class.java.name,
            ).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            check(process.waitFor(60, TimeUnit.SECONDS)) { "the probe JVM did not finish" }
            return out.lineSequence().firstOrNull { it.startsWith("done=") }?.removePrefix("done=")
                ?: error("the probe printed no answer:\n$out")
        } finally {
            data.deleteRecursively()
        }
    }
}

/** Run by [DesktopStringsStartupTest] in a JVM of its own: the very first string it is asked for. */
object DesktopStringsStartupProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        println("done=" + DesktopStrings["done", "Done"])
    }
}
