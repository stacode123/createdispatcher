package net.Dispatcher.web.live;

import net.Dispatcher.config.DispatcherConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Rolling ring of recent sampler frames (immutable), sized by {@code Web Replay Buffer Seconds}.
 * Feeds replay lead-up windows. Pushed from the server thread; snapshots taken from any thread.
 */
public final class FrameHistory {
    private final ArrayDeque<LiveTrainSampler.Frame> frames = new ArrayDeque<>();

    public synchronized void push(LiveTrainSampler.Frame frame) {
        int cadenceTicks = Math.max(1, DispatcherConfig.COMMON.WebLiveSampleTicks.get());
        int capacity = Math.max(2, DispatcherConfig.COMMON.WebReplayBufferSeconds.get() * 20 / cadenceTicks);
        frames.addLast(frame);
        while (frames.size() > capacity) frames.removeFirst();
    }

    /** Frames with gameTick >= sinceTick, oldest first. */
    public synchronized List<LiveTrainSampler.Frame> since(long sinceTick) {
        List<LiveTrainSampler.Frame> copy = new ArrayList<>();
        for (LiveTrainSampler.Frame frame : frames)
            if (frame.gameTick >= sinceTick) copy.add(frame);
        return copy;
    }
}
