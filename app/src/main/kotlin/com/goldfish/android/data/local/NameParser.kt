package com.goldfish.android.data.local

/**
 * Kotlin-Port von /Users/christian/Projekte/Videoplayer/internal/nameparser/parser.go
 * — extrahiert Titel + Jahr + Staffel/Episode aus einem Dateinamen.
 *
 * Gleiche Heuristiken wie der Server-Parser:
 *  - SxxExx, NxN (1x01) als primaere Episode-Marker
 *  - Im TV-Kontext zusaetzlich: E01 (Season=1) und 3-/4-stellige Codes (104 → S1E04)
 *  - Jahres-Extraktion mit Edge-Case "Titel ist Jahr" (1917.2019 → Title=1917, Year=2019)
 *  - Release-Trash (1080p/x264/BluRay/GERMAN/DL etc.) wird aus dem Titel entfernt
 *  - Release-Group-Suffix "-GROUP" am Ende raus
 *
 * Tests koennen spaeter angelegt werden — fuers erste 1:1-Port der Go-Logik.
 */
object NameParser {
    data class Parsed(
        val title: String,
        val year: Int = 0,
        val season: Int = 0,
        val episode: Int = 0,
        val episodeEnd: Int = 0,
        val isEpisode: Boolean = false
    )

    // (?i)S(\d{1,2})\s*E(\d{1,3})(?:\s*[-&]?\s*E(\d{1,3}))?(?:\s*[-&]?\s*E\d{1,3})*\b
    private val reSxxExx = Regex(
        """(?i)S(\d{1,2})\s*E(\d{1,3})(?:\s*[-&]?\s*E(\d{1,3}))?(?:\s*[-&]?\s*E\d{1,3})*\b"""
    )
    private val reNxN = Regex(
        """(?i)\b(\d{1,2})x(\d{1,3})(?:\s*[-&]\s*(?:\d{1,2}x)?(\d{1,3}))?\b"""
    )
    private val reEonly = Regex("""(?i)\bE(\d{1,3})\b""")
    private val reSeasonRange = Regex("""(?i)\bS(\d{1,2})\s*[-–]\s*S(\d{1,2})\b""")
    private val reSeasonOnly = Regex("""(?i)\bS(\d{1,2})\b""")
    private val reYear = Regex("""\b(19|20)\d{2}\b""")
    private val reNumericEp = Regex("""\b(\d{3,4})\b""")
    private val reTrash = Regex(
        """(?i)\b(480p|576p|720p|1080p|2160p|4k|uhd|hdr|hdr10|hevc|x264|x265|h264|h265|blu[- ]?ray|bdrip|brrip|web[- ]?dl|web|webrip|hdtv|dvdrip|dvd|tvrip|xvid|divx|aac|ac3|dd5?1?|dd\+?|ddp|dts|dts[- ]?hd|atmos|truehd|multi|multisub|dual|dubbed|german|deutsch|english|eng|ger|dl|fs|ws|ld|ml|proper|repack|remux|extended|uncut|directors?[- ]?cut|imax|unrated|custom|internal|retail|limited|complete|video|amazonhd|amzn|hulu|dsnp|hmax|scene|t\d{2,}|cd\d+|disc\d+|part\d+|v\d{1,2})\b"""
    )
    private val reGroupSuffix = Regex("""(?i)-[A-Z0-9]+$""")

    /** Filename-Parser im Film-Kontext (keine numerischen Episode-Codes). */
    fun parseFile(fileName: String): Parsed {
        val name = fileName.substringBeforeLast('.', fileName)
        return parseString(name, allowNumericEpisode = false)
    }

    /** Filename-Parser im TV-Kontext (E01/104/1004 erlaubt). */
    fun parseEpisodeFile(fileName: String): Parsed {
        val name = fileName.substringBeforeLast('.', fileName)
        return parseString(name, allowNumericEpisode = true)
    }

    /** Folder-Parser: Show-Name + Jahr aus Ordnernamen. */
    fun parseFolder(name: String): Parsed {
        var work = name.replace('.', ' ').replace('_', ' ')
        // Season-Range zuerst pruefen (spezifischer als SxxExx bei Release-Paketen)
        val mRange = reSeasonRange.find(work)
        if (mRange != null) {
            work = work.substring(0, mRange.range.first)
        } else {
            val mEp = reSxxExx.find(work)
            if (mEp != null) {
                work = work.substring(0, mEp.range.first)
            } else {
                val mSeason = reSeasonOnly.find(work)
                if (mSeason != null) {
                    val tail = work.substring(mSeason.range.last + 1)
                    if (reTrash.containsMatchIn(tail)) {
                        work = work.substring(0, mSeason.range.first)
                    }
                }
            }
        }
        val p = parseString(work, allowNumericEpisode = false)
        return p.copy(isEpisode = false, season = 0, episode = 0)
    }

    private fun parseString(raw: String, allowNumericEpisode: Boolean): Parsed {
        var work = raw.replace('.', ' ').replace('_', ' ')
        var season = 0
        var episode = 0
        var episodeEnd = 0
        var isEpisode = false
        var year = 0

        // 1. SxxExx
        val mSxx = reSxxExx.find(work)
        if (mSxx != null) {
            season = mSxx.groupValues[1].toIntOrNull() ?: 0
            episode = mSxx.groupValues[2].toIntOrNull() ?: 0
            isEpisode = true
            val g3 = mSxx.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }
            if (g3 != null) {
                val e2 = g3.toIntOrNull() ?: 0
                if (e2 > episode && e2 - episode <= 10) episodeEnd = e2
            }
            work = work.substring(0, mSxx.range.first)
        } else {
            // NxN (1x01)
            val mNxN = reNxN.find(work)
            if (mNxN != null) {
                val s = mNxN.groupValues[1].toIntOrNull() ?: 0
                val e = mNxN.groupValues[2].toIntOrNull() ?: 0
                if (s in 0..50 && e in 0..999) {
                    season = s
                    episode = e
                    isEpisode = true
                    val g3 = mNxN.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }
                    if (g3 != null) {
                        val e2 = g3.toIntOrNull() ?: 0
                        if (e2 > episode && e2 - episode <= 10) episodeEnd = e2
                    }
                    work = work.substring(0, mNxN.range.first)
                }
            }
        }

        // 1b. E-only (nur TV-Kontext)
        if (!isEpisode && allowNumericEpisode) {
            val mE = reEonly.find(work)
            if (mE != null) {
                val e = mE.groupValues[1].toIntOrNull() ?: 0
                if (e in 1..999) {
                    season = 1
                    episode = e
                    isEpisode = true
                    work = work.substring(0, mE.range.first) + " " +
                           work.substring(mE.range.last + 1)
                }
            }
        }

        // 1c. Numerische Codes (3-4 Ziffern) im TV-Kontext
        if (!isEpisode && allowNumericEpisode) {
            for (m in reNumericEp.findAll(work)) {
                val num = m.groupValues[1].toIntOrNull() ?: continue
                if (num in 1900..2099) continue  // Jahre ausschliessen
                val s = num / 100
                val e = num % 100
                if (s in 1..29 && e in 1..99) {
                    season = s
                    episode = e
                    isEpisode = true
                    work = work.substring(0, m.range.first) + " " +
                           work.substring(m.range.last + 1)
                    break
                }
            }
        }

        // 2. Jahr (mit Edge-Case "Titel ist Jahr")
        val yearMatches = reYear.findAll(work).toList()
        if (yearMatches.isNotEmpty()) {
            val trimmed = work.trimStart(' ', '\t')
            val leadOffset = work.length - trimmed.length
            val idx = if (yearMatches.size > 1 && yearMatches[0].range.first == leadOffset) 1 else 0
            year = yearMatches[idx].value.toIntOrNull() ?: 0
            work = work.substring(0, yearMatches[idx].range.first)
        }

        // 3. Trash raus
        work = reTrash.replace(work, " ")
        // 4. Klammern weg
        work = work.replace('(', ' ').replace(')', ' ')
                   .replace('[', ' ').replace(']', ' ')
        // 5. Group-Suffix "-GROUP"
        work = reGroupSuffix.replace(work, "")
        // 6. Hash-ID-Praefix
        work = stripHashPrefix(work.trim())
        // 7. Tokenweise trimmen
        val toks = work.split(' ').mapNotNull { tok ->
            tok.trim(' ', '-', '_', '.', ',').takeIf { it.isNotEmpty() }
        }
        return Parsed(
            title = toks.joinToString(" "),
            year = year,
            season = season,
            episode = episode,
            episodeEnd = episodeEnd,
            isEpisode = isEpisode
        )
    }

    private fun stripHashPrefix(s: String): String {
        val idx = s.indexOfAny(charArrayOf('-', '_'))
        if (idx < 8) return s
        val prefix = s.substring(0, idx)
        if (prefix.contains(' ')) return s
        val digits = prefix.count { it.isDigit() }
        if (digits < 3) return s
        return s.substring(idx + 1)
    }
}
