package com.s23ultra.focusstack

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object FocusStackProcessor {

    private const val PYRAMID_LEVELS = 5

    suspend fun processStack(
        bitmaps: List<Bitmap>,
        onProgress: (String) -> Unit
    ): Bitmap = withContext(Dispatchers.Default) {
        require(bitmaps.isNotEmpty()) { "Kare listesi bos!" }
        if (bitmaps.size == 1) return@withContext bitmaps.first()

        val width = bitmaps[0].width
        val height = bitmaps[0].height
        val numImages = bitmaps.size

        onProgress("1/4: Focus Breathing ve hizalama hesaplaniyor...")
        val refIndex = numImages / 2
        val refGray = toGrayscaleFloat(bitmaps[refIndex], width, height)

        val alignedBitmaps = Array<Bitmap>(numImages) { bitmaps[it] }
        for (i in 0 until numImages) {
            if (i == refIndex) continue
            onProgress("1/4: Kare ${i + 1}/$numImages hizalaniyor (Olcek + Kayma)...")
            alignedBitmaps[i] = alignToReference(bitmaps[i], refGray, width, height)
        }

        onProgress("2/4: Keskinlik (Laplacian Energy) haritalari cikariliyor...")
        val weights = Array(numImages) { FloatArray(width * height) }
        (0 until numImages).map { i ->
            async {
                val gray = toGrayscaleFloat(alignedBitmaps[i], width, height)
                weights[i] = computeSharpnessMap(gray, width, height)
            }
        }.awaitAll()

        // Her piksel icin en keskin kareyi secip yumusak gecis maskesi olustur
        val winnerMask = Array(numImages) { FloatArray(width * height) }
        for (p in 0 until width * height) {
            var bestImg = 0
            var maxW = -1f
            for (i in 0 until numImages) {
                if (weights[i][p] > maxW) {
                    maxW = weights[i][p]
                    bestImg = i
                }
            }
            winnerMask[bestImg][p] = 1f
        }

        onProgress("3/4: 5 Katmanli Laplacian Piramit harmanlama yapiliyor...")
        val weightPyramids = Array(numImages) { i ->
            buildGaussianPyramid(winnerMask[i], width, height, PYRAMID_LEVELS)
        }

        val outR = FloatArray(width * height)
        val outG = FloatArray(width * height)
        val outB = FloatArray(width * height)

        // Kanal bazli (R, G, B) Laplacian Piramit birlestirme (Bellek dostu akis)
        val channels = listOf(16, 8, 0) // R, G, B bit shifts
        val outChannels = listOf(outR, outG, outB)

        for (cIndex in 0..2) {
            val shift = channels[cIndex]
            val blendedLapPyramid = Array(PYRAMID_LEVELS) { level ->
                val lw = getLevelDim(width, level)
                val lh = getLevelDim(height, level)
                FloatArray(lw * lh)
            }

            for (i in 0 until numImages) {
                val channelData = extractChannel(alignedBitmaps[i], width, height, shift)
                val imgLapPyr = buildLaplacianPyramid(channelData, width, height, PYRAMID_LEVELS)

                for (lvl in 0 until PYRAMID_LEVELS) {
                    val lap = imgLapPyr[lvl]
                    val wPyr = weightPyramids[i][lvl]
                    val accum = blendedLapPyramid[lvl]
                    for (idx in accum.indices) {
                        accum[idx] += lap[idx] * wPyr[idx]
                    }
                }
            }

            // Agirlik toplamlariyla normalize et ve piramidi yeniden insa et
            for (lvl in 0 until PYRAMID_LEVELS) {
                val accum = blendedLapPyramid[lvl]
                for (idx in accum.indices) {
                    var wSum = 0f
                    for (i in 0 until numImages) wSum += weightPyramids[i][lvl][idx]
                    if (wSum > 1e-5f) accum[idx] /= wSum
                }
            }

            val reconstructed = collapseLaplacianPyramid(blendedLapPyramid, width, height, PYRAMID_LEVELS)
            System.arraycopy(reconstructed, 0, outChannels[cIndex], 0, width * height)
        }

        onProgress("4/4: Son goruntu olusturuluyor...")
        val finalPixels = IntArray(width * height)
        for (p in 0 until width * height) {
            val r = outR[p].toInt().coerceIn(0, 255)
            val g = outG[p].toInt().coerceIn(0, 255)
            val b = outB[p].toInt().coerceIn(0, 255)
            finalPixels[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(finalPixels, 0, width, 0, 0, width, height)
        result
    }

    // Focus Breathing (Olcek) + X/Y Kayma Hizalama
    private fun alignToReference(src: Bitmap, refGray: FloatArray, width: Int, height: Int): Bitmap {
        val ds = 4 // Hizli arama icin 1/4 alt ornekleme
        val sw = width / ds
        val sh = height / ds
        val refSmall = downsampleGray(refGray, width, height, sw, sh)
        val srcGray = toGrayscaleFloat(src, width, height)
        val srcSmall = downsampleGray(srcGray, width, height, sw, sh)

        var bestScale = 1.0f
        var bestDx = 0f
        var bestDy = 0f
        var minError = Float.MAX_VALUE

        // Odak nefesi (%96 ile %104 arasi olcek) ve X/Y kayma taramasi
        val scales = floatArrayOf(0.975f, 0.985f, 0.992f, 0.996f, 1.0f, 1.004f, 1.008f, 1.015f, 1.025f)
        for (scale in scales) {
            for (dy in -6..6 step 2) {
                for (dx in -6..6 step 2) {
                    val err = evaluateTransformError(refSmall, srcSmall, sw, sh, scale, dx.toFloat(), dy.toFloat())
                    if (err < minError) {
                        minError = err
                        bestScale = scale
                        bestDx = dx.toFloat()
                        bestDy = dy.toFloat()
                    }
                }
            }
        }

        // Ince (Sub-pixel) arama
        for (sDelta in floatArrayOf(-0.002f, 0f, 0.002f)) {
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val s = bestScale + sDelta
                    val tx = bestDx + dx
                    val ty = bestDy + dy
                    val err = evaluateTransformError(refSmall, srcSmall, sw, sh, s, tx, ty)
                    if (err < minError) {
                        minError = err
                        bestScale = s
                        bestDx = tx
                        bestDy = ty
                    }
                }
            }
        }

        if (bestScale == 1.0f && bestDx == 0f && bestDy == 0f) return src

        val aligned = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(aligned)
        val matrix = Matrix().apply {
            postScale(bestScale, bestScale, width / 2f, height / 2f)
            postTranslate(bestDx * ds, bestDy * ds)
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, matrix, paint)
        return aligned
    }

    private fun evaluateTransformError(
        ref: FloatArray, src: FloatArray, w: Int, h: Int,
        scale: Float, dx: Float, dy: Float
    ): Float {
        val cx = w / 2f
        val cy = h / 2f
        val marginX = w / 6
        val marginY = h / 6
        var err = 0f
        var count = 0
        for (y in marginY until (h - marginY) step 2) {
            val sy = ((y - cy - dy) / scale + cy).toInt()
            if (sy !in 0 until h) continue
            for (x in marginX until (w - marginX) step 2) {
                val sx = ((x - cx - dx) / scale + cx).toInt()
                if (sx !in 0 until w) continue
                err += abs(ref[y * w + x] - src[sy * w + sx])
                count++
            }
        }
        return if (count > 0) err / count else Float.MAX_VALUE
    }

    private fun computeSharpnessMap(gray: FloatArray, w: Int, h: Int): FloatArray {
        // Gürültüyü (ISO noise) keskinlik sanmamak için önce hafif Gaussian yumuşatma
        val smoothed = gaussianBlur5x5(gray, w, h)
        val lapEnergy = FloatArray(w * h)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val c = smoothed[idx] * -8f +
                        smoothed[idx - 1] + smoothed[idx + 1] +
                        smoothed[idx - w] + smoothed[idx + w] +
                        smoothed[idx - w - 1] + smoothed[idx - w + 1] +
                        smoothed[idx + w - 1] + smoothed[idx + w + 1]
                lapEnergy[idx] = c * c
            }
        }
        // Lokal enerjiyi pencere icinde yumusatarak puruzsuz secim haritasi uret
        return gaussianBlur5x5(lapEnergy, w, h)
    }

    private fun buildGaussianPyramid(base: FloatArray, w: Int, h: Int, levels: Int): Array<FloatArray> {
        val pyr = Array(levels) { FloatArray(0) }
        pyr[0] = gaussianBlur5x5(base, w, h)
        var cw = w
        var ch = h
        for (lvl in 1 until levels) {
            val nw = getLevelDim(w, lvl)
            val nh = getLevelDim(h, lvl)
            val blurred = gaussianBlur5x5(pyr[lvl - 1], cw, ch)
            pyr[lvl] = downsample2x(blurred, cw, ch, nw, nh)
            cw = nw
            ch = nh
        }
        return pyr
    }

    private fun buildLaplacianPyramid(base: FloatArray, w: Int, h: Int, levels: Int): Array<FloatArray> {
        val gauss = Array(levels) { FloatArray(0) }
        gauss[0] = base
        var cw = w
        var ch = h
        for (lvl in 1 until levels) {
            val nw = getLevelDim(w, lvl)
            val nh = getLevelDim(h, lvl)
            val blurred = gaussianBlur5x5(gauss[lvl - 1], cw, ch)
            gauss[lvl] = downsample2x(blurred, cw, ch, nw, nh)
            cw = nw
            ch = nh
        }

        val lap = Array(levels) { FloatArray(0) }
        for (lvl in 0 until levels - 1) {
            val lw = getLevelDim(w, lvl)
            val lh = getLevelDim(h, lvl)
            val nw = getLevelDim(w, lvl + 1)
            val nh = getLevelDim(h, lvl + 1)
            val up = upsample2x(gauss[lvl + 1], nw, nh, lw, lh)
            val cur = gauss[lvl]
            val diff = FloatArray(lw * lh)
            for (i in diff.indices) diff[i] = cur[i] - up[i]
            lap[lvl] = diff
        }
        lap[levels - 1] = gauss[levels - 1]
        return lap
    }

    private fun collapseLaplacianPyramid(lap: Array<FloatArray>, w: Int, h: Int, levels: Int): FloatArray {
        var cur = lap[levels - 1]
        for (lvl in levels - 2 downTo 0) {
            val nw = getLevelDim(w, lvl + 1)
            val nh = getLevelDim(h, lvl + 1)
            val lw = getLevelDim(w, lvl)
            val lh = getLevelDim(h, lvl)
            val up = upsample2x(cur, nw, nh, lw, lh)
            val next = FloatArray(lw * lh)
            val lLayer = lap[lvl]
            for (i in next.indices) next[i] = lLayer[i] + up[i]
            cur = next
        }
        return cur
    }

    private fun gaussianBlur5x5(src: FloatArray, w: Int, h: Int): FloatArray {
        val temp = FloatArray(w * h)
        val dst = FloatArray(w * h)
        // [1, 4, 6, 4, 1] / 16 ayrilabilir çekirdek
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val xm2 = max(0, x - 2)
                val xm1 = max(0, x - 1)
                val xp1 = min(w - 1, x + 1)
                val xp2 = min(w - 1, x + 2)
                temp[row + x] = (src[row + xm2] + 4f * src[row + xm1] + 6f * src[row + x] +
                        4f * src[row + xp1] + src[row + xp2]) * 0.0625f
            }
        }
        for (y in 0 until h) {
            val ym2 = max(0, y - 2) * w
            val ym1 = max(0, y - 1) * w
            val y0 = y * w
            val yp1 = min(h - 1, y + 1) * w
            val yp2 = min(h - 1, y + 2) * w
            for (x in 0 until w) {
                dst[y0 + x] = (temp[ym2 + x] + 4f * temp[ym1 + x] + 6f * temp[y0 + x] +
                        4f * temp[yp1 + x] + temp[yp2 + x]) * 0.0625f
            }
        }
        return dst
    }

    private fun downsample2x(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val dst = FloatArray(dw * dh)
        for (y in 0 until dh) {
            val sy = min(sh - 1, y * 2)
            for (x in 0 until dw) {
                val sx = min(sw - 1, x * 2)
                dst[y * dw + x] = src[sy * sw + sx]
            }
        }
        return dst
    }

    private fun upsample2x(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val dst = FloatArray(dw * dh)
        for (y in 0 until dh) {
            val fy = y * 0.5f
            val y0 = fy.toInt().coerceIn(0, sh - 1)
            val y1 = min(sh - 1, y0 + 1)
            val wy = fy - y0
            for (x in 0 until dw) {
                val fx = x * 0.5f
                val x0 = fx.toInt().coerceIn(0, sw - 1)
                val x1 = min(sw - 1, x0 + 1)
                val wx = fx - x0
                val top = src[y0 * sw + x0] * (1f - wx) + src[y0 * sw + x1] * wx
                val bot = src[y1 * sw + x0] * (1f - wx) + src[y1 * sw + x1] * wx
                dst[y * dw + x] = top * (1f - wy) + bot * wy
            }
        }
        return dst
    }

    private fun getLevelDim(dim: Int, level: Int): Int {
        var d = dim
        repeat(level) { d = max(2, (d + 1) / 2) }
        return d
    }

    private fun toGrayscaleFloat(bmp: Bitmap, w: Int, h: Int): FloatArray {
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return gray
    }

    private fun extractChannel(bmp: Bitmap, w: Int, h: Int, shift: Int): FloatArray {
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatArray(w * h)
        for (i in pixels.indices) {
            out[i] = ((pixels[i] shr shift) and 0xFF).toFloat()
        }
        return out
    }

    private fun downsampleGray(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val dst = FloatArray(dw * dh)
        val scaleX = sw.toFloat() / dw
        val scaleY = sh.toFloat() / dh
        for (y in 0 until dh) {
            val sy = (y * scaleY).toInt().coerceIn(0, sh - 1)
            for (x in 0 until dw) {
                val sx = (x * scaleX).toInt().coerceIn(0, sw - 1)
                dst[y * dw + x] = src[sy * sw + sx]
            }
        }
        return dst
    }
}
