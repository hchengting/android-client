package io.netbird.client;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.netbird.client.tool.Preferences;
import io.netbird.client.tool.VPNService;

@RunWith(AndroidJUnit4.class)
public class ForceRelayCommandReceiverTest {
    private Context targetContext;

    @Before
    public void setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        clearPreferences();
    }

    @After
    public void tearDown() {
        clearPreferences();
    }

    @Test
    public void booleanExtraUpdatesPreferenceAndRequestsApply() {
        RecordingContext context = new RecordingContext(targetContext);
        Intent intent = new Intent(ForceRelayCommandReceiver.ACTION_SET_FORCE_RELAY)
                .putExtra(ForceRelayCommandReceiver.EXTRA_ENABLED, false);

        new ForceRelayCommandReceiver().onReceive(context, intent);

        Assert.assertFalse(new Preferences(targetContext).isConnectionForceRelayed());
        Assert.assertNotNull(context.lastBroadcast);
        Assert.assertEquals(VPNService.ACTION_APPLY_FORCE_RELAY_SETTING,
                context.lastBroadcast.getAction());
        Assert.assertEquals(targetContext.getPackageName(),
                context.lastBroadcast.getPackage());
    }

    @Test
    public void stringExtraSupportsTaskerStyleValues() {
        RecordingContext context = new RecordingContext(targetContext);
        Intent intent = new Intent(ForceRelayCommandReceiver.ACTION_SET_FORCE_RELAY)
                .putExtra(ForceRelayCommandReceiver.EXTRA_ENABLED, "false");

        new ForceRelayCommandReceiver().onReceive(context, intent);

        Assert.assertFalse(new Preferences(targetContext).isConnectionForceRelayed());
        Assert.assertNotNull(context.lastBroadcast);
    }

    @Test
    public void unchangedPreferenceStillRequestsServiceReconciliation() {
        RecordingContext context = new RecordingContext(targetContext);
        Intent intent = new Intent(ForceRelayCommandReceiver.ACTION_SET_FORCE_RELAY)
                .putExtra(ForceRelayCommandReceiver.EXTRA_ENABLED, true);

        new ForceRelayCommandReceiver().onReceive(context, intent);

        Assert.assertTrue(new Preferences(targetContext).isConnectionForceRelayed());
        Assert.assertNotNull(context.lastBroadcast);
        Assert.assertEquals(VPNService.ACTION_APPLY_FORCE_RELAY_SETTING,
                context.lastBroadcast.getAction());
    }

    @Test
    public void invalidExtraDoesNotChangePreference() {
        RecordingContext context = new RecordingContext(targetContext);
        Intent intent = new Intent(ForceRelayCommandReceiver.ACTION_SET_FORCE_RELAY)
                .putExtra(ForceRelayCommandReceiver.EXTRA_ENABLED, "not-a-boolean");

        new ForceRelayCommandReceiver().onReceive(context, intent);

        Assert.assertTrue(new Preferences(targetContext).isConnectionForceRelayed());
        Assert.assertNull(context.lastBroadcast);
    }

    private void clearPreferences() {
        targetContext.getSharedPreferences("netbird", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    private static final class RecordingContext extends ContextWrapper {
        private Intent lastBroadcast;

        private RecordingContext(Context base) {
            super(base);
        }

        @Override
        public void sendBroadcast(Intent intent) {
            lastBroadcast = intent;
        }
    }
}
