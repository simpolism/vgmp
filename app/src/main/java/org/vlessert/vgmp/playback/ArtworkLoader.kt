package org.vlessert.vgmp.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

object ArtworkLoader {
    fun load(context: Context, ref: ArtworkRef, maxDimension: Int = 768): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        withInput(context, ref) { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension ||
            bounds.outHeight / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return withInput(context, ref) { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun <T> withInput(context: Context, ref: ArtworkRef, block: (InputStream) -> T): T? =
        runCatching {
            if (ref.archiveEntry != null) {
                ZipArchiveStore(context).withEntryInputStream(ref.uri, ref.archiveEntry, block)
            } else {
                context.contentResolver.openInputStream(ref.uri)?.use(block)
            }
        }.getOrNull()
}
