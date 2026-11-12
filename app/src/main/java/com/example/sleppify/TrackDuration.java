package com.example.sleppify;

/** Shared parser for playback, playlist totals and offline-file validation. */
public final class TrackDuration {
    private TrackDuration() { }

    public static int parseSeconds(String label, int unknown) {
        if (label == null) return unknown;
        String[] parts = label.trim().split(":", -1);
        if (parts.length < 2 || parts.length > 3) return unknown;
        long seconds = 0;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) return unknown;
            int component = 0;
            for (int j = 0; j < parts[i].length(); j++) {
                char digit = parts[i].charAt(j);
                if (digit < '0' || digit > '9' || component > Integer.MAX_VALUE / 10) return unknown;
                long next = (long) component * 10 + digit - '0';
                if (next > Integer.MAX_VALUE) return unknown;
                component = (int) next;
            }
            if (i > 0 && component >= 60) return unknown;
            seconds = seconds * 60 + component;
            if (seconds > Integer.MAX_VALUE) return unknown;
        }
        return (int) seconds;
    }
}
