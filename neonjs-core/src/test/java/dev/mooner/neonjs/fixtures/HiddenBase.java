package dev.mooner.neonjs.fixtures;

/**
 * A package-private class whose public methods its public subclasses inherit, as {@code AbstractStringBuilder} for
 * {@code StringBuilder}: javac gives {@link Visible} a public bridge for each of them (JDK-6342411).
 */
abstract class HiddenBase<T> {
    T value;

    /** A public field of a non-public class: Java code can read it through Visible, reflection cannot. */
    public int count = 1;

    public String hello() {
        return "hello from " + getClass().getSimpleName();
    }

    public int twice(int x) {
        return 2 * x;
    }

    public T get() {
        return value;
    }

    public void set(T v) {
        value = v;
    }

    /** Overridden with a covariant return type in {@link Visible}. */
    public HiddenBase<T> self() {
        return this;
    }
}
