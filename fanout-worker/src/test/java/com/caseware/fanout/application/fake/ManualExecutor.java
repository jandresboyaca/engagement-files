package com.caseware.fanout.application.fake;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;

/**
 * Queues submitted loads; the test decides when each one answers, so loads in flight are
 * observable without threads.
 */
public final class ManualExecutor implements Executor {

    private final Deque<Runnable> queued = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
        queued.add(command);
    }

    public int pending() {
        return queued.size();
    }

    /** Runs the oldest queued load. */
    public void runNext() {
        Runnable next = queued.poll();
        if (next == null) {
            throw new IllegalStateException("no queued load");
        }
        next.run();
    }

    /** Runs every load queued so far, but not loads they queue in turn. */
    public void runAll() {
        int count = queued.size();
        for (int i = 0; i < count; i++) {
            runNext();
        }
    }
}
