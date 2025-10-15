package com.valoser.futaburakari.videoeditor.utils

import android.media.MediaExtractor
import android.media.MediaFormat

/**
 * Finds the first track in the extractor that has a MIME type starting with the given prefix.
 * @param prefix The MIME type prefix (e.g., "video/", "audio/").
 * @return The track index, or null if no such track is found.
 */
fun MediaExtractor.findTrack(prefix: String): Int? {
    for (i in 0 until trackCount) {
        val format = getTrackFormat(i)
        if (format.getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) {
            return i
        }
    }
    return null
}
