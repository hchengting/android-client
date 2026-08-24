package io.netbird.client.tool;

import android.content.Context;
import android.content.SharedPreferences;

public class Preferences {

    private final String keyTraceLog = "tracelog";

    private final String keyForceRelayConnection = "isConnectionForceRelayed";

    private final String keyForceRelayOnDeviceIdle = "isForceRelayOnDeviceIdleEnabled";

    private final SharedPreferences sharedPref;

    public Preferences(Context context) {
       sharedPref = context.getSharedPreferences("netbird", Context.MODE_PRIVATE);
    }

    public boolean isTraceLogEnabled() {
       return sharedPref.getBoolean(keyTraceLog, false);
    }
    public void enableTraceLog() {
        sharedPref.edit().putBoolean(keyTraceLog, true).apply();
    }

    public void disableTraceLog() {
        sharedPref.edit().putBoolean(keyTraceLog, false).apply();
    }

    public boolean isConnectionForceRelayed() {
        return sharedPref.getBoolean(keyForceRelayConnection, true);
    }

    /**
     * Stores the force-relay preference and reports whether it changed.
     */
    public boolean setConnectionForceRelayed(boolean enabled) {
        if (isConnectionForceRelayed() == enabled) {
            return false;
        }

        sharedPref.edit().putBoolean(keyForceRelayConnection, enabled).apply();
        return true;
    }

    public void enableForcedRelayConnection() {
        setConnectionForceRelayed(true);
    }

    public void disableForcedRelayConnection() {
        setConnectionForceRelayed(false);
    }

    public boolean isForceRelayOnDeviceIdleEnabled() {
        return sharedPref.getBoolean(keyForceRelayOnDeviceIdle, false);
    }

    /**
     * Enables or disables automatic force-relay control while the device is idle.
     *
     * <p>The UI keeps this mode mutually exclusive with manual force relay. The
     * effective force-relay preference remains separate because it changes as
     * the device enters idle mode and the user later unlocks it.</p>
     */
    public boolean setForceRelayOnDeviceIdleEnabled(boolean enabled) {
        if (isForceRelayOnDeviceIdleEnabled() == enabled) {
            return false;
        }

        sharedPref.edit().putBoolean(keyForceRelayOnDeviceIdle, enabled).apply();
        return true;
    }

    public static String defaultServer() {
        return "https://api.netbird.io";
    }
}
