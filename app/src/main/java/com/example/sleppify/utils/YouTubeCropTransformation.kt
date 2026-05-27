package com.example.sleppify.utils

import android.graphics.Bitmap
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

class YouTubeCropTransformation : BitmapTransformation() {
    
    companion object {
        /** Bumped when [YouTubeImageProcessor.smartCrop] output semantics change (invalidates Glide disk cache). */
        private const val ID = "com.example.sleppify.utils.YouTubeCropTransformation.v3-non-square-safe-margins"
        private val ID_BYTES = ID.toByteArray(CHARSET)
    }

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        return YouTubeImageProcessor.smartCrop(toTransform) ?: toTransform
    }

    override fun equals(other: Any?): Boolean {
        return other is YouTubeCropTransformation
    }

    override fun hashCode(): Int {
        return ID.hashCode()
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID_BYTES)
    }
}
