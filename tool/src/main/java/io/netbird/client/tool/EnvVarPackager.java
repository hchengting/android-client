package io.netbird.client.tool;

import io.netbird.gomobile.android.Android;
import io.netbird.gomobile.android.EnvList;

public class EnvVarPackager {
    public static EnvList getEnvironmentVariables(Preferences preferences) {
        return getEnvironmentVariables(preferences.isConnectionForceRelayed());
    }

    public static EnvList getEnvironmentVariables(boolean forceRelayEnabled) {
        var envList = new EnvList();

        envList.put(Android.getEnvKeyNBForceRelay(), String.valueOf(forceRelayEnabled));

        return envList;
    }
}
