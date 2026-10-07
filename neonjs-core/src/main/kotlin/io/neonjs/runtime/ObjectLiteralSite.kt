package io.neonjs.runtime

/**
 * An object literal whose properties are all `key: value` with static, distinct keys (Op.NEW_OBJECT_LITERAL). The
 * object is created after its values are evaluated, directly in its final shape, sharing the key layout with every
 * other object of that site. The layout is cached per empty root shape (shapes belong to one context; code blocks,
 * and so sites, are shared): it is an immutable record published with a single write.
 */
class ObjectLiteralSite(@JvmField val keys: Array<Any>) {
    /** Last layout built; its root identifies the context it belongs to. */
    @JvmField var layout: PropertyMap.Layout? = null

    /** The object `{ keys[0]: values[0], ... }` with [proto]; [values] becomes its values array. */
    fun create(proto: JSObject, values: Array<Any?>): JSObject {
        val o = JSObject(proto)
        val root = Shape.emptyShapeOf(o)
        var l = layout
        if (l == null || l.root !== root) {
            l = PropertyMap.layoutOf(root, keys)
            if (l == null) {
                for (i in keys.indices) o.createDataProperty(keys[i], values[i])
                return o
            }
            layout = l
        }
        o.props = PropertyMap.withLayout(l, values)
        return o
    }
}
