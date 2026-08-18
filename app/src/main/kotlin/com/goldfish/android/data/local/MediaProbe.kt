package com.goldfish.android.data.local

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Probt eine lokale Videodatei (SAF-URI) auf Dimensionen, Dauer und
 * Codec-Infos. Pendant zum Server-ffprobe — wir nutzen die System-APIs
 * MediaMetadataRetriever (fuer Width/Height/Duration) + MediaExtractor
 * (fuer Codec-Namen pro Stream).
 *
 * Idempotent + non-throwing — bei kaputten/unterstuetzten Files wird ein
 * default `MediaProbeResult` mit Nullwerten zurueckgegeben.
 */
data class MediaProbeResult(
    val width: Int = 0,
    val height: Int = 0,
    val durationSec: Double = 0.0,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val container: String? = null,
    // Veroeffentlichungs-Datum (epoch ms) aus Container-Metadaten —
    // typisch yt-dlp MKV-DATE als YYYYMMDD, mp4 creation_time als ISO, etc.
    val releasedAtMs: Long? = null
)

object MediaProbe {
    private const val TAG = "GF-MediaProbe"

    fun probe(context: Context, uriStr: String): MediaProbeResult {
        val uri = try { Uri.parse(uriStr) } catch (_: Exception) { return MediaProbeResult() }
        var width = 0
        var height = 0
        var durationSec = 0.0
        var videoCodec: String? = null
        var audioCodec: String? = null
        var container: String? = null
        var releasedAtMs: Long? = null

        // 1) MediaMetadataRetriever fuer Width/Height/Duration/Container/Date
        try {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(context, uri)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()?.let { width = it }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()?.let { height = it }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.let { durationSec = it / 1000.0 }
                container = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                releasedAtMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                    ?.let { parseContainerDate(it) }
            }
        } catch (e: Exception) {
            Log.d(TAG, "MediaMetadataRetriever failed for $uri: ${e.message}")
        }

        // 2) MediaExtractor fuer Codec-Namen pro Stream
        try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    when {
                        mime.startsWith("video/") && videoCodec == null ->
                            videoCodec = shortCodecName(mime)
                        mime.startsWith("audio/") && audioCodec == null ->
                            audioCodec = shortCodecName(mime)
                    }
                    // Width/Height aus Track-Format ist oft genauer als MMR
                    if (mime.startsWith("video/")) {
                        if (width == 0) width = fmt.tryInt(MediaFormat.KEY_WIDTH)
                        if (height == 0) height = fmt.tryInt(MediaFormat.KEY_HEIGHT)
                    }
                }
            } finally {
                extractor.release()
            }
        } catch (e: Exception) {
            Log.d(TAG, "MediaExtractor failed for $uri: ${e.message}")
        }

        return MediaProbeResult(
            width = width,
            height = height,
            durationSec = durationSec,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            container = container,
            releasedAtMs = releasedAtMs
        )
    }

    /** Parsed Container-Date-Strings auf epoch-ms. Bekannte Formate:
     *   - yt-dlp MKV-DATE: "YYYYMMDD" (8 Ziffern, ohne Trennzeichen)
     *   - mp4 creation_time / ISO 8601: "YYYY-MM-DDTHH:MM:SS[.SSS][Z]"
     *   - QuickTime: "YYYY-MM-DD HH:MM:SS"
     *  Unbekannte/leere Strings → null. */
    fun parseContainerDate(raw: String): Long? {
        val s = raw.trim()
        if (s.isBlank() || s.length < 4) return null
        // yt-dlp: 8 Ziffern YYYYMMDD
        if (s.length == 8 && s.all { it.isDigit() }) {
            return try {
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                    set(s.substring(0,4).toInt(), s.substring(4,6).toInt() - 1,
                        s.substring(6,8).toInt(), 0, 0, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                cal.timeInMillis
            } catch (_: Exception) { null }
        }
        // ISO + Space-Varianten — beste Effort ueber mehrere Formate
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (p in patterns) {
            try {
                val sdf = java.text.SimpleDateFormat(p, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                return sdf.parse(s)?.time ?: continue
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    /** Mappt MIME-Typen aus MediaExtractor auf die kurzen Codec-Namen, die
     *  der Server-Side ffprobe liefert (h264, hevc, aac, ac3, dts, etc.). */
    private fun shortCodecName(mime: String): String = when (mime) {
        "video/avc"        -> "h264"
        "video/hevc"       -> "hevc"
        "video/x-vnd.on2.vp8" -> "vp8"
        "video/x-vnd.on2.vp9" -> "vp9"
        "video/av01"       -> "av1"
        "video/mp4v-es"    -> "mpeg4"
        "video/mpeg2"      -> "mpeg2video"
        "video/3gpp"       -> "h263"
        "audio/mp4a-latm"  -> "aac"
        "audio/ac3"        -> "ac3"
        "audio/eac3"       -> "eac3"
        "audio/vnd.dts"    -> "dts"
        "audio/opus"       -> "opus"
        "audio/vorbis"     -> "vorbis"
        "audio/flac"       -> "flac"
        "audio/mpeg"       -> "mp3"
        else -> mime.substringAfter('/')
    }

    private fun MediaFormat.tryInt(key: String): Int =
        try { if (containsKey(key)) getInteger(key) else 0 } catch (_: Exception) { 0 }

    /** Extrahiert einen Frame zum Zeitpunkt `atSec` aus dem Video und speichert
     *  ihn als JPEG (Quali 85). Output-Pfad ist deterministisch aus der URI
     *  abgeleitet — `<filesDir>/local-thumbs/<sha1(uri)>.jpg` — damit
     *  Re-Scans dieselbe Datei wiederverwenden.
     *
     *  WICHTIG: Es war frueher `cacheDir`, was unter Speicherdruck vom
     *  Android-System aufgeraeumt wurde — Folge: Thumbnails verschwanden
     *  unvermittelt. `filesDir` ist persistent bis App-Deinstall oder
     *  expliziter "Daten loeschen"-Aktion.
     *
     *  Returns absoluten Pfad bei Erfolg, null sonst. */
    fun extractFrame(context: Context, uriStr: String, atSec: Long): String? {
        val uri = try { Uri.parse(uriStr) } catch (_: Exception) { return null }
        val outDir = File(context.filesDir, "local-thumbs").apply { if (!exists()) mkdirs() }
        val outFile = File(outDir, sha1(uriStr) + ".jpg")
        if (outFile.exists() && outFile.length() > 0) return outFile.absolutePath

        // Erstversuch: System-MediaMetadataRetriever. Schnell, hardware-
        // beschleunigt, scheitert aber bei Codecs/Containern die der
        // System-Extractor nicht kennt (genau die Files die auch im Player
        // "Container/Format nicht unterstuetzt"-Fehler werfen).
        val sysBmp: Bitmap? = try {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, uri)
                mmr.getFrameAtTime(atSec * 1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally { mmr.release() }
        } catch (e: Exception) {
            Log.d(TAG, "extractFrame system path failed for $uri: ${e.message} — trying FFmpeg fallback")
            null
        }

        if (sysBmp != null) {
            return writeBitmapToFile(sysBmp, outFile)
        }

        // Fallback: nextlib-MediaThumbnailRetriever (FFmpeg-basiert) —
        // findet Frames auch in Files die der System-MediaCodec
        // ablehnt. Etwas langsamer + nutzt CPU statt HW-Decoder.
        return try {
            val r = io.github.anilbeesetti.nextlib.mediainfo.MediaThumbnailRetriever()
            try {
                r.setDataSource(context, uri)
                val bmp = r.getFrameAtTime(atSec * 1_000_000L) ?: return null
                writeBitmapToFile(bmp, outFile)
            } finally { r.release() }
        } catch (e: Exception) {
            Log.w(TAG, "extractFrame FFmpeg fallback failed for $uri: ${e.message}")
            null
        }
    }

    private fun writeBitmapToFile(bmp: Bitmap, outFile: File): String? {
        return try {
            outFile.outputStream().use { os -> bmp.compress(Bitmap.CompressFormat.JPEG, 85, os) }
            bmp.recycle()
            if (outFile.length() > 0) outFile.absolutePath else null
        } catch (e: Exception) {
            Log.w(TAG, "writeBitmapToFile failed: ${e.message}")
            try { bmp.recycle() } catch (_: Exception) {}
            null
        }
    }

    private fun sha1(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
