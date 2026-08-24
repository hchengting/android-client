package io.netbird.client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

import io.netbird.client.tool.Preferences;
import io.netbird.client.tool.VPNService;

/**
 * Public automation entry point for changing the global force-relay setting.
 */
public final class ForceRelayCommandReceiver extends BroadcastReceiver {
    public static final String ACTION_SET_FORCE_RELAY =
            "io.netbird.client.intent.action.SET_FORCE_RELAY";
    public static final String EXTRA_ENABLED =
            "io.netbird.client.intent.extra.ENABLED";

    public static final int RESULT_UPDATED = 1;
    public static final int RESULT_UNCHANGED = 2;
    public static final int RESULT_INVALID_REQUEST = 3;

    private static final String LOGTAG = "ForceRelayIntent";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SET_FORCE_RELAY.equals(intent.getAction())) {
            finishOrderedBroadcast(RESULT_INVALID_REQUEST, "invalid_action");
            return;
        }

        Preferences preferences = new Preferences(context);
        if (preferences.isForceRelayOnDeviceIdleEnabled()) {
            Log.i(LOGTAG, "Ignoring force-relay intent while idle automatic mode is enabled");
            finishOrderedBroadcast(RESULT_UNCHANGED, "ignored_idle_auto_mode");
            return;
        }

        Boolean enabled = readEnabled(intent);
        if (enabled == null) {
            Log.w(LOGTAG, "Ignoring force-relay intent without a valid ENABLED extra");
            finishOrderedBroadcast(RESULT_INVALID_REQUEST, "invalid_enabled_extra");
            return;
        }

        boolean changed = preferences.setConnectionForceRelayed(enabled);

        Intent applyIntent = new Intent(VPNService.ACTION_APPLY_FORCE_RELAY_SETTING);
        applyIntent.setPackage(context.getPackageName());
        context.sendBroadcast(applyIntent);
        finishOrderedBroadcast(
                changed ? RESULT_UPDATED : RESULT_UNCHANGED,
                changed ? "updated" : "unchanged"
        );
    }

    @Nullable
    private static Boolean readEnabled(Intent intent) {
        Bundle extras;
        try {
            extras = intent.getExtras();
        } catch (RuntimeException e) {
            Log.w(LOGTAG, "Unable to read force-relay intent extras", e);
            return null;
        }
        if (extras == null || !extras.containsKey(EXTRA_ENABLED)) {
            return null;
        }

        Object value;
        try {
            value = extras.get(EXTRA_ENABLED);
        } catch (RuntimeException e) {
            Log.w(LOGTAG, "Unable to read force-relay ENABLED extra", e);
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            String stringValue = ((String) value).trim();
            if ("true".equalsIgnoreCase(stringValue)) {
                return true;
            }
            if ("false".equalsIgnoreCase(stringValue)) {
                return false;
            }
        }
        return null;
    }

    private void finishOrderedBroadcast(int resultCode, String resultData) {
        if (!isOrderedBroadcast()) {
            return;
        }
        setResultCode(resultCode);
        setResultData(resultData);
    }
}
