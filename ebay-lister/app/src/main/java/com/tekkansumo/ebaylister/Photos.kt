package com.tekkansumo.ebaylister

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/** 出品用の写真をアプリ内に保存・管理する。 */
class Photos(private val context: Context) {
    private val dir = File(context.filesDir, "photos").apply { mkdirs() }

    fun list(): List<File> =
        (dir.listFiles { f -> f.name.endsWith(".jpg") } ?: emptyArray()).sortedBy { it.name }

    /** 端末の画像（カメラ・ギャラリー）を、向きを直し長辺 [MAX_SIDE]px に縮めて保存する。 */
    fun add(uri: Uri) {
        if (list().size >= MAX_PHOTOS) return
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
        val rotation = runCatching {
            ExifInterface(bytes.inputStream()).rotationDegrees
        }.getOrDefault(0)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return

        val bmp = scaleAndRotate(decoded, MAX_SIDE, rotation)
        val out = File(dir, "${System.currentTimeMillis()}.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    }

    fun remove(name: String) {
        File(dir, File(name).name).delete()
    }

    fun clear() = list().forEach { it.delete() }

    /** UI に渡すサムネイル（data URL） */
    fun thumbnail(file: File): String {
        val src = BitmapFactory.decodeFile(file.absolutePath) ?: return ""
        val bmp = scaleAndRotate(src, 320, 0)
        val buf = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 75, buf)
        return "data:image/jpeg;base64," + Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP)
    }

    private fun scaleAndRotate(src: Bitmap, maxSide: Int, rotation: Int): Bitmap {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        if (scale >= 1f && rotation == 0) return src
        val m = Matrix().apply {
            postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    companion object {
        const val MAX_SIDE = 1600
        const val MAX_PHOTOS = 12
    }
}
