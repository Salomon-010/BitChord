package com.music.bitchord.desktop

/**
 * "1 song" / "12 songs", the phone's `song_count_plural` (quantity one / other). The PC wrote
 * "$n songs" everywhere, so a one-song artist or playlist read "1 songs".
 */
internal fun forkSongCount(count: Int): String = if (count == 1) DesktopStrings["d_song_count_one", "1 song"] else DesktopStrings.format("d_song_count_other", count, fallback = "%1\$d songs")
