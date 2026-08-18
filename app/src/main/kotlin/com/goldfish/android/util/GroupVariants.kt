package com.goldfish.android.util

import com.goldfish.android.data.model.Item

// Pendant zum Browser-`groupVariants` (app.js): Items mit gleicher metadataId
// werden zu einer Kachel zusammengefasst. Repraesentant pro Gruppe = hoechste
// Aufloesung, dann groesste Bitrate. Items ohne metadataId (z. B. unmatched
// Files) bleiben einzeln.
fun groupVariants(items: List<Item>): List<Item> {
    val groups = LinkedHashMap<Int, Item>()
    val singletons = mutableListOf<Item>()
    for (it in items) {
        val key = it.metadataId
        if (key == null || key == 0) {
            singletons += it
            continue
        }
        val existing = groups[key]
        if (existing == null) {
            groups[key] = it
        } else {
            val better = it.height > existing.height ||
                (it.height == existing.height && it.bitrateKbps > existing.bitrateKbps)
            if (better) groups[key] = it
        }
    }
    // Reihenfolge wie sie der Server geliefert hat (server-seitige Sortierung
    // stabil halten) — `groups.values` liefert in Insertion-Order, plus die
    // Singletons in ihrer Originalposition. Einfacher Mix: in einem Durchlauf
    // bauen, statt zwei separate Listen am Ende zu verbinden.
    val seen = HashSet<Int>()
    val out = ArrayList<Item>(items.size)
    for (it in items) {
        val key = it.metadataId
        if (key == null || key == 0) { out += it; continue }
        if (seen.add(key)) out += groups.getValue(key)
    }
    return out
}
