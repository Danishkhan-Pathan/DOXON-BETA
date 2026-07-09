package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream

object MediaUtils {
    /**
     * Compresses an image Uri from gallery/camera locally to a maximum resolution and quality.
     * Integrates real-time Multi-Modal Payload Threat scanning to quarantine malicious attachments.
     */
    fun compressImageUri(context: Context, uri: Uri, maxSide: Int = 1024, quality: Int = 75): String? {
        // Enforce sandboxed Multi-Modal Payload scan
        if (!SecurityFirewall.scanPayloadDetail(context, uri)) {
            SelfHealingMonitor.logEvent("QUARANTINE CRITICAL: Suspected script injection or macro exploit found inside media binary at $uri. Instantly isolated and deleted.")
            return null
        }

        return try {
            val contentResolver = context.contentResolver
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream) ?: return null
            inputStream?.close()

            val width = originalBitmap.width
            val height = originalBitmap.height
            val scaledBitmap = if (width > maxSide || height > maxSide) {
                val ratio = width.toFloat() / height.toFloat()
                val (newWidth, newHeight) = if (width > height) {
                    maxSide to (maxSide / ratio).toInt()
                } else {
                    (maxSide * ratio).toInt() to maxSide
                }
                Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)
            } else {
                originalBitmap
            }

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val bytes = outputStream.toByteArray()
            
            if (scaledBitmap != originalBitmap) {
                scaledBitmap.recycle()
            }
            originalBitmap.recycle()

            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }
}
