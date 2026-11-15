package dev.mooner.neonjs.runtime

/**
 * An object whose elements are a host collection's: a Java array or `List` shown as a JS array, with
 * `Array.prototype` behind it and [JSObject.FOREIGN_ARRAY] set (GraalJS's foreign arrays).
 *
 * `Array.prototype`'s methods that change the length (`push`, `pop`, `shift`, `unshift`, `splice`) hand their work to
 * [spliceElements] instead of moving elements one at a time. A collection whose length cannot change then fails
 * before any element has changed, rather than half shifted.
 */
interface ForeignArray {
    /**
     * Removes [deleteCount] elements at [start] and inserts [items] (JS values) there; both lie within the current
     * length. Returns the removed elements as JS values. Throws a TypeError, with nothing changed, when the
     * collection's length cannot change and [items] is not as long as [deleteCount].
     */
    fun spliceElements(start: Int, deleteCount: Int, items: Array<Any?>): Array<Any?>
}
