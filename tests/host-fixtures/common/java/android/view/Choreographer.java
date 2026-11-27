package android.view;

import java.util.ArrayList;
import java.util.List;

/** Host scheduling fixture only; this does not emulate Android rendering or timing. */
public final class Choreographer {
    public interface FrameCallback {
        void doFrame(long frameTimeNanos);
    }

    private static final Choreographer INSTANCE = new Choreographer();
    private final List<FrameCallback> pending = new ArrayList<>();

    public static Choreographer getInstance() { return INSTANCE; }
    public void postFrameCallback(FrameCallback callback) { pending.add(callback); }
    public void removeFrameCallback(FrameCallback callback) { pending.remove(callback); }
    public int pendingCount() { return pending.size(); }

    public List<FrameCallback> takeFrame() {
        List<FrameCallback> ready = new ArrayList<>(pending);
        pending.clear();
        return ready;
    }

    public void frame(long frameTimeNanos) {
        for (FrameCallback callback : takeFrame()) callback.doFrame(frameTimeNanos);
    }
}
