package com.goldfish.android.ui.player

import com.goldfish.android.data.model.MediaStream
import com.goldfish.android.data.model.PlaybackInfo

data class SubtitleOption(
    val key: String,                  // "off" | "ext:<idx>" | "embed:<idx>"
    val label: String,
    val externalUri: String? = null,  // null bei eingebettetem Track (PGS bei Direct Play)
    val language: String? = null      // ISO 639-2/-3 (eng, ger, ita, …) oder zweistelliger Whisper-Code (de, en, it)
)

private val TEXT_BASED_CODECS = setOf(
    "subrip", "srt", "mov_text", "tx3g", "webvtt", "vtt", "ass", "ssa", "stl", "ttml",
    "webvtt-generated"
)

private val IMAGE_BASED_CODECS = setOf(
    "hdmv_pgs_subtitle", "pgssub", "pgs",
    "dvb_subtitle", "dvbsub", "dvd_subtitle", "dvdsub",
    "xsub"
)

private fun langLabel(lang: String?): String = when ((lang ?: "").lowercase()) {
    "ger", "deu", "de" -> "Deutsch"
    "eng", "en" -> "Englisch"
    "ita", "it" -> "Italienisch"
    "fre", "fra", "fr" -> "Französisch"
    "spa", "es" -> "Spanisch"
    "jpn", "ja" -> "Japanisch"
    "kor", "ko" -> "Koreanisch"
    "rus", "ru" -> "Russisch"
    "tur", "tr" -> "Türkisch"
    "pol", "pl" -> "Polnisch"
    "ned", "nld", "nl" -> "Niederländisch"
    "" -> "Unbekannt"
    else -> lang ?: "?"
}

fun buildSubtitleOptions(
    info: PlaybackInfo,
    baseUrl: String,
    itemId: Int
): List<SubtitleOption> {
    val out = mutableListOf(SubtitleOption("off", "Aus", null, null))
    val base = baseUrl.trimEnd('/')
    val streams = info.streams?.filter { it.type == "subtitle" }.orEmpty()
    streams.forEach { s ->
        out += subtitleOptionFor(s, info.mode, base, itemId) ?: return@forEach
    }
    return out
}

private fun subtitleOptionFor(
    s: MediaStream,
    mode: String,
    baseUrl: String,
    itemId: Int
): SubtitleOption? {
    val codec = (s.codec ?: "").lowercase()
    val lang = s.language?.takeIf { it.isNotBlank() }
    val title = s.title?.takeIf { it.isNotBlank() }
    val baseLabel = title ?: langLabel(lang)
    val forced = if (s.isForced) " (forced)" else ""

    return when {
        codec == "webvtt-generated" -> {
            val l = lang ?: "en"
            SubtitleOption(
                key = "ext:${s.index}",
                label = "🎤 ${langLabel(l)} (KI)",
                externalUri = "$baseUrl/api/generated-subtitle/$itemId/$l.vtt",
                language = l
            )
        }
        codec in TEXT_BASED_CODECS -> SubtitleOption(
            key = "ext:${s.index}",
            label = "$baseLabel$forced",
            externalUri = "$baseUrl/api/subtitle/$itemId/${s.index}.vtt",
            language = lang
        )
        codec in IMAGE_BASED_CODECS -> {
            // PGS u. ä. — nur bei Direct Play. Bei Transcode hat der Server den
            // Track nicht in der HLS-Playlist, daher überspringen.
            if (mode == "direct") {
                SubtitleOption(
                    key = "embed:${s.index}",
                    label = "$baseLabel$forced (Bild)",
                    externalUri = null,
                    language = lang
                )
            } else null
        }
        else -> null  // unbekannter Codec → vorsichtshalber überspringen
    }
}
