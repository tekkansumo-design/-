package com.tekkansumo.ebayscanner

/**
 * 8x8 の平均ハッシュ。写っているものが前回の画像検索から
 * 大きく変わったかどうかを、通信せずに判定するのに使う。
 */
object ImageHash {
    /** gray は 8x8=64 個の明るさ（0..255）。 */
    fun fromGray(gray: IntArray): Long {
        require(gray.size == 64)
        val avg = gray.average()
        var h = 0L
        for (i in 0 until 64) if (gray[i] > avg) h = h or (1L shl i)
        return h
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
