package com.example.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.CalibrationConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class MinigameAnalysisResult(
    val isMinigameActive: Boolean,
    val fishX: Float?,
    val barX: Float?,
    val barWidth: Float,
    val isFishInsideBar: Boolean,
    val catchProgress: Float,
    val isCatchReady: Boolean,
    val confidence: Float
)

/**
 * High-speed Bitmap-based visual analyzer for Gridmere's "CATCH THE FISH" minigame bar.
 * Reuses internal pixel buffers to minimize GC latency during 20Hz+ closed-loop tracking.
 */
class MinigameAnalyzer {

    private var trackPixelBuffer = IntArray(0)
    private var stripPixelBuffer = IntArray(0)

    fun analyze(bitmap: Bitmap, config: CalibrationConfig): MinigameAnalysisResult {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            return emptyResult()
        }

        val rect = config.minigameBarRegion.clamped()
        val xStart = (rect.left * width).toInt().coerceIn(0, width - 2)
        val xEnd = (rect.right * width).toInt().coerceIn(xStart + 1, width - 1)
        val trackTop = (rect.top * height + (rect.bottom - rect.top) * height * 0.32f)
            .toInt()
            .coerceIn(0, height - 2)
        val trackBottom = (rect.top * height + (rect.bottom - rect.top) * height * 0.84f)
            .toInt()
            .coerceIn(trackTop + 1, height - 1)

        val regionWidth = xEnd - xStart + 1
        val regionHeight = trackBottom - trackTop + 1
        if (regionWidth < 16 || regionHeight < 6) {
            return emptyResult()
        }

        val requiredTrackSize = regionWidth * regionHeight
        if (trackPixelBuffer.size < requiredTrackSize) {
            trackPixelBuffer = IntArray(requiredTrackSize)
        }
        val pixels = trackPixelBuffer
        bitmap.getPixels(pixels, 0, regionWidth, xStart, trackTop, regionWidth, regionHeight)

        val colStep = max(1, regionWidth / 110)
        val rowStep = max(1, regionHeight / 14)

        val sampleCols = (regionWidth / colStep) + 1
        val boxScores = FloatArray(sampleCols)
        val darkFishScores = FloatArray(sampleCols)
        val brightFishScores = FloatArray(sampleCols)
        val trackColScores = FloatArray(sampleCols)
        var trackMatchingSamples = 0
        var totalSamples = 0

        var colIndex = 0
        var px = 0
        while (px < regionWidth && colIndex < sampleCols) {
            var boxCount = 0
            var darkFishCount = 0
            var brightFishCount = 0
            var trackCount = 0
            var colSamples = 0

            var py = 0
            while (py < regionHeight) {
                val pixel = pixels[py * regionWidth + px]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                val isTrackBlue = b > 130 && g in 60..215 && r < 110 && b > r + 38
                val isGreenBox = g > 130 && g > r + 25 && g > b + 20
                val isYellowBox = g > 135 && r > 125 && g > b + 45 && abs(r - g) <= 60
                val isBoxPixel = isGreenBox || isYellowBox
                val isDarkFish = r < 78 && g < 100 && b in 35..160 && (r + g) < 165
                val isBrightOverlapFish = g > 165 && r < 145 && b < 105 && (g - b) > 85

                if (isBoxPixel) boxCount++
                if (isDarkFish) darkFishCount++
                if (isBrightOverlapFish) brightFishCount++
                if (isTrackBlue || isBoxPixel || isDarkFish || isBrightOverlapFish) {
                    trackCount++
                    trackMatchingSamples++
                }

                colSamples++
                totalSamples++
                py += rowStep
            }

            if (colSamples > 0) {
                boxScores[colIndex] = boxCount.toFloat() / colSamples
                darkFishScores[colIndex] = darkFishCount.toFloat() / colSamples
                brightFishScores[colIndex] = brightFishCount.toFloat() / colSamples
                trackColScores[colIndex] = trackCount.toFloat() / colSamples
            }
            colIndex++
            px += colStep
        }

        if (totalSamples == 0 || colIndex == 0) {
            return emptyResult()
        }

        val trackCoverage = trackMatchingSamples.toFloat() / totalSamples

        // Group contiguous columns belonging to the green/yellow movable box
        var bestStart = -1
        var bestEnd = -1
        var bestEnergy = 0f

        var curStart = -1
        var curEnergy = 0f
        var gapTolerance = 0

        for (i in 0 until colIndex) {
            val s = boxScores[i]
            if (s >= 0.11f) {
                if (curStart == -1) {
                    curStart = i
                    curEnergy = 0f
                }
                curEnergy += s
                gapTolerance = 0
            } else if (curStart != -1) {
                gapTolerance++
                if (gapTolerance > 2) {
                    val clusterEnd = i - gapTolerance
                    if (curEnergy > bestEnergy) {
                        bestEnergy = curEnergy
                        bestStart = curStart
                        bestEnd = clusterEnd
                    }
                    curStart = -1
                    curEnergy = 0f
                }
            }
        }
        if (curStart != -1) {
            val clusterEnd = (colIndex - 1 - gapTolerance).coerceAtLeast(curStart)
            if (curEnergy > bestEnergy) {
                bestEnergy = curEnergy
                bestStart = curStart
                bestEnd = clusterEnd
            }
        }

        val barSpanCols = if (bestStart >= 0 && bestEnd >= bestStart) (bestEnd - bestStart + 1) else 0
        val barWidth = barSpanCols.toFloat() / max(1, colIndex)
        val barX = if (bestStart >= 0 && bestEnergy >= 0.25f) {
            val leftNorm = bestStart.toFloat() / max(1, colIndex - 1)
            val rightNorm = bestEnd.toFloat() / max(1, colIndex - 1)
            ((leftNorm + rightNorm) * 0.5f).coerceIn(0f, 1f)
        } else {
            null
        }

        // Locate dark fish silhouette (outside box) or bright green-tinted fish inside box
        var fishWeightedSum = 0f
        var fishTotalWeight = 0f
        for (i in 0 until colIndex) {
            val weight = darkFishScores[i]
            if (weight >= 0.10f) {
                val normX = i.toFloat() / max(1, colIndex - 1)
                fishWeightedSum += normX * weight
                fishTotalWeight += weight
            }
        }

        var isFishInsideBar = false
        val fishX: Float? = if (fishTotalWeight >= 0.12f) {
            (fishWeightedSum / fishTotalWeight).coerceIn(0f, 1f)
        } else if (barX != null && bestStart >= 0) {
            var overlapWeightedSum = 0f
            var overlapWeight = 0f
            val spanStart = max(0, bestStart - 1)
            val spanEnd = min(colIndex - 1, bestEnd + 1)
            for (i in spanStart..spanEnd) {
                val w = brightFishScores[i]
                if (w >= 0.14f) {
                    val normX = i.toFloat() / max(1, colIndex - 1)
                    overlapWeightedSum += normX * w
                    overlapWeight += w
                }
            }
            isFishInsideBar = true
            if (overlapWeight > 0.14f) {
                (overlapWeightedSum / overlapWeight).coerceIn(0f, 1f)
            } else {
                barX
            }
        } else {
            null
        }

        val isMinigameActive = trackCoverage >= 0.22f && barX != null && barWidth >= 0.03f
        val (catchProgress, rightTailCyanRatio) = if (isMinigameActive) {
            measureCatchProgress(
                bitmap = bitmap,
                xStart = xStart,
                colStep = colStep,
                colCount = colIndex,
                trackColScores = trackColScores,
                trackBottom = trackBottom,
                regionHeight = regionHeight
            )
        } else {
            0f to 0f
        }

        val isCatchReady = isMinigameActive && catchProgress >= 0.88f && rightTailCyanRatio >= 0.70f
        val confidence = ((trackCoverage * 0.6f) + (if (barX != null) 0.4f else 0f)).coerceIn(0f, 1f)

        return MinigameAnalysisResult(
            isMinigameActive = isMinigameActive,
            fishX = fishX,
            barX = barX,
            barWidth = barWidth,
            isFishInsideBar = isFishInsideBar,
            catchProgress = catchProgress,
            isCatchReady = isCatchReady,
            confidence = confidence
        )
    }

    private fun measureCatchProgress(
        bitmap: Bitmap,
        xStart: Int,
        colStep: Int,
        colCount: Int,
        trackColScores: FloatArray,
        trackBottom: Int,
        regionHeight: Int
    ): Pair<Float, Float> {
        var leftTrackCol = 0
        while (leftTrackCol < colCount - 1 && trackColScores[leftTrackCol] < 0.35f) {
            leftTrackCol++
        }
        var rightTrackCol = colCount - 1
        while (rightTrackCol > leftTrackCol && trackColScores[rightTrackCol] < 0.35f) {
            rightTrackCol--
        }

        val innerLeftX = (xStart + leftTrackCol * colStep + 3).coerceIn(0, bitmap.width - 4)
        val innerRightX = (xStart + rightTrackCol * colStep - 3).coerceIn(innerLeftX + 8, bitmap.width - 1)
        val barW = innerRightX - innerLeftX + 1

        val stripTop = (trackBottom - 1).coerceIn(0, bitmap.height - 2)
        val stripBottom = (trackBottom + max(10, (regionHeight * 0.65f).toInt())).coerceIn(stripTop + 1, bitmap.height - 1)
        val stripH = stripBottom - stripTop + 1

        val requiredStripSize = barW * stripH
        if (stripPixelBuffer.size < requiredStripSize) {
            stripPixelBuffer = IntArray(requiredStripSize)
        }
        val stripPixels = stripPixelBuffer
        bitmap.getPixels(stripPixels, 0, barW, innerLeftX, stripTop, barW, stripH)

        val sampleStepX = max(1, barW / 64)
        var bestRowScore = -1f
        var bestFillRatio = 0f
        var bestTailRatio = 0f

        for (row in 0 until stripH) {
            val offset = row * barW
            var cyanCount = 0
            var darkTrackCount = 0
            var tailCyanCount = 0
            var tailTotal = 0
            var rowTotal = 0

            val tailThreshold = (barW * 0.83f).toInt()
            var col = 0
            while (col < barW) {
                val pixel = stripPixels[offset + col]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                val isBrightCyanFill = b >= 165 && g >= 135 && r <= 120 && (b - r) >= 60 && (g - r) >= 35
                val isDarkUnfilledTrack = r <= 75 && g <= 105 && b <= 150 && (b >= r)

                if (isBrightCyanFill) {
                    cyanCount++
                    if (col >= tailThreshold) tailCyanCount++
                } else if (isDarkUnfilledTrack) {
                    darkTrackCount++
                }

                if (col >= tailThreshold) tailTotal++
                rowTotal++
                col += sampleStepX
            }

            if (rowTotal > 0) {
                val stripLikeness = (cyanCount + darkTrackCount).toFloat() / rowTotal
                val rowScore = stripLikeness + (cyanCount.toFloat() / rowTotal) * 0.25f
                if (stripLikeness >= 0.60f && rowScore > bestRowScore) {
                    bestRowScore = rowScore
                    bestFillRatio = cyanCount.toFloat() / rowTotal
                    bestTailRatio = if (tailTotal > 0) tailCyanCount.toFloat() / tailTotal else 0f
                }
            }
        }

        return bestFillRatio.coerceIn(0f, 1f) to bestTailRatio.coerceIn(0f, 1f)
    }

    private fun emptyResult() = MinigameAnalysisResult(
        isMinigameActive = false,
        fishX = null,
        barX = null,
        barWidth = 0f,
        isFishInsideBar = false,
        catchProgress = 0f,
        isCatchReady = false,
        confidence = 0f
    )
}
