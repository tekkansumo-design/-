package com.tekkansumo.ebayscanner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

/**
 * カメラの映像を一定間隔で解析する（すべて端末内で処理、通信なし）。
 *  - バーコード（JAN / EAN / UPC / ISBN）
 *  - 文字（日本語 + 英数字）→ 値札の金額と型番
 *  - 必要なら縮小した写真（eBay の画像検索用）
 */
class FrameAnalyzer(
    private val callbackExecutor: Executor,
    private val wantImage: () -> Boolean,
    private val paused: () -> Boolean,
    private val onFrame: (Frame) -> Unit,
) : ImageAnalysis.Analyzer {

    class Frame(
        val barcodes: List<String>,
        val lines: List<PriceParser.Line>,
        val text: String,
        val jpeg: ByteArray?,
        val hash: Long?,
    )

    private val barcode = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
            )
            .build()
    )
    private val text = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    private var last = 0L
    private val intervalMs = 700L

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        val media = image.image
        if (media == null || paused() || now - last < intervalMs) {
            image.close()
            return
        }
        last = now

        var jpeg: ByteArray? = null
        var hash: Long? = null
        if (wantImage()) {
            try {
                val small = scaled(image.toBitmap(), image.imageInfo.rotationDegrees, 640)
                hash = hashOf(small)
                jpeg = ByteArrayOutputStream().also {
                    small.compress(Bitmap.CompressFormat.JPEG, 80, it)
                }.toByteArray()
            } catch (_: Exception) {
            }
        }

        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        val tb = barcode.process(input)
        val tt = text.process(input)
        Tasks.whenAllComplete(tb, tt).addOnCompleteListener(callbackExecutor) {
            image.close()
            val codes = if (tb.isSuccessful) tb.result.mapNotNull { usableCode(it.rawValue) } else emptyList()
            val lines = ArrayList<PriceParser.Line>()
            var all = ""
            if (tt.isSuccessful) {
                all = tt.result.text
                for (block in tt.result.textBlocks) for (line in block.lines) {
                    val h = line.boundingBox?.height() ?: 0
                    lines += PriceParser.Line(line.text, h)
                }
            }
            onFrame(Frame(codes.distinct(), lines, all, jpeg, hash))
        }
    }

    fun close() {
        barcode.close()
        text.close()
    }

    companion object {
        /**
         * 店舗が独自に貼るバーコード（20〜29 で始まる）や
         * 書籍の 2 段目（191/192 で始まる分類・価格コード）は商品の特定に使えないので除く。
         */
        fun usableCode(raw: String?): String? {
            val c = raw?.trim() ?: return null
            if (!c.all { it.isDigit() } || c.length !in setOf(8, 12, 13)) return null
            if (c.length == 13 && (c[0] == '2' || c.startsWith("191") || c.startsWith("192"))) return null
            return c
        }

        private fun scaled(src: Bitmap, rotation: Int, maxSide: Int): Bitmap {
            val s = maxSide.toFloat() / maxOf(src.width, src.height)
            val m = Matrix().apply {
                if (s < 1f) postScale(s, s)
                postRotate(rotation.toFloat())
            }
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }

        private fun hashOf(b: Bitmap): Long {
            val t = Bitmap.createScaledBitmap(b, 8, 8, true)
            val g = IntArray(64)
            for (y in 0 until 8) for (x in 0 until 8) {
                val p = t.getPixel(x, y)
                g[y * 8 + x] = (Color.red(p) * 30 + Color.green(p) * 59 + Color.blue(p) * 11) / 100
            }
            return ImageHash.fromGray(g)
        }
    }
}
