package com.mapmyroad.ui.search

import java.util.Locale

/** Search language (API_SPEC.md 5.7 `lang`): the app language when the app ships it, otherwise English. */
object SearchLanguage {
    const val FALLBACK = "en"
    val SUPPORTED: Set<String> = setOf("en", "hi", "mr")

    fun of(locale: Locale): String {
        val language = locale.language.lowercase(Locale.ROOT)
        return if (language in SUPPORTED) language else FALLBACK
    }
}

/**
 * Small in-memory LRU cache for search answers (M5-7). The server caches as well; this one only saves round trips
 * while the user edits a query back and forth. Not thread safe: the view model uses it on the main thread.
 */
class QueryLruCache<K, V>(
    private val maxSize: Int,
) {
    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    // accessOrder = true makes iteration order least recently used first.
    private val map =
        object : LinkedHashMap<K, V>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
        }

    val size: Int get() = map.size

    operator fun get(key: K): V? = map[key]

    fun put(
        key: K,
        value: V,
    ) {
        map[key] = value
    }

    fun clear() {
        map.clear()
    }

    /** Keys from least to most recently used, for tests. */
    fun keys(): List<K> = map.keys.toList()

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}

/** Cache key of one search: normalized query, language and the rounded bias (null when there is no bias). */
data class SearchCacheKey(
    val query: String,
    val language: String,
    val biasLat: Double?,
    val biasLon: Double?,
) {
    // Holds search text and an approximate position: keep it out of logs.
    override fun toString(): String = "SearchCacheKey(***)"

    companion object {
        /** Case-insensitive on the query, as typing "Pune" and "pune" should hit the same entry. */
        fun of(
            normalizedQuery: String,
            language: String,
            roundedBiasLat: Double?,
            roundedBiasLon: Double?,
        ): SearchCacheKey =
            SearchCacheKey(
                query = normalizedQuery.lowercase(Locale.ROOT),
                language = language,
                biasLat = roundedBiasLat,
                biasLon = roundedBiasLon,
            )
    }
}
