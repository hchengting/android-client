package io.netbird.client.tool.networks;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;

import io.netbird.gomobile.android.ControlPlaneResolver;
import io.netbird.gomobile.android.IPList;

/**
 * Resolves NetBird control-plane hostnames on Android's best non-VPN network.
 * This class never changes the process default network or the VPN DNS path.
 */
public final class UnderlyingNetworkResolver implements ControlPlaneResolver {
    private static final String LOGTAG = UnderlyingNetworkResolver.class.getSimpleName();
    private static final long NETWORK_WAIT_MILLIS = 5_000L;

    private final ConnectivityManager connectivityManager;
    private final Handler callbackHandler;
    private final Object networkLock = new Object();

    @Nullable
    private Network currentNetwork;
    @Nullable
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean registered;

    public UnderlyingNetworkResolver(ConnectivityManager connectivityManager,
                                     Handler callbackHandler) {
        this.connectivityManager = connectivityManager;
        this.callbackHandler = callbackHandler;
    }

    /** Starts tracking the best internet-capable network outside this VPN. */
    public void register() {
        final ConnectivityManager.NetworkCallback callback =
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(@NonNull Network network) {
                        publishNetwork(network);
                    }

                    @Override
                    public void onLost(@NonNull Network network) {
                        clearNetwork(network);
                    }

                    @Override
                    public void onUnavailable() {
                        clearNetwork(null);
                    }
                };

        synchronized (networkLock) {
            if (registered) {
                return;
            }
            registered = true;
            networkCallback = callback;
        }

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                connectivityManager.registerBestMatchingNetworkCallback(
                        request, callback, callbackHandler);
            } else {
                // requestNetwork tracks one best matching Network on API 26-30
                // and reports a replacement before losing the old selection.
                connectivityManager.requestNetwork(request, callback, callbackHandler);
            }
        } catch (RuntimeException e) {
            synchronized (networkLock) {
                registered = false;
                networkCallback = null;
                networkLock.notifyAll();
            }
            throw e;
        }
    }

    /** Stops network tracking and releases any lookup waiting for a network. */
    public void unregister() {
        final ConnectivityManager.NetworkCallback callback;
        synchronized (networkLock) {
            if (!registered) {
                return;
            }
            registered = false;
            currentNetwork = null;
            callback = networkCallback;
            networkCallback = null;
            networkLock.notifyAll();
        }

        if (callback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(callback);
            } catch (IllegalArgumentException e) {
                Log.w(LOGTAG, "network callback was already unregistered", e);
            }
        }
    }

    @Override
    public IPList resolveHost(String host) throws Exception {
        Network network = awaitNetwork(host);
        InetAddress[] addresses = network.getAllByName(host);
        IPList result = new IPList();
        for (InetAddress address : addresses) {
            result.add(address.getHostAddress());
        }
        return result;
    }

    private Network awaitNetwork(String host) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + NETWORK_WAIT_MILLIS;
        synchronized (networkLock) {
            while (registered && currentNetwork == null) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) {
                    break;
                }
                networkLock.wait(remaining);
            }
            if (currentNetwork == null) {
                throw new UnknownHostException(
                        "no Android non-VPN network available for " + host);
            }
            return currentNetwork;
        }
    }

    private void publishNetwork(Network network) {
        synchronized (networkLock) {
            if (!registered) {
                return;
            }
            currentNetwork = network;
            networkLock.notifyAll();
        }
    }

    private void clearNetwork(@Nullable Network network) {
        synchronized (networkLock) {
            if (!registered) {
                return;
            }
            if (network == null || network.equals(currentNetwork)) {
                currentNetwork = null;
                networkLock.notifyAll();
            }
        }
    }
}
