package com.example.sleppify;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

/** Same connectivity rule for the activity, search, library and playback screens. */
public final class NetworkStatus {
    private NetworkStatus() { }

    public static boolean isOnline(Context context) {
        if (context == null) return false;
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return manager != null && isOnline(manager.getNetworkCapabilities(manager.getActiveNetwork()));
    }

    public static boolean isOnline(NetworkCapabilities capabilities) {
        if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
        // Some mobile providers do not pass Android's validation probe despite usable connectivity.
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
    }
}
