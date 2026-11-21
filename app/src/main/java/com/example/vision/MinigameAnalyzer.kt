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
 * Dynamically locates the track interior, skips the static cyan frame border, reads the
 * underlying catch progress strip, and accurately separates the fish icon from the track gradient.
 */
class MinigameAnalyzer {

    private var regionPixelBuffer = IntArray(0)
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
        val yStart = (rect.top * height).toInt().coerceIn(0, height - 2)
        val yEnd = (rect.bottom * height).toInt().coerceIn(yStart + 1, height - 1)

        val regionWidth = xEnd - xStart + 1
        val fullRegionHeight = yEnd - yStart + 1
        if (regionWidth < 16 || fullRegionHeight < 10) {
            return emptyResult()
        }

        val requiredSize = regionWidth * fullRegionHeight
        if (regionPixelBuffer.size < requiredSize) {
            regionPixelBuffer = IntArray(requiredSize)
        }
        val fullPixels = regionPixelBuffer
        bitmap.getPixels(fullPixels, 0, regionWidth, xStart, yStart, regionWidth, fullRegionHeight)

        // 1. Dynamically detect which rows inside minigameBarRegion belong to the track interior
        // (and avoid including the top title text or bottom static cyan border inside track rows).
        val rowScanStepX = max(1, regionWidth / 48)
        var detectedTrackStartRow = -1
        var detectedTrackEndRow = -1
        var curBandStart = -1
        var bestBandLen = 0

        for (ry in 0 until fullRegionHeight) {
            val rowOffset = ry * regionWidth
            var trackLike = 0
            var staticBorderLike = 0
            var samples = 0
            var rx = 0
            while (rx < regionWidth) {
                val pixel = fullPixels[rowOffset + rx]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                if (isStaticFrameBorder(r, g, b)) {
                    staticBorderLike++
                } else if (isTrackInteriorBlue(r, g, b) || isBoxColor(r, g, b)) {
                    trackLike++
                }
                samples++
                rx += rowScanStepX
            }
            val borderRatio = if (samples > 0) staticBorderLike.toFloat() / samples else 0f
            val interiorRatio = if (samples > 0) trackLike.toFloat() / samples else 0f

            if (interiorRatio >= 0.42f && borderRatio < 0.45f) {
                if (curBandStart == -1) curBandStart = ry
                val bandLen = ry - curBandStart + 1
                if (bandLen > bestBandLen) {
                    bestBandLen = bandLen
                    detectedTrackStartRow = curBandStart
                    detectedTrackEndRow = ry
                }
            } else {
                curBandStart = -1
            }
        }

        val trackRelTop: Int
        val trackRelBottom: Int
        if (detectedTrackStartRow >= 0 && bestBandLen >= max(4, (fullRegionHeight * 0.14f).toInt())) {
            trackRelTop = detectedTrackStartRow
            trackRelBottom = detectedTrackEndRow
        } else {
            // Fallback band for custom calibrated regions
            trackRelTop = (fullRegionHeight * 0.16f).toInt().coerceIn(0, fullRegionHeight - 4)
            trackRelBottom = (fullRegionHeight * 0.68f).toInt().coerceIn(trackRelTop + 3, fullRegionHeight - 1)
        }

        val trackHeight = trackRelBottom - trackRelTop + 1
        if (trackHeight < 4) {
            return emptyResult()
        }

        val colStep = max(1, regionWidth / 240)
        val rowStep = max(1, trackHeight / 24)

        val sampleCols = (regionWidth / colStep) + 1
        val boxScores = FloatArray(sampleCols)
        val darkFishScores = FloatArray(sampleCols)
        val lightFishScores = FloatArray(sampleCols)
        val brightFishScores = FloatArray(sampleCols)
        val trackColScores = FloatArray(sampleCols)
        var trackMatchingSamples = 0
        var totalSamples = 0

        var colIndex = 0
        var px = 0
        while (px < regionWidth && colIndex < sampleCols) {
            var boxCount = 0
            var darkFishCount = 0
            var lightFishCount = 0
            var brightFishCount = 0
            var trackCount = 0
            var colSamples = 0

            var py = trackRelTop
            while (py <= trackRelBottom) {
                val pixel = fullPixels[py * regionWidth + px]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                val isTrackBlue = isTrackInteriorBlue(r, g, b)
                val isBoxPixel = isBoxColor(r, g, b)
                // Dark fish silhouette: matches (0,33,112) & (22,52,105) while rejecting track blue (g>=57, b>=140)
                val isDarkFish = !isBoxPixel && r < 65 && g <= 54 && b in 15..134
                // Light silver/cyan fish body outside the box
                val isLightFish = !isBoxPixel && r in 75..220 && g >= 145 && b >= 185 && (b - r) <= 130
                // Lime-green fish body overlapping inside the green box
                val isBrightOverlapFish = r >= 75 && g >= 165 && b in 40..135 && (g - b) >= 60

                if (isBoxPixel) boxCount++
                if (isDarkFish) darkFishCount++
                if (isLightFish) lightFishCount++
                if (isBrightOverlapFish) brightFishCount++
                if (isTrackBlue || isBoxPixel || isDarkFish || isLightFish || isBrightOverlapFish) {
                    trackCount++
                    trackMatchingSamples++
                }

                colSamples++
                totalSamples++
                py += rowStep
            }

            // Also probe just below the static bottom border for a hollow box's bottom green/yellow edge
            var bottomOutlineHit = 0
            val outlineCheckEnd = min(fullRegionHeight - 1, trackRelBottom + max(8, (trackHeight * 0.50f).toInt()))
            for (oy in (trackRelBottom + 1)..outlineCheckEnd) {
                val op = fullPixels[oy * regionWidth + px]
                if (isBoxColor(Color.red(op), Color.green(op), Color.blue(op))) {
                    bottomOutlineHit++
                }
            }

            if (colSamples > 0) {
                val baseBoxScore = boxCount.toFloat() / colSamples
                val outlineBoost = if (bottomOutlineHit > 0) 0.20f else 0f
                boxScores[colIndex] = (baseBoxScore + outlineBoost).coerceAtMost(1f)
                darkFishScores[colIndex] = darkFishCount.toFloat() / colSamples
                lightFishScores[colIndex] = lightFishCount.toFloat() / colSamples
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
        var activeTrackCols = 0
        for (i in 0 until colIndex) {
            if (trackColScores[i] >= 0.45f) activeTrackCols++
        }
        val activeTrackColRatio = activeTrackCols.toFloat() / colIndex

        // Group contiguous columns belonging to the movable box (green / yellow / orange / red)
        var bestStart = -1
        var bestEnd = -1
        var bestEnergy = 0f

        var curStart = -1
        var curEnergy = 0f
        var gapTolerance = 0

        for (i in 0 until colIndex) {
            val s = boxScores[i]
            if (s >= 0.12f) {
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
        var finalStart = bestStart
        var finalEnd = bestEnd
        var finalSpanCols = barSpanCols

        // If only a thin single vertical outline edge was grouped (hollow box with empty interior),
        // look for its companion opposite edge within expected box width (5%..18% of track).
        if (bestStart >= 0 && barSpanCols.toFloat() / max(1, colIndex) < 0.045f) {
            val minPartnerDist = max(3, (colIndex * 0.05f).toInt())
            val maxPartnerDist = max(minPartnerDist + 2, (colIndex * 0.18f).toInt())
            var bestPartner = -1
            var bestPartnerScore = 0f
            for (d in minPartnerDist..maxPartnerDist) {
                val rightCand = bestEnd + d
                if (rightCand < colIndex && boxScores[rightCand] >= 0.14f && boxScores[rightCand] > bestPartnerScore) {
                    bestPartnerScore = boxScores[rightCand]
                    bestPartner = rightCand
                }
                val leftCand = bestStart - d
                if (leftCand >= 0 && boxScores[leftCand] >= 0.14f && boxScores[leftCand] > bestPartnerScore) {
                    bestPartnerScore = boxScores[leftCand]
                    bestPartner = leftCand
                }
            }
            if (bestPartner >= 0) {
                finalStart = min(bestStart, bestPartner)
                finalEnd = max(bestEnd, bestPartner)
                finalSpanCols = finalEnd - finalStart + 1
            }
        }

        val barWidth = finalSpanCols.toFloat() / max(1, colIndex)
        val barX = if (finalStart >= 0 && bestEnergy >= 0.25f) {
            val leftNorm = finalStart.toFloat() / max(1, colIndex - 1)
            val rightNorm = finalEnd.toFloat() / max(1, colIndex - 1)
            ((leftNorm + rightNorm) * 0.5f).coerceIn(0f, 1f)
        } else {
            null
        }

        // Locate fish outside box (strongest contiguous dark/light fish cluster, ignoring box columns & 1px noise)
        val darkOutsideX = findFishClusterOutsideBox(darkFishScores, colIndex, finalStart, finalEnd, minCols = 2)
        val lightOutsideX = if (darkOutsideX == null) {
            findFishClusterOutsideBox(lightFishScores, colIndex, finalStart, finalEnd, minCols = 2)
        } else {
            null
        }

        var isFishInsideBar = false
        val fishX: Float? = if (darkOutsideX != null) {
            darkOutsideX
        } else if (lightOutsideX != null) {
            lightOutsideX
        } else if (barX != null && finalStart >= 0) {
            var overlapWeightedSum = 0f
            var overlapWeight = 0f
            var unlitWeightedSum = 0f
            var unlitWeight = 0f
            val spanStart = max(0, finalStart - 1)
            val spanEnd = min(colIndex - 1, finalEnd + 1)
            for (i in spanStart..spanEnd) {
                val normX = i.toFloat() / max(1, colIndex - 1)
                val w = brightFishScores[i]
                if (w >= 0.14f) {
                    overlapWeightedSum += normX * w
                    overlapWeight += w
                }
                val u = darkFishScores[i] + lightFishScores[i]
                if (u >= 0.16f) {
                    unlitWeightedSum += normX * u
                    unlitWeight += u
                }
            }
            isFishInsideBar = true
            when {
                overlapWeight > 0.14f -> (overlapWeightedSum / overlapWeight).coerceIn(0f, 1f)
                unlitWeight > 0.25f -> (unlitWeightedSum / unlitWeight).coerceIn(0f, 1f)
                else -> barX
            }
        } else {
            null
        }

        val isMinigameActive = trackCoverage >= 0.30f &&
            activeTrackColRatio >= 0.52f &&
            barX != null &&
            barWidth in 0.04f..0.38f

        val absTrackBottom = yStart + trackRelBottom
        val (catchProgress, rightTailCyanRatio) = if (isMinigameActive) {
            measureCatchProgress(
                bitmap = bitmap,
                xStart = xStart,
                colStep = colStep,
                colCount = colIndex,
                trackColScores = trackColScores,
                trackBottom = absTrackBottom,
                trackHeight = trackHeight,
                regionBottomLimit = yEnd
            )
        } else {
            0f to 0f
        }

        val isCatchReady = isMinigameActive && catchProgress >= 0.91f && rightTailCyanRatio >= 0.78f
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

    private fun findFishClusterOutsideBox(
        scores: FloatArray,
        colCount: Int,
        boxStart: Int,
        boxEnd: Int,
        minCols: Int
    ): Float? {
        var bestClusterStart = -1
        var bestClusterEnd = -1
        var bestClusterEnergy = 0f

        var curStart = -1
        var curEnergy = 0f
        var gap = 0

        for (i in 0 until colCount) {
            val insideBox = boxStart >= 0 && i in (boxStart - 1)..(boxEnd + 1)
            val s = if (insideBox) 0f else scores[i]
            if (s >= 0.14f) {
                if (curStart == -1) {
                    curStart = i
                    curEnergy = 0f
                }
                curEnergy += s
                gap = 0
            } else if (curStart != -1) {
                gap++
                if (gap > 1) {
                    val end = i - gap
                    val len = end - curStart + 1
                    if (len >= minCols && curEnergy > bestClusterEnergy) {
                        bestClusterEnergy = curEnergy
                        bestClusterStart = curStart
                        bestClusterEnd = end
                    }
                    curStart = -1
                    curEnergy = 0f
                }
            }
        }
        if (curStart != -1) {
            val end = (colCount - 1 - gap).coerceAtLeast(curStart)
            val len = end - curStart + 1
            if (len >= minCols && curEnergy > bestClusterEnergy) {
                bestClusterEnergy = curEnergy
                bestClusterStart = curStart
                bestClusterEnd = end
            }
        }

        if (bestClusterStart < 0 || bestClusterEnergy < 0.25f) return null

        var weightedSum = 0f
        var weightTotal = 0f
        for (i in bestClusterStart..bestClusterEnd) {
            val w = scores[i]
            if (w > 0f) {
                val normX = i.toFloat() / max(1, colCount - 1)
                weightedSum += normX * w
                weightTotal += w
            }
        }
        return if (weightTotal > 0f) (weightedSum / weightTotal).coerceIn(0f, 1f) else null
    }

    private fun measureCatchProgress(
        bitmap: Bitmap,
        xStart: Int,
        colStep: Int,
        colCount: Int,
        trackColScores: FloatArray,
        trackBottom: Int,
        trackHeight: Int,
        regionBottomLimit: Int
    ): Pair<Float, Float> {
        var leftTrackCol = 0
        while (leftTrackCol < colCount - 1 && trackColScores[leftTrackCol] < 0.35f) {
            leftTrackCol++
        }
        var rightTrackCol = colCount - 1
        while (rightTrackCol > leftTrackCol && trackColScores[rightTrackCol] < 0.35f) {
            rightTrackCol--
        }

        val trim = max(4, ((rightTrackCol - leftTrackCol) * colStep * 0.025f).toInt())
        val innerLeftX = (xStart + leftTrackCol * colStep + trim).coerceIn(0, bitmap.width - 4)
        val innerRightX = (xStart + rightTrackCol * colStep - trim).coerceIn(innerLeftX + 8, bitmap.width - 1)
        val barW = innerRightX - innerLeftX + 1

        val stripTop = (trackBottom + 1).coerceIn(0, bitmap.height - 2)
        val maxSearchBottom = max(
            regionBottomLimit + max(6, (bitmap.height * 0.018f).toInt()),
            trackBottom + max(14, (trackHeight * 0.90f).toInt())
        )
        val stripBottom = maxSearchBottom.coerceIn(stripTop + 1, bitmap.height - 1)
        val stripH = stripBottom - stripTop + 1

        val requiredStripSize = barW * stripH
        if (stripPixelBuffer.size < requiredStripSize) {
            stripPixelBuffer = IntArray(requiredStripSize)
        }
        val stripPixels = stripPixelBuffer
        bitmap.getPixels(stripPixels, 0, barW, innerLeftX, stripTop, barW, stripH)

        val sampleStepX = max(1, barW / 64)
        var startRow = 0
        // Skip the static bottom frame border row(s) right below the track (g ~ 150..182, b >= 230)
        while (startRow < stripH - 1) {
            val offset = startRow * barW
            var borderLike = 0
            var rowTotal = 0
            var col = 0
            while (col < barW) {
                val pixel = stripPixels[offset + col]
                if (isStaticFrameBorder(Color.red(pixel), Color.green(pixel), Color.blue(pixel))) {
                    borderLike++
                }
                rowTotal++
                col += sampleStepX
            }
            if (rowTotal > 0 && (borderLike.toFloat() / rowTotal) >= 0.45f) {
                startRow++
            } else {
                break
            }
        }

        var bestRowScore = -1f
        var bestFillRatio = 0f
        var bestTailRatio = 0f

        for (row in startRow until stripH) {
            val offset = row * barW
            var cyanCount = 0
            var darkTrackCount = 0
            var tailCyanCount = 0
            var tailTotal = 0
            var rowTotal = 0

            val tailThreshold = (barW * 0.85f).toInt()
            var col = 0
            while (col < barW) {
                val pixel = stripPixels[offset + col]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Progress strip filled bright cyan: e.g. (49,200,255), (63,196,255), or synthetic (40,210,245)
                val isBrightCyanFill = b >= 210 && g >= 180 && r <= 105 && (b - r) >= 115 && (g - r) >= 85
                // Progress strip unfilled dark navy/blue: e.g. (23,31,59), (18,68,162), (31,126,188)
                val isDarkUnfilledTrack = r <= 65 && g <= 135 && b <= 195 && (b >= r)

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
                val fillRatio = cyanCount.toFloat() / rowTotal
                // Prefer rows that clearly match the progress bar (high stripLikeness) and aren't just dim background
                val rowScore = stripLikeness + (fillRatio * 0.15f)
                if (stripLikeness >= 0.65f && rowScore > bestRowScore) {
                    bestRowScore = rowScore
                    bestFillRatio = fillRatio
                    bestTailRatio = if (tailTotal > 0) tailCyanCount.toFloat() / tailTotal else 0f
                }
            }
        }

        return bestFillRatio.coerceIn(0f, 1f) to bestTailRatio.coerceIn(0f, 1f)
    }

    private fun isStaticFrameBorder(r: Int, g: Int, b: Int): Boolean {
        return r in 10..70 && g in 145..183 && b >= 225 && (b - g) >= 60
    }

    private fun isTrackInteriorBlue(r: Int, g: Int, b: Int): Boolean {
        return r <= 40 && g in 48..122 && b in 120..203 && (b - g) >= 50 && (b - r) >= 95
    }

    private fun isBoxColor(r: Int, g: Int, b: Int): Boolean {
        val isGreenBox = g >= 108 && g > r + 40 && g > b + 40
        val isTealGreenBorder = g >= 120 && r <= 65 && (g - r) >= 55 && b <= 135
        val isYellowBox = g >= 125 && r >= 105 && g >= b + 35 && abs(r - g) <= 75
        val isOrangeRedBox = r >= 130 && g in 35..165 && b <= 110 && (r - b) >= 65
        return isGreenBox || isTealGreenBorder || isYellowBox || isOrangeRedBox
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
