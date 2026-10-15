package com.mapmyroad.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class SearchHelpersTest {
    @Test
    fun language_supportedLanguagesPassThrough() {
        assertEquals("en", SearchLanguage.of(Locale.forLanguageTag("en-IN")))
        assertEquals("hi", SearchLanguage.of(Locale.forLanguageTag("hi-IN")))
        assertEquals("mr", SearchLanguage.of(Locale.forLanguageTag("mr")))
    }

    @Test
    fun language_othersFallBackToEnglish() {
        assertEquals("en", SearchLanguage.of(Locale.forLanguageTag("ta-IN")))
        assertEquals("en", SearchLanguage.of(Locale.GERMANY))
        assertEquals("en", SearchLanguage.of(Locale.ROOT))
    }

    @Test
    fun lruCache_evictsLeastRecentlyUsed() {
        val cache = QueryLruCache<String, Int>(maxSize = 2)
        cache.put("a", 1)
        cache.put("b", 2)
        // Reading "a" makes "b" the least recently used entry.
        assertEquals(1, cache["a"])
        cache.put("c", 3)
        assertNull(cache["b"])
        assertEquals(1, cache["a"])
        assertEquals(3, cache["c"])
        assertEquals(2, cache.size)
    }

    @Test
    fun lruCache_holdsAtMostMaxSize() {
        val cache = QueryLruCache<Int, Int>(maxSize = 20)
        (1..50).forEach { cache.put(it, it) }
        assertEquals(20, cache.size)
        assertEquals((31..50).toList(), cache.keys())
    }

    @Test(expected = IllegalArgumentException::class)
    fun lruCache_rejectsZeroSize() {
        QueryLruCache<String, String>(maxSize = 0)
    }

    @Test
    fun cacheKey_isCaseInsensitive_andIncludesLanguageAndBias() {
        val key = SearchCacheKey.of("Shaniwar Wada", "en", 18.52, 73.86)
        assertEquals(key, SearchCacheKey.of("shaniwar wada", "en", 18.52, 73.86))
        assertFalse(key == SearchCacheKey.of("shaniwar wada", "mr", 18.52, 73.86))
        assertFalse(key == SearchCacheKey.of("shaniwar wada", "en", null, null))
        assertFalse(key.toString().contains("wada", ignoreCase = true))
    }
}
