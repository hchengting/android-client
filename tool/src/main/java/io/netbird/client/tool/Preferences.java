package io.netbird.client.tool;

import android.content.Context;
import android.content.SharedPreferences;

public class Preferences {

    private final String keyTraceLog = "tracelog";

    private final String keyForceRelayConnection = "isConnectionForceRelayed";

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

    public static String defaultServer() {
        return "https://api.netbird.io";
    }
}
