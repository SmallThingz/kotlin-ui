package android.os;

/** Identity fixture for loading the production dispatcher on the host JVM. */
public final class Looper {
    private static final Looper MAIN = new Looper();
    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return MAIN; }
}
