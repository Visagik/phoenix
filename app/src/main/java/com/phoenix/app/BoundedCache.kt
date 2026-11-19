package com.phoenix.app

/** Кэш с вытеснением самых давних: при переполнении уходит одна запись, а не все сразу (полная очистка заставляла пересчитывать весь пул). */
class BoundedCache<K, V>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
    }

    @Synchronized fun get(k: K): V? = map[k]
    @Synchronized fun put(k: K, v: V) { map[k] = v }
    @Synchronized fun size(): Int = map.size
}
