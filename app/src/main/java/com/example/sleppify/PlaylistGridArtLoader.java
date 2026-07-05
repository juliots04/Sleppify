package com.example.sleppify;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.MultiTransformation;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.request.FutureTarget;
import com.example.sleppify.utils.YouTubeCropTransformation;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Builds a 2x2 grid composite image from 1 to 4 track thumbnail URLs and loads it into an ImageView.
 *
 * The composite is built ONCE off the main thread and cached in memory (LruCache) AND on disk, keyed
 * by the source URLs + size. So a grid cover is composited a single time, persisted as one image, and
 * on every subsequent bind / re-scroll / screen re-open it paints atomically from cache — no re-decode
 * of the 4 cells and no per-bind main-thread compositing.
 */
public final class PlaylistGridArtLoader {

    /** Strip YouTube black margins, then center-crop to exact 1:1 square. */
    private static final MultiTransformation<Bitmap> SQUARE_CROP =
            new MultiTransformation<>(new YouTubeCropTransformation(), new CenterCrop());
    /** Minimum decode size so smartCrop margin detection is reliable. */
    private static final int MIN_DECODE_PX = 320;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Low-priority pool so compositing never competes with the UI thread. */
    private static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "grid-art");
        t.setDaemon(true);
        t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
        return t;
    });

    /** Byte-bounded memory cache (~1/12 of the heap). Never recycles evicted bitmaps — an evicted
     *  composite may still be attached to a visible ImageView. */
    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(
            (int) Math.min(24 * 1024L, Math.max(4 * 1024L, Runtime.getRuntime().maxMemory() / 12 / 1024L))) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };

    private PlaylistGridArtLoader() {}

    /**
     * Builds a cache key from the provided URLs + target size so we can skip re-compositing
     * if the same images are already loaded.
     */
    @NonNull
    public static String buildSignature(@NonNull List<String> urls, int sizePx) {
        StringBuilder sb = new StringBuilder();
        for (String url : urls) {
            sb.append(url == null ? "" : url.trim()).append('|');
        }
        sb.append(sizePx);
        return sb.toString();
    }

    /**
     * Loads a 2x2 grid composite into the target ImageView. Paints instantly from the memory cache
     * when warm; otherwise builds it off the main thread (disk cache first) and sets it when ready —
     * only if the ImageView hasn't since been recycled to a different grid.
     *
     * @param target   ImageView to load into
     * @param urls     1 to 4 thumbnail URLs
     * @param sizePx   the total output bitmap size in pixels (e.g. 60dp * density)
     */
    public static void load(
            @NonNull ImageView target,
            @NonNull List<String> urls,
            int sizePx
    ) {
        if (urls.isEmpty() || sizePx <= 0) return;
        Context context = target.getContext();
        if (context == null) return;

        String signature = buildSignature(urls, sizePx);
        Object prev = target.getTag(R.id.tag_artwork_signature);
        if (prev instanceof String && signature.equals(prev)) {
            return; // already showing / loading this exact grid
        }
        target.setTag(R.id.tag_artwork_signature, signature);

        Bitmap cached = MEM.get(signature);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        // Cache miss: keep whatever is currently drawn (the ImageView's placeholder background)
        // until the composite is ready — the grid is opaque and appears atomically once built.

        Context appCtx = context.getApplicationContext();
        List<String> urlsCopy = new ArrayList<>(urls);
        IO.execute(() -> {
            Bitmap bmp = getOrBuild(appCtx, signature, urlsCopy, sizePx);
            if (bmp == null) return;
            MAIN.post(() -> {
                // Guard against ViewHolder recycling: only set if this view still wants this grid.
                if (signature.equals(target.getTag(R.id.tag_artwork_signature))) {
                    target.setImageBitmap(bmp);
                }
            });
        });
    }

    private static Bitmap getOrBuild(Context ctx, String signature, List<String> urls, int sizePx) {
        Bitmap mem = MEM.get(signature);
        if (mem != null) return mem;
        try {
            File file = diskFile(ctx, signature);
            Bitmap bmp = file.exists() ? decodeDisk(file) : null;
            if (bmp == null) {
                bmp = compose(ctx, urls, sizePx);
                if (bmp != null) writeDisk(file, bmp);
            }
            if (bmp != null) MEM.put(signature, bmp);
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap compose(Context ctx, List<String> urls, int sizePx) {
        int count = Math.min(4, urls.size());
        int half = sizePx / 2;
        Bitmap[] cells = new Bitmap[4];
        List<FutureTarget<Bitmap>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                String url = urls.get(i);
                if (url == null || url.trim().isEmpty()) continue;
                int decodeSide = Math.max(half, MIN_DECODE_PX);
                try {
                    FutureTarget<Bitmap> ft = Glide.with(ctx).asBitmap()
                            .load(url.trim())
                            .transform(SQUARE_CROP)
                            .format(DecodeFormat.PREFER_RGB_565)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .submit(decodeSide, decodeSide);
                    futures.add(ft);
                    cells[i] = ft.get(12, TimeUnit.SECONDS);
                } catch (Throwable ignored) {
                    cells[i] = null;
                }
            }

            boolean any = false;
            for (Bitmap b : cells) {
                if (b != null) { any = true; break; }
            }
            if (!any) return null;

            Bitmap composite = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(composite);
            // Top-left, Top-right, Bottom-left, Bottom-right
            int[][] positions = {{0, 0}, {half, 0}, {0, half}, {half, half}};
            for (int i = 0; i < 4; i++) {
                if (cells[i] != null) {
                    Bitmap scaled = Bitmap.createScaledBitmap(cells[i], half, half, true);
                    canvas.drawBitmap(scaled, positions[i][0], positions[i][1], null);
                    if (scaled != cells[i]) {
                        scaled.recycle();
                    }
                }
            }
            return composite;
        } finally {
            // Release the loaded cells back to Glide's bitmap pool.
            for (FutureTarget<Bitmap> ft : futures) {
                try { Glide.with(ctx).clear(ft); } catch (Throwable ignored) {}
            }
        }
    }

    private static Bitmap decodeDisk(File file) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeDisk(File file, Bitmap bmp) {
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                // Opaque grid -> JPEG keeps the file small.
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
        } catch (Throwable t) {
            try { file.delete(); } catch (Throwable ignored) {}
        }
    }

    private static File diskFile(Context ctx, String signature) {
        File dir = new File(ctx.getCacheDir(), "grid_art");
        dir.mkdirs();
        return new File(dir, md5(signature) + ".jpg");
    }

    private static String md5(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes());
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
