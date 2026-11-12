package com.example.sleppify;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.work.*;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** One download policy for the player, search, library and playlist detail. */
public final class OfflineDownloadScheduler {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastCleanupMs;

    private OfflineDownloadScheduler() { }

    public interface Callback { void onComplete(boolean enqueued); }

    public static final class Request {
        private final java.util.List<String> tags = new java.util.ArrayList<>();
        private final OfflineDownloadManifest manifest;
        private final String playlistId, playlistTitle;
        private final int alreadyOffline, total;
        private final boolean userInitiated, manual;

        public Request(String playlistId, String playlistTitle, String[] ids, String[] titles,
                       String[] artists, String[] durations, int alreadyOffline, int total,
                       boolean userInitiated, boolean manual) {
            this.manifest = new OfflineDownloadManifest(ids, titles, artists, durations);
            this.playlistId = playlistId == null ? "" : playlistId;
            this.playlistTitle = playlistTitle == null ? "Playlist" : playlistTitle;
            this.alreadyOffline = alreadyOffline;
            this.total = total;
            this.userInitiated = userInitiated;
            this.manual = manual;
        }

        public Request addTag(String tag) { tags.add(tag); return this; }
    }

    static File directory(Context context) { return new File(context.getFilesDir(), "offline_download_jobs"); }

    private static void cleanFinishedPayloads(Context context) throws Exception {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastCleanupMs != 0 && now - lastCleanupMs < TimeUnit.MINUTES.toMillis(2)) return;
        lastCleanupMs = now;
        File[] files = directory(context).listFiles((dir, name) -> name.endsWith(".tracks"));
        if (files == null) return;
        for (File file : files) {
            String token = file.getName();
            try {
                java.util.UUID id = java.util.UUID.fromString(token.substring(0, token.length() - 7));
                WorkInfo info = WorkManager.getInstance(context).getWorkInfoById(id).get();
                if (info != null && info.getState().isFinished()) OfflineDownloadManifest.delete(directory(context), token);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    public static void enqueue(Context context, Request request, String uniqueName) {
        enqueue(context, request, uniqueName, request.tags.toArray(new String[0]));
    }

    public static void enqueue(Context context, Request request, String uniqueName, Callback callback) {
        enqueue(context, request, uniqueName, request.tags.toArray(new String[0]), callback);
    }

    public static void enqueue(Context context, Request request, String uniqueName, String[] tags) {
        enqueue(context, request, uniqueName, tags, null);
    }

    public static void enqueue(Context context, Request request, String uniqueName, String[] tags, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            String token = null;
            boolean enqueued = false;
            try {
                cleanFinishedPayloads(app);
                if (request.manifest.ids.length == 0) throw new IllegalArgumentException("Empty download");
                token = request.manifest.write(directory(app));
                Data input = new Data.Builder()
                        .putString(OfflinePlaylistDownloadWorker.INPUT_MANIFEST, token)
                        .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_ID, request.playlistId)
                        .putString(OfflinePlaylistDownloadWorker.INPUT_PLAYLIST_TITLE,
                                request.playlistTitle.substring(0, Math.min(256, request.playlistTitle.length())))
                        .putInt(OfflinePlaylistDownloadWorker.INPUT_ALREADY_OFFLINE_COUNT, request.alreadyOffline)
                        .putInt(OfflinePlaylistDownloadWorker.INPUT_TOTAL_WITH_VIDEO_ID, request.total)
                        .putBoolean(OfflinePlaylistDownloadWorker.INPUT_USER_INITIATED, request.userInitiated)
                        .putBoolean(OfflinePlaylistDownloadWorker.INPUT_MANUAL_QUEUE, request.manual)
                        .build();
                boolean allowMobile = app.getSharedPreferences(CloudSyncManager.PREFS_SETTINGS, Context.MODE_PRIVATE)
                        .getBoolean(CloudSyncManager.KEY_OFFLINE_DOWNLOAD_ALLOW_MOBILE_DATA, false);
                OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(OfflinePlaylistDownloadWorker.class)
                        .setId(java.util.UUID.fromString(token.substring(0, token.length() - 7)))
                        .setInputData(input)
                        .setConstraints(new Constraints.Builder().setRequiredNetworkType(
                                allowMobile ? NetworkType.CONNECTED : NetworkType.UNMETERED).build())
                        .addTag(uniqueName);
                if (request.manual) builder.setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS);
                for (String tag : tags) if (tag != null && !tag.isEmpty()) builder.addTag(tag);
                WorkManager.getInstance(app).enqueueUniqueWork(uniqueName, ExistingWorkPolicy.APPEND_OR_REPLACE,
                        builder.build()).getResult().get();
                enqueued = true;
            } catch (Exception error) {
                if (token != null) OfflineDownloadManifest.delete(directory(app), token);
                Log.e("OfflineDownloads", "Cannot enqueue download", error);
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            boolean result = enqueued;
            MAIN.post(() -> {
                if (!result && request.userInitiated) Toast.makeText(app,
                        "No se pudo iniciar la descarga. Inténtalo de nuevo.", Toast.LENGTH_LONG).show();
                if (callback != null) callback.onComplete(result);
            });
        });
    }
}
