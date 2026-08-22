package io.netbird.client.tool;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class EngineRestartCoordinatorTest {
    @Test
    public void requestWhileStoppedDoesNotScheduleRestart() {
        AtomicInteger stops = new AtomicInteger();
        EngineRestartCoordinator coordinator = new EngineRestartCoordinator(
                () -> false,
                stops::incrementAndGet
        );

        Assert.assertFalse(coordinator.requestRestart());
        Assert.assertFalse(coordinator.isRestartPending());
        Assert.assertEquals(0, stops.get());
    }

    @Test
    public void repeatedRequestsStopRunningEngineOnce() {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger stops = new AtomicInteger();
        EngineRestartCoordinator coordinator = new EngineRestartCoordinator(
                running::get,
                stops::incrementAndGet
        );

        Assert.assertTrue(coordinator.requestRestart());
        Assert.assertTrue(coordinator.requestRestart());
        Assert.assertTrue(coordinator.isRestartPending());
        Assert.assertEquals(1, stops.get());
    }

    @Test
    public void consumeReturnsTrueOnlyOnce() {
        EngineRestartCoordinator coordinator = new EngineRestartCoordinator(
                () -> true,
                () -> { }
        );
        coordinator.requestRestart();

        Assert.assertTrue(coordinator.consumeRestart());
        Assert.assertFalse(coordinator.consumeRestart());
        Assert.assertFalse(coordinator.isRestartPending());
    }

    @Test
    public void cancelPreventsScheduledRestart() {
        EngineRestartCoordinator coordinator = new EngineRestartCoordinator(
                () -> true,
                () -> { }
        );
        coordinator.requestRestart();

        coordinator.cancelRestart();

        Assert.assertFalse(coordinator.consumeRestart());
    }
}
