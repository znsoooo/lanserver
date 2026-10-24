package com.example.lanserver

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

object QrUtils {

    /** 生成指定像素尺寸的二维码 Bitmap；失败时返回 1x1 占位图。 */
    fun gen(text: String, px: Int): Bitmap {
        return try {
            val matrix: BitMatrix =
                MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, px, px)
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.RGB_565)
            for (y in 0 until px) {
                for (x in 0 until px) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
        }
    }
}
