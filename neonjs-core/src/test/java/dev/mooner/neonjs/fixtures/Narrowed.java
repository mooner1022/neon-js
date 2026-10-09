package dev.mooner.neonjs.fixtures;

/** Overrides {@code set} for its type argument: the generic bridge {@code set(Object)} must not become an overload. */
public class Narrowed extends HiddenBase<String> {
    @Override
    public void set(String v) {
        value = v + "!";
    }
}
