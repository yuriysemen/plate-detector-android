package com.github.yuriysemen.platesdetector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Detection(
    val leftPx: Float,
    val topPx: Float,
    val rightPx: Float,
    val bottomPx: Float,
    val score: Float,
    val classId: Int
)

enum class CoordFormat {
    /** [x1, y1, x2, y2, score, class] */
    XYXY_SCORE_CLASS,

    /** [y1, x1, y2, x2, score, class] */
    YXYX_SCORE_CLASS
}

class PlateDetector(
    private val context: Context,
    private val modelUri: Uri,
    threads: Int = 4,
    private val coordFormat: CoordFormat = CoordFormat.XYXY_SCORE_CLASS,
    private val classFilter: Int = 0,
    private val debugLogs: Boolean = false
) {

    private val interpreter: Interpreter
    private val runLock = Any()
    @Volatile private var closed = false

    private val inputW: Int
    private val inputH: Int
    private val inputType: DataType

    private val inputBuffer: ByteBuffer
    private val intValues: IntArray

    private val maxDetections: Int
    private val detections: Array<Array<FloatArray>> // [1, N, 6]

    init {
        val modelBuffer = loadModelFile(context, modelUri)
        val opts = Interpreter.Options().apply {
            setNumThreads(threads)
        }
        interpreter = Interpreter(modelBuffer, opts)

        val inTensor = interpreter.getInputTensor(0)
        val inShape = inTensor.shape() // [1, H, W, 3]
        require(inShape.size == 4 && inShape[0] == 1 && inShape[3] == 3) {
            "Unexpected input shape: ${inShape.contentToString()}"
        }
        inputH = inShape[1]
        inputW = inShape[2]
        inputType = inTensor.dataType()

        // Expect output[0] = [1, N, 6]
        require(interpreter.outputTensorCount >= 1) { "Model has no outputs" }
        val outTensor0 = interpreter.getOutputTensor(0)
        val outShape0 = outTensor0.shape()
        require(outShape0.size == 3 && outShape0[0] == 1) {
            "Unexpected output[0] shape: ${outShape0.contentToString()}"
        }
        require(outShape0[2] == 6) {
            "Expected output last dim=6, got ${outShape0[2]}. Shape=${outShape0.contentToString()}"
        }
        maxDetections = outShape0[1]
        detections = Array(1) { Array(maxDetections) { FloatArray(6) } }

        val bytesPerChannel = if (inputType == DataType.UINT8) 1 else 4
        inputBuffer = ByteBuffer
            .allocateDirect(1 * inputW * inputH * 3 * bytesPerChannel)
            .order(ByteOrder.nativeOrder())

        intValues = IntArray(inputW * inputH)

        if (debugLogs) {
            Log.d("PlateDetector", "Loaded model=$modelUri")
            Log.d("PlateDetector", "Input shape=${inShape.contentToString()} type=$inputType")
            Log.d("PlateDetector", "Output[0] shape=${outShape0.contentToString()} type=${outTensor0.dataType()}")
            Log.d("PlateDetector", "coordFormat=$coordFormat classFilter=$classFilter maxDet=$maxDetections")
        }
    }

    fun close() {
        synchronized(runLock) {
            if (closed) return
            closed = true
            interpreter.close()
        }
    }

    /**
     * (Optional) return all detections for classFilter above threshold.
     */
    fun detectAll(bitmapUpright: Bitmap, scoreThreshold: Float = 0.35f): List<Detection> {
        synchronized(runLock) {
            if (closed) return emptyList()

            val lb = letterbox(bitmapUpright, inputW, inputH)
            bitmapToInput(lb.letterboxed)

            val outputs: MutableMap<Int, Any> = HashMap()
            outputs[0] = detections

            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

            val list = ArrayList<Detection>(16)

            for (i in 0 until maxDetections) {
                val d = detections[0][i]
                val score = d[4]
                if (score < scoreThreshold) continue

                val cls = d[5].roundToInt()
                if (cls != classFilter) continue

                var x1: Float
                var y1: Float
                var x2: Float
                var y2: Float
                when (coordFormat) {
                    CoordFormat.XYXY_SCORE_CLASS -> {
                        x1 = d[0]; y1 = d[1]; x2 = d[2]; y2 = d[3]
                    }
                    CoordFormat.YXYX_SCORE_CLASS -> {
                        y1 = d[0]; x1 = d[1]; y2 = d[2]; x2 = d[3]
                    }
                }

                val normalized = isLikelyNormalized(x1, y1, x2, y2)
                if (normalized) {
                    x1 *= inputW; x2 *= inputW
                    y1 *= inputH; y2 *= inputH
                }

                val leftIn = min(x1, x2)
                val rightIn = max(x1, x2)
                val topIn = min(y1, y2)
                val bottomIn = max(y1, y2)

                val left = (leftIn - lb.padX) / lb.scale
                val top = (topIn - lb.padY) / lb.scale
                val right = (rightIn - lb.padX) / lb.scale
                val bottom = (bottomIn - lb.padY) / lb.scale

                list += Detection(
                    leftPx = clamp(left, 0f, bitmapUpright.width.toFloat()),
                    topPx = clamp(top, 0f, bitmapUpright.height.toFloat()),
                    rightPx = clamp(right, 0f, bitmapUpright.width.toFloat()),
                    bottomPx = clamp(bottom, 0f, bitmapUpright.height.toFloat()),
                    score = score,
                    classId = cls
                )
            }

            return list.sortedByDescending { it.score }
        }
    }

    // -----------------------
    // Preprocess (letterbox)
    // -----------------------

    private data class Letterbox(
        val letterboxed: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private fun letterbox(src: Bitmap, dstW: Int, dstH: Int): Letterbox {
        val scale = min(dstW / src.width.toFloat(), dstH / src.height.toFloat())
        val newW = (src.width * scale).roundToInt()
        val newH = (src.height * scale).roundToInt()
        val padX = (dstW - newW) / 2f
        val padY = (dstH - newH) / 2f

        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        val resized = Bitmap.createScaledBitmap(src, newW, newH, true)
        canvas.drawBitmap(resized, padX, padY, null)

        return Letterbox(out, scale, padX, padY)
    }

    private fun bitmapToInput(bmp: Bitmap) {
        inputBuffer.rewind()

        bmp.getPixels(intValues, 0, bmp.width, 0, 0, bmp.width, bmp.height)

        when (inputType) {
            DataType.UINT8 -> {
                // [0..255] RGB
                for (px in intValues) {
                    inputBuffer.put(((px shr 16) and 0xFF).toByte()) // R
                    inputBuffer.put(((px shr 8) and 0xFF).toByte())  // G
                    inputBuffer.put((px and 0xFF).toByte())          // B
                }
            }
            else -> {
                // float32: [0..1] RGB
                for (px in intValues) {
                    val r = ((px shr 16) and 0xFF) / 255.0f
                    val g = ((px shr 8) and 0xFF) / 255.0f
                    val b = (px and 0xFF) / 255.0f
                    inputBuffer.putFloat(r)
                    inputBuffer.putFloat(g)
                    inputBuffer.putFloat(b)
                }
            }
        }
    }

    // -----------------------
    // Utilities
    // -----------------------

    private fun loadModelFile(context: Context, modelUri: Uri): ByteBuffer {
        return try {
            context.contentResolver.openFileDescriptor(modelUri, "r")?.use { pfd ->
                val size = pfd.statSize
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val channel = fis.channel
                    if (size > 0) {
                        channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
                    } else {
                        val bytes = fis.readBytes()
                        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                            put(bytes)
                            rewind()
                        }
                    }
                }
            } ?: error("Unable to open model file: $modelUri")
        } catch (t: Throwable) {
            if (debugLogs) Log.w("PlateDetector", "openFileDescriptor failed, fallback: ${t.message}")
            val bytes = context.contentResolver.openInputStream(modelUri)?.use { it.readBytes() }
                ?: error("Unable to read model file: $modelUri")
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }
    }

    private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))

    private fun isLikelyNormalized(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        // heuristic: all coords within a small range around [0..1]
        val m = max(max(x1, y1), max(x2, y2))
        val n = min(min(x1, y1), min(x2, y2))
        return (m <= 1.5f && n >= -0.5f)
    }
}
