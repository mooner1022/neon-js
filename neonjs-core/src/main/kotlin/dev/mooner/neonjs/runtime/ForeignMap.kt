package dev.mooner.neonjs.runtime

/**
 * A host `Map` shown as a JS Map: `Map.prototype` is behind it and [JSObject.FOREIGN_MAP] is set, as GraalJS gives
 * foreign hash maps. `Map.prototype`'s methods (`get`, `set`, `has`, `delete`, `clear`, `size`, `forEach`, `keys`,
 * `values`, `entries` and iteration) reach its entries through these, with keys and values as JS values.
 */
interface ForeignMap {
    /** The value of [key], or [Undefined] when there is no such entry. */
    fun mapGet(key: Any?): Any?
    fun mapSet(key: Any?, value: Any?)
    fun mapHas(key: Any?): Boolean
    fun mapDelete(key: Any?): Boolean
    fun mapClear()
    fun mapSize(): Int

    /**
     * A cursor over the keys as they are now: each call gives the next `[key, value]` whose key is still there, or null
     * at the end. A key removed meanwhile is skipped and one added meanwhile is not visited, where a JS Map's iteration
     * would visit it; changing the Map during the loop never throws (GraalJS throws Java's
     * ConcurrentModificationException).
     */
    fun mapCursor(): () -> Array<Any?>?
}
