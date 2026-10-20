package com.goldfish.android.util

import com.goldfish.android.data.model.Item

// Pendant zum Browser-`groupVariants` (app.js): Items mit gleicher metadataId
// werden zu einer Kachel zusammengefasst. Repraesentant pro Gruppe = hoechste
// Aufloesung, dann groesste Bitrate. Items ohne metadataId (z. B. unmatched
// Files) bleiben einzeln.
fun groupVariants(items: List<Item>): List<Item> {
    // Repraesentant und Dateianzahl pro metadataId; variantSplit-Items und
    // Items ohne metadataId bleiben immer einzeln (wie im Browser).
    val reps = HashMap<Int, Item>()
    val counts = HashMap<Int, Int>()
    for (it in items) {
        val key = it.metadataId
        if (key == null || key == 0 || it.variantSplit) continue
        counts[key] = (counts[key] ?: 0) + maxOf(1, it.groupedCount)
        val existing = reps[key]
        if (existing == null) {
            reps[key] = it
        } else {
            val better = it.height > existing.height ||
                (it.height == existing.height && it.bitrateKbps > existing.bitrateKbps)
            if (better) reps[key] = it
        }
    }
    // Reihenfolge wie vom Server geliefert: die Gruppe steht an der Position
    // ihres ersten Mitglieds, Einzel-Items an der Originalposition.
    val seen = HashSet<Int>()
    val out = ArrayList<Item>(items.size)
    for (it in items) {
        val key = it.metadataId
        if (key == null || key == 0 || it.variantSplit) { out += it; continue }
        if (seen.add(key)) out += reps.getValue(key).copy(groupedCount = counts.getValue(key))
    }
    return out
}
