package com.goldfish.android.util

import java.text.Collator
import java.util.Locale

// Natural-Sort fuer Strings: Zahlen werden als Werte verglichen, nicht
// Ziffer fuer Ziffer. Pendant zum Browser-`Intl.Collator("de", {numeric:true,
// sensitivity:"base"})` in applyNaturalTitleSort (app.js).
//
// "Episode 2" < "Episode 10" (statt lex: "10" < "2").
// SQLite kann das nicht — daher client-seitig nach dem Fetch.
private val baseCollator: Collator = Collator.getInstance(Locale.GERMAN).apply {
    strength = Collator.PRIMARY  // case-/akzent-insensitiv, wie sensitivity:"base"
}

fun naturalCompare(a: String?, b: String?): Int {
    val sa = a.orEmpty()
    val sb = b.orEmpty()
    var i = 0
    var j = 0
    while (i < sa.length && j < sb.length) {
        val ca = sa[i]
        val cb = sb[j]
        val aDigit = ca.isDigit()
        val bDigit = cb.isDigit()
        if (aDigit && bDigit) {
            // Zahlen-Run extrahieren und numerisch vergleichen.
            var ei = i
            while (ei < sa.length && sa[ei].isDigit()) ei++
            var ej = j
            while (ej < sb.length && sb[ej].isDigit()) ej++
            // Fuehrende Nullen ignorieren beim Wertvergleich.
            val aTrim = sa.substring(i, ei).trimStart('0').ifEmpty { "0" }
            val bTrim = sb.substring(j, ej).trimStart('0').ifEmpty { "0" }
            if (aTrim.length != bTrim.length) return aTrim.length - bTrim.length
            val cmp = aTrim.compareTo(bTrim)
            if (cmp != 0) return cmp
            i = ei
            j = ej
        } else {
            // Nicht-Zahlen-Run: locale-aware Collator-Vergleich Stueck fuer Stueck.
            var ei = i
            while (ei < sa.length && !sa[ei].isDigit()) ei++
            var ej = j
            while (ej < sb.length && !sb[ej].isDigit()) ej++
            val cmp = baseCollator.compare(sa.substring(i, ei), sb.substring(j, ej))
            if (cmp != 0) return cmp
            i = ei
            j = ej
        }
    }
    return (sa.length - i) - (sb.length - j)
}
