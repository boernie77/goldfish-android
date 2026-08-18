package com.goldfish.android.ui.locallib

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser

/**
 * ExtractorsFactory die zusaetzlich zu den Default-Extractoren mehrere
 * "tolerant" Force-Extractoren anhaengt, deren `sniff()` immer true
 * zurueckgibt. Damit umgehen wir die strikten Container-Brand-Checks
 * von Media3.
 *
 * **Warum:** ExoPlayer's Default-Extractoren lehnen Files ab, deren
 * Container-Header (ftyp-Box im MP4, EBML-Header in MKV) entweder
 * fehlt, einen unbekannten Brand deklariert oder eine unerwartete
 * Box-Reihenfolge hat. Fehlerbild: `sniff failures: [NoDeclaredBrand]`
 * o.ae. Die Files sind dabei oft strukturell valide — VLC spielt sie
 * problemlos, weil libVLC viel toleranter parst.
 *
 * **Reihenfolge:** die Force-Extractoren stehen NACH den Default-Sniffern.
 * Normale Files (mp4 mit bekanntem Brand, valides mkv, …) werden weiter
 * wie bisher behandelt — die Force-Pfade greifen NUR wenn alle
 * Default-Sniffs fehlschlagen. Innerhalb des Force-Blocks probiert
 * ExoPlayer der Reihe nach: Mp4 → FragmentedMp4 → Matroska. Wenn keiner
 * parsen kann, kommt der ueblicher PlaybackException.
 *
 * **Workaround-Flags:** der Mp4Extractor bekommt FLAG_WORKAROUND_IGNORE_
 * EDIT_LISTS — manche MP4-Encoder schreiben kaputte Edit Lists, die
 * sonst zum Abbruch fuehren.
 */
@UnstableApi
class TolerantExtractorsFactory : ExtractorsFactory {
    private val base = DefaultExtractorsFactory().also {
        // Erweitert auch den Default-Mp4Extractor um Workaround-Flags —
        // gilt fuer ALLE Files (auch die, die der Sniff schon akzeptiert).
        it.setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
        it.setFragmentedMp4ExtractorFlags(FragmentedMp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
    }

    override fun createExtractors(): Array<Extractor> {
        val standard = base.createExtractors()
        return standard + forcedFallbacks()
    }

    override fun createExtractors(
        uri: android.net.Uri,
        responseHeaders: MutableMap<String, MutableList<String>>
    ): Array<Extractor> {
        val standard = base.createExtractors(uri, responseHeaders)
        return standard + forcedFallbacks()
    }

    private fun forcedFallbacks(): Array<Extractor> = arrayOf(
        ForcedExtractor(Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED,
            Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)),
        ForcedExtractor(FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED,
            FragmentedMp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)),
        ForcedExtractor(MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED))
    )
}

/**
 * Universal-Wrapper um einen beliebigen Media3-Extractor, dessen `sniff()`
 * immer `true` zurueckgibt. Damit erzwingen wir, dass ExoPlayer den
 * Extractor auch dann probiert wenn sein eingebauter Sniff-Check
 * fehlschlagen wuerde. Wenn das File wirklich nicht zum Extractor passt,
 * scheitert das Parsen mit klarem PlaybackException-Cause.
 *
 * NICHT als einziger Extractor verwenden — gehoert immer ans Ende einer
 * ExtractorsFactory-Liste, hinter den echten Sniffern.
 */
@UnstableApi
private class ForcedExtractor(private val delegate: Extractor) : Extractor {
    override fun sniff(input: ExtractorInput): Boolean = true
    override fun init(output: ExtractorOutput) = delegate.init(output)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)
    override fun release() = delegate.release()
}
