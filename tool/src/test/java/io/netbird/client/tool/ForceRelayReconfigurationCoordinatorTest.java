package io.netbird.client.tool;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ForceRelayReconfigurationCoordinatorTest {
    @Test
    public void queuedRequestsApplyOnlyLatestValue() {
        List<Runnable> tasks = new ArrayList<>();
        List<Boolean> applied = new ArrayList<>();
        ForceRelayReconfigurationCoordinator coordinator =
                new ForceRelayReconfigurationCoordinator(tasks::add, applied::add);

        coordinator.request(true);
        coordinator.request(false);

        Assert.assertEquals(1, tasks.size());
        Assert.assertTrue(coordinator.isPending());
        tasks.get(0).run();
        Assert.assertEquals(List.of(false), applied);
        Assert.assertFalse(coordinator.isPending());
    }

    @Test
    public void requestDuringApplyRunsOneMorePassWithLatestValue() {
        List<Runnable> tasks = new ArrayList<>();
        List<Boolean> applied = new ArrayList<>();
        ForceRelayReconfigurationCoordinator[] holder =
                new ForceRelayReconfigurationCoordinator[1];
        holder[0] = new ForceRelayReconfigurationCoordinator(tasks::add, enabled -> {
            applied.add(enabled);
            if (enabled) {
                holder[0].request(false);
            }
        });

        holder[0].request(true);
        tasks.get(0).run();

        Assert.assertEquals(List.of(true, false), applied);
        Assert.assertFalse(holder[0].isPending());
    }

    @Test
    public void cancelDropsValueThatHasNotStarted() {
        List<Runnable> tasks = new ArrayList<>();
        List<Boolean> applied = new ArrayList<>();
        ForceRelayReconfigurationCoordinator coordinator =
                new ForceRelayReconfigurationCoordinator(tasks::add, applied::add);

        coordinator.request(true);
        coordinator.cancelPending();
        tasks.get(0).run();

        Assert.assertTrue(applied.isEmpty());
        Assert.assertFalse(coordinator.isPending());
    }

    @Test
    public void closeDropsPendingAndFutureValues() {
        List<Runnable> tasks = new ArrayList<>();
        List<Boolean> applied = new ArrayList<>();
        ForceRelayReconfigurationCoordinator coordinator =
                new ForceRelayReconfigurationCoordinator(tasks::add, applied::add);

        coordinator.request(true);
        coordinator.close();
        coordinator.request(false);
        tasks.get(0).run();

        Assert.assertTrue(applied.isEmpty());
        Assert.assertFalse(coordinator.isPending());
        Assert.assertEquals(1, tasks.size());
    }
}
