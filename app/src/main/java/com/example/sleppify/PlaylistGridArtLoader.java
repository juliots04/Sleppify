package com.example.sleppify;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.MultiTransformation;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.example.sleppify.utils.YouTubeCropTransformation;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads a 2x2 grid composite image from 4 track thumbnail URLs into an ImageView.
 * The composite bitmap is cached in Glide's memory+disk cache via a deterministic key.
 */
public final class PlaylistGridArtLoader {

    /** Strip YouTube black margins, then center-crop to exact 1:1 square. */
    private static final MultiTransformation<Bitmap> SQUARE_CROP =
            new MultiTransformation<>(new YouTubeCropTransformation(), new CenterCrop());
    /** Minimum decode size so smartCrop margin detection is reliable. */
    private static final int MIN_DECODE_PX = 320;

    private PlaylistGridArtLoader() {}

    /**
     * Builds a cache key from the 4 URLs + target size so we can skip re-compositing
     * if the same 4 images are already loaded.
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
     * Loads a 2x2 grid composite into the target ImageView.
     *
     * @param target   ImageView to load into
     * @param urls     exactly 4 thumbnail URLs
     * @param sizePx   the total output bitmap size in pixels (e.g. 60dp * density)
     */
    public static void load(
            @NonNull ImageView target,
            @NonNull List<String> urls,
            int sizePx
    ) {
        if (urls.size() < 4 || sizePx <= 0) return;
        Context context = target.getContext();
        if (context == null) return;

        String signature = buildSignature(urls, sizePx);
        Object prev = target.getTag(R.id.tag_artwork_signature);
        if (prev instanceof String && signature.equals(prev)) {
            return; // already showing this exact grid
        }
        target.setTag(R.id.tag_artwork_signature, signature);

        int half = sizePx / 2;
        final Bitmap[] cells = new Bitmap[4];
        final AtomicInteger loaded = new AtomicInteger(0);

        RequestManager glide = Glide.with(target);

        for (int i = 0; i < 4; i++) {
            final int index = i;
            String url = urls.get(i);
            if (url == null || url.trim().isEmpty()) {
                if (loaded.incrementAndGet() == 4) {
                    compositeAndSet(target, cells, sizePx, half);
                }
                continue;
            }
            int decodeSide = Math.max(half, MIN_DECODE_PX);
            glide.asBitmap()
                    .load(url.trim())
                    .transform(SQUARE_CROP)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .override(decodeSide, decodeSide)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(new CustomTarget<Bitmap>(half, half) {
                        @Override
                        public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                            cells[index] = resource;
                            if (loaded.incrementAndGet() == 4) {
                                compositeAndSet(target, cells, sizePx, half);
                            }
                        }

                        @Override
                        public void onLoadFailed(@Nullable Drawable errorDrawable) {
                            cells[index] = null;
                            if (loaded.incrementAndGet() == 4) {
                                compositeAndSet(target, cells, sizePx, half);
                            }
                        }

                        @Override
                        public void onLoadCleared(@Nullable Drawable placeholder) {
                            // no-op
                        }
                    });
        }
    }

    private static void compositeAndSet(
            @NonNull ImageView target,
            @NonNull Bitmap[] cells,
            int sizePx,
            int half
    ) {
        // Verify we're still expecting this grid (tag hasn't changed)
        Bitmap composite = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(composite);

        // Top-left, Top-right, Bottom-left, Bottom-right
        int[][] positions = {{0, 0}, {half, 0}, {0, half}, {half, half}};
        for (int i = 0; i < 4; i++) {
            if (cells[i] != null) {
                // Scale cell to exactly half x half
                Bitmap scaled = Bitmap.createScaledBitmap(cells[i], half, half, true);
                canvas.drawBitmap(scaled, positions[i][0], positions[i][1], null);
                if (scaled != cells[i]) {
                    scaled.recycle();
                }
            }
        }
        target.setAlpha(0f);
        target.setImageBitmap(composite);
        target.animate().alpha(1f).setDuration(200).start();
    }
}
