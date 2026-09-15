package com.github.yuriysemen.platesdetector

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max
import kotlin.math.min

data class OCRResult(
    val text: String,
    val confidence: Float,
    val rawText: String
)

class PlateOCR(private val debugLogs: Boolean = false) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Performs OCR on a bitmap (typically a cropped plate region).
     * Returns OCRResult with cleaned text and confidence, or null if no text found.
     */
    fun recognizePlate(bitmap: Bitmap): OCRResult? {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = Tasks.await(recognizer.process(image))

            if (visionText.text.isEmpty()) {
                if (debugLogs) Log.d("PlateOCR", "No text detected")
                return null
            }

            val rawText = visionText.text
            val cleanedText = cleanPlateText(rawText)

            // Calculate average confidence from all text blocks
            // ML Kit doesn't provide per-element confidence, so we estimate based on content
            val hasMultipleBlocks = visionText.textBlocks.size > 0
            val avgConfidence = if (hasMultipleBlocks && cleanedText.isNotEmpty()) {
                0.85f // Reasonable estimate when text is detected
            } else {
                0.5f
            }

            if (debugLogs) {
                Log.d("PlateOCR", "Raw text: '$rawText'")
                Log.d("PlateOCR", "Cleaned text: '$cleanedText'")
                Log.d("PlateOCR", "Confidence: $avgConfidence")
            }

            OCRResult(
                text = cleanedText,
                confidence = avgConfidence,
                rawText = rawText
            )
        } catch (e: Exception) {
            if (debugLogs) Log.e("PlateOCR", "OCR failed", e)
            null
        }
    }

    /**
     * Cleans the recognized text to match typical license plate patterns.
     * - Removes whitespace, newlines, special characters
     * - Converts to uppercase
     * - Filters to alphanumeric characters only
     */
    private fun cleanPlateText(text: String): String {
        // Remove all whitespace and newlines
        val noSpaces = text.replace(Regex("\\s+"), "")

        // Convert to uppercase
        val upper = noSpaces.uppercase()

        // Keep only alphanumeric characters
        val alphanumeric = upper.filter { it.isLetterOrDigit() }

        return alphanumeric
    }

    /**
     * Crops a bitmap to the specified detection bounds and applies padding if needed.
     */
    fun cropToBounds(
        bitmap: Bitmap,
        leftPx: Float,
        topPx: Float,
        rightPx: Float,
        bottomPx: Float,
        paddingFraction: Float = 0.1f
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // Add padding to capture more context around the plate
        val boxWidth = rightPx - leftPx
        val boxHeight = bottomPx - topPx
        val padX = boxWidth * paddingFraction
        val padY = boxHeight * paddingFraction

        // Clamp to bitmap bounds
        val cropLeft = max(0f, leftPx - padX).toInt()
        val cropTop = max(0f, topPx - padY).toInt()
        val cropRight = min(width.toFloat(), rightPx + padX).toInt()
        val cropBottom = min(height.toFloat(), bottomPx + padY).toInt()

        val cropWidth = cropRight - cropLeft
        val cropHeight = cropBottom - cropTop

        if (cropWidth <= 0 || cropHeight <= 0) {
            if (debugLogs) Log.w("PlateOCR", "Invalid crop dimensions")
            return bitmap
        }

        return try {
            Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
        } catch (e: Exception) {
            if (debugLogs) Log.e("PlateOCR", "Failed to crop bitmap", e)
            bitmap
        }
    }

    /**
     * Releases resources. Call when done with OCR.
     */
    fun close() {
        recognizer.close()
    }
}
