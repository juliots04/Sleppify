package com.example.sleppify;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * An ImageView whose {@link #requestLayout()} becomes a no-op once it has been laid out.
 *
 * <p>Used for fixed-size thumbnails inside the library's RecyclerView, which lives in a
 * {@code wrap_content} NestedScrollView and therefore does NOT recycle — every row is measured and
 * laid out together. {@code ImageView.setImageDrawable} calls {@code requestLayout()} on every
 * drawable swap, and each async cover that Glide (or PlaylistGridArtLoader) delivers propagated that
 * request up through the non-recycling list, re-measuring/re-laying-out ALL rows once per image.
 * With 20-40 covers trickling in over the first seconds after entry, that was dozens of full-tree
 * layout passes — the "laggy for a few seconds, then stabilizes once covers are cached" symptom.
 *
 * <p>These thumbnails are a fixed size (a match_parent child of a fixed 50x50dp frame), so a new
 * drawable can never change the view's bounds. Suppressing requestLayout after the first layout is
 * therefore visually identical and safe — Glide already decodes to a bucketed override() size.
 */
public class FixedSizeImageView extends AppCompatImageView {

    private boolean blockLayout;

    public FixedSizeImageView(Context context) {
        super(context);
    }

    public FixedSizeImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FixedSizeImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void requestLayout() {
        if (blockLayout) {
            return;
        }
        super.requestLayout();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // Laid out at its fixed bounds — image swaps from here on never change our size.
        blockLayout = true;
    }

    @Override
    public void setLayoutParams(android.view.ViewGroup.LayoutParams params) {
        // A real size change (rare for these fixed thumbs) must be honored — re-arm layout.
        blockLayout = false;
        super.setLayoutParams(params);
    }
}
