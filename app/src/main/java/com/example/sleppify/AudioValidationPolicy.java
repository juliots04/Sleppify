package com.example.sleppify;

/** File validation rules shared by playback scans and download workers. */
public final class AudioValidationPolicy {
    public static final long MIN_BYTES = 24L * 1024L;
    public enum Result { VALID, INVALID, UNREADABLE }
    private AudioValidationPolicy() { }

    public static Result evaluate(long bytes, int durationSeconds, int audioTracks,
                                  int channels, int expectedSeconds) {
        if (bytes < MIN_BYTES) return Result.INVALID;
        if (audioTracks == 0 || channels == 0) return Result.INVALID;
        if (durationSeconds < 0 || audioTracks < 0) return Result.UNREADABLE;
        int minimumSeconds = expectedSeconds <= 0 ? 6
                : Math.max(6, Math.round(expectedSeconds * 0.88f) - 8);
        return durationSeconds >= minimumSeconds ? Result.VALID : Result.INVALID;
    }
}
