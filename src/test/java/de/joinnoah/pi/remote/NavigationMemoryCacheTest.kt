package de.joinnoah.pi.remote

import org.junit.Assert.*
import org.junit.Test

class NavigationMemoryCacheTest {
    @Test fun readsPromoteEntriesAndEvictLeastRecentlyUsed() {
        val cache = NavigationMemoryCache<String, String>(2)
        cache["a"] = "A"
        cache["b"] = "B"
        assertEquals("A", cache["a"])
        cache["c"] = "C"
        assertNull(cache["b"])
        assertEquals("A", cache["a"])
        assertEquals("C", cache["c"])
    }

    @Test fun replacingAndClearingDoNotLeaveOldValues() {
        val cache = NavigationMemoryCache<String, String>(2)
        cache["a"] = "old"
        cache["a"] = "new"
        assertEquals("new", cache["a"])
        cache.clear()
        assertNull(cache["a"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun capacityMustBePositive() { NavigationMemoryCache<String, String>(0) }
}
