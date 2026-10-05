package de.joinnoah.pi.remote

/** Main-thread confined LRU cache. Reads promote entries; eviction never affects active state. */
internal class NavigationMemoryCache<K, V>(private val capacity: Int) {
    init { require(capacity > 0) }
    private val entries = LinkedHashMap<K, V>()

    operator fun get(key: K): V? {
        val value = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

    operator fun set(key: K, value: V) {
        entries.remove(key)
        entries[key] = value
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    fun clear() = entries.clear()
}
