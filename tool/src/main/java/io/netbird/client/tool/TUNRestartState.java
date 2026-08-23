package io.netbird.client.tool;

final class TUNRestartState {
    private boolean preserveForRestart;
    private boolean restartRunStarted;

    void preserveForRestart() {
        preserveForRestart = true;
        restartRunStarted = false;
    }

    void onEngineRunStarted() {
        if (preserveForRestart) {
            restartRunStarted = true;
        }
    }

    boolean canReuseTun(boolean hasRetainedTun, TUNParameters retained,
                        TUNParameters requested) {
        return preserveForRestart
                && restartRunStarted
                && hasRetainedTun
                && retained != null
                && retained.hasSameConfiguration(requested);
    }

    void onTunConfigured() {
        if (restartRunStarted) {
            cancel();
        }
    }

    boolean shouldKeepTunOnEngineStopped() {
        return preserveForRestart && !restartRunStarted;
    }

    void cancel() {
        preserveForRestart = false;
        restartRunStarted = false;
    }
}
