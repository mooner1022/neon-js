package dev.mooner.neonjs.fixtures;

/** Inherits {@code hello}, {@code twice}, {@code get} and {@code set} through visibility bridges; {@code self} is covariant. */
public class Visible extends HiddenBase<String> {
    @Override
    public Visible self() {
        return this;
    }

    public String own() {
        return "own";
    }
}
