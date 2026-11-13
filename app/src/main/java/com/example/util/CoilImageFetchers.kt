package com.example.util

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import java.util.zip.ZipInputStream

/**
 * Custom Coil Fetcher for `demo-page://mangaKey/chKey/pageIndex`
 * Renders crisp comic/manga panel pages onto an offscreen Canvas so Demo Mode
 * demonstrates real multi-page reading, zooming, webtoon scrolling, and downloads.
 */
class DemoPageFetcher(
    private val uri: Uri,
    private val context: Context
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val segments = uri.pathSegments
        val mangaKey = uri.host ?: "sample"
        val chapter = segments.getOrElse(0) { "ch_1" }.replace("_", " ").uppercase()
        val pageIndex = segments.getOrElse(1) { "0" }.toIntOrNull() ?: 0

        val width = 900
        val height = 1350
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Cream / dark paper tint depending on title
        val isCyber = mangaKey.contains("neon")
        val isSpace = mangaKey.contains("perihelion")
        val paperBg = when {
            isCyber -> Color.rgb(18, 22, 34)
            isSpace -> Color.rgb(12, 16, 26)
            else -> Color.rgb(245, 241, 233)
        }
        val inkColor = if (isCyber || isSpace) Color.rgb(238, 241, 248) else Color.rgb(24, 26, 34)
        val accentColor = when {
            isCyber -> Color.rgb(225, 29, 72)
            isSpace -> Color.rgb(245, 158, 11)
            else -> Color.rgb(16, 185, 129)
        }

        canvas.drawColor(paperBg)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = inkColor
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = accentColor
            alpha = 42
        }

        // Draw 3 stylized comic koma panels with gutters
        val panels = listOf(
            RectF(56f, 70f, 844f, 430f),
            RectF(56f, 462f, 480f, 880f),
            RectF(512f, 462f, 844f, 880f),
            RectF(56f, 912f, 844f, 1250f)
        )
        panels.forEachIndexed { idx, rect ->
            if ((idx + pageIndex) % 2 == 0) {
                canvas.drawRect(rect, fillPaint)
            }
            // Speed lines or horizon motif inside panel
            val motifPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                alpha = 95
                strokeWidth = 3f
            }
            val cy = rect.centerY()
            val cx = rect.centerX()
            for (line in -4..4) {
                canvas.drawLine(
                    rect.left + 24f,
                    cy + line * 18f,
                    rect.right - 24f,
                    cy - line * 10f,
                    motifPaint
                )
            }
            canvas.drawCircle(cx, cy, 48f + (pageIndex * 7f % 40f), motifPaint)
            canvas.drawRect(rect, borderPaint)
        }

        // Speech bubble in top panel
        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isCyber || isSpace) Color.rgb(28, 34, 52) else Color.WHITE
            style = Paint.Style.FILL
        }
        val bubbleRect = RectF(100f, 110f, 520f, 230f)
        canvas.drawRoundRect(bubbleRect, 48f, 48f, bubblePaint)
        canvas.drawRoundRect(bubbleRect, 48f, 48f, borderPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            textSize = 32f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("DEMO SAMPLE • PAGE ${pageIndex + 1}", 132f, 165f, textPaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = 24f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText("$mangaKey / $chapter", 132f, 205f, subPaint)

        // Footer page marker
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            alpha = 160
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("— ${pageIndex + 1} / 8 —", width / 2f, 1308f, footerPaint)

        return DrawableResult(
            drawable = BitmapDrawable(context.resources, bitmap),
            isSampled = false,
            dataSource = DataSource.MEMORY
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            return if (data.scheme == "demo-page") DemoPageFetcher(data, context) else null
        }
    }
}

/**
 * Custom Coil Fetcher for `cbz://<encodedTreeOrFileUri>/<encodedEntryName>`
 * Extracts a single image entry cleanly from a local CBZ/ZIP archive via ContentResolver.
 */
class CbzEntryFetcher(
    private val uri: Uri,
    private val context: Context
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val archiveUriStr = Uri.decode(uri.host.orEmpty())
        val entryPath = Uri.decode(uri.path?.removePrefix("/").orEmpty())
        val archiveUri = Uri.parse(archiveUriStr)

        var decoded: Bitmap? = null
        context.contentResolver.openInputStream(archiveUri)?.use { stream ->
            ZipInputStream(stream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == entryPath) {
                        val bytes = zis.readBytes()
                        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
                        val sampleOpts = BitmapFactory.Options().apply {
                            inSampleSize = calculateInSampleSize(boundsOpts, 1600, 2400)
                        }
                        decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampleOpts)
                        break
                    }
                    entry = zis.nextEntry
                }
            }
        }

        val finalBitmap = decoded ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return DrawableResult(
            drawable = BitmapDrawable(context.resources, finalBitmap),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            return if (data.scheme == "cbz") CbzEntryFetcher(data, context) else null
        }
    }
}
