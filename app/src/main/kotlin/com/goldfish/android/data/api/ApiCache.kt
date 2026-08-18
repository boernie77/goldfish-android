package com.goldfish.android.data.api

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Einfacher In-Memory-LRU-Cache mit TTL für Repository-Antworten.
 * Wirkt als Schicht VOR OkHttp/Retrofit — spart komplette Round-Trips beim
 * Library-Wechsel, Detail-Öffnen, Home-Refresh.
 */
@Singleton
class ApiCache @Inject constructor() {
    private data class Entry(val data: Any, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val maxEntries = 200

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String): T? {
        val e = cache[key] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) {
            cache.remove(key)
            return null
        }
        return e.data as? T
    }

    fun put(key: String, data: Any, ttlMs: Long = 30_000L) {
        if (cache.size >= maxEntries) {
            // Naive Evict: ältesten Eintrag rauswerfen
            cache.entries.minByOrNull { it.value.expiresAt }?.key?.let { cache.remove(it) }
        }
        cache[key] = Entry(data, System.currentTimeMillis() + ttlMs)
    }

    fun invalidate(prefix: String) {
        cache.keys.toList().forEach { if (it.startsWith(prefix)) cache.remove(it) }
    }

    fun clear() = cache.clear()
}
