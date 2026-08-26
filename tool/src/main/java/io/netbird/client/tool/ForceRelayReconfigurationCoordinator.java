package io.netbird.client.tool;

import java.util.concurrent.Executor;

/** Coalesces force-relay changes and applies the latest value off the caller thread. */
final class ForceRelayReconfigurationCoordinator {
    interface Applier {
        void apply(boolean enabled);
    }

    private final Executor executor;
    private final Applier applier;
    private Boolean pending;
    private boolean drainScheduled;
    private boolean closed;

    ForceRelayReconfigurationCoordinator(Executor executor, Applier applier) {
        this.executor = executor;
        this.applier = applier;
    }

    synchronized void request(boolean enabled) {
        if (closed) {
            return;
        }
        pending = enabled;
        if (drainScheduled) {
            return;
        }

        drainScheduled = true;
        try {
            executor.execute(this::drain);
        } catch (RuntimeException e) {
            drainScheduled = false;
            throw e;
        }
    }

    synchronized void cancelPending() {
        pending = null;
    }

    synchronized void close() {
        closed = true;
        pending = null;
        drainScheduled = false;
    }

    synchronized boolean isPending() {
        return pending != null || drainScheduled;
    }

    private void drain() {
        while (true) {
            final boolean enabled;
            synchronized (this) {
                if (closed || pending == null) {
                    drainScheduled = false;
                    return;
                }
                enabled = pending;
                pending = null;
            }
            applier.apply(enabled);
        }
    }
}
