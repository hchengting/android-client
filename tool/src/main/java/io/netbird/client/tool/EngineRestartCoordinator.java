package io.netbird.client.tool;

import java.util.function.BooleanSupplier;

/**
 * Serializes requests that restart a running engine after it has fully stopped.
 */
final class EngineRestartCoordinator {
    private final BooleanSupplier engineRunning;
    private final Runnable stopEngine;
    private boolean restartPending;

    EngineRestartCoordinator(BooleanSupplier engineRunning, Runnable stopEngine) {
        this.engineRunning = engineRunning;
        this.stopEngine = stopEngine;
    }

    /**
     * Requests one restart when the engine is currently running.
     *
     * @return true when a restart is pending, or false when the engine was idle
     */
    synchronized boolean requestRestart() {
        if (restartPending) {
            return true;
        }
        if (!engineRunning.getAsBoolean()) {
            return false;
        }

        restartPending = true;
        stopEngine.run();
        return true;
    }

    synchronized boolean isRestartPending() {
        return restartPending;
    }

    /**
     * Consumes a pending request after the engine's stopped callback has run.
     */
    synchronized boolean consumeRestart() {
        if (!restartPending) {
            return false;
        }

        restartPending = false;
        return true;
    }

    synchronized void cancelRestart() {
        restartPending = false;
    }
}
