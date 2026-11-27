package android.os;

import java.util.ArrayList;
import java.util.List;

/** Host queue fixture only; posted work runs only when the test explicitly drains it. */
public final class Handler {
    private final List<Runnable> pending = new ArrayList<>();

    public static Handler createAsync(Looper looper) { return new Handler(); }
    public boolean post(Runnable callback) { pending.add(callback); return true; }
    public void removeCallbacks(Runnable callback) { pending.removeIf(value -> value == callback); }

    public void drain() {
        while (!pending.isEmpty()) {
            List<Runnable> ready = new ArrayList<>(pending);
            pending.clear();
            for (Runnable callback : ready) callback.run();
        }
    }
}
