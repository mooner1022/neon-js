package dev.mooner.neonjs.fixtures;

/** {@code compareTo(Object)} is a generic bridge: only {@code compareTo(Version)} is a JS member. */
public class Version implements Comparable<Version> {
    private final int n;

    public Version(int n) {
        this.n = n;
    }

    @Override
    public int compareTo(Version o) {
        return Integer.compare(n, o.n);
    }
}
