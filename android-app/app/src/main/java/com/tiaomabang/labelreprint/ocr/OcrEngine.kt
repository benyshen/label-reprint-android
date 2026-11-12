package com.tiaomabang.labelreprint.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.io.File
import java.util.EnumSet
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * PP-OCRv6 small (det + rec, optional cls) inference pipeline, running fully on-device.
 *
 * Upgraded from PP-OCRv4 (2026-11): v6 small det/rec, 18708-character unified dictionary,
 * same 48px rec height and same ImageNet / 0.5-0.5 normalisation, so the pipeline below is
 * unchanged — only the model + dict assets and their thresholds differ. Measured on the
 * project's label photos: EAN-13 digit lines come out clean (`934024512123` vs v4's
 * `934024"512123`), all line confidences ≥ 0.99, det ~20% faster.
 *
 * The implementation mirrors the validated Python/OpenCV reference
 * (`tools/ppocr_ref.py`) step by step:
 *
 *  det  : resize so the short side reaches [DET_LIMIT] (limit_type=min, pad/32)
 *         -> ImageNet normalize -> ONNX -> DB probability map
 *  post : threshold -> contours -> minAreaRect -> getMiniBoxes ordering
 *         -> box score (mean prob inside polygon) -> unclip -> rescale
 *  rec  : getRotateCropImage (rot90 when the box is tall) -> resize to h=48
 *         keeping aspect (dynamic width = 48 * max aspect of the frame)
 *         -> (x/255-0.5)/0.5 -> ONNX -> CTC greedy decode
 */
class OcrEngine(
    private val context: Context,
    val requestedBackend: InferenceBackend = InferenceBackend.CPU
) {

    companion object {
        private const val TAG = "OcrEngine"
        private const val PROFILE_PREFIX = "ort_profile"

        /** Short side target of the detection input (PaddleOCR limit_side_len, limit_type=min). */
        const val DET_LIMIT = 736
        const val DET_THRESH = 0.3f
        const val BOX_THRESH = 0.5
        const val UNCLIP_RATIO = 1.5
        const val MIN_BOX_SIDE = 3.0
        const val UNCLIP_MIN_SIDE = 5.0
        const val REC_HEIGHT = 48
        const val MIN_CONFIDENCE = 0.3f
        const val CLS_WIDTH = 192

        private const val DET_MODEL = "models/ch_PP-OCRv6_det_small_infer.onnx"
        private const val REC_MODEL = "models/ch_PP-OCRv6_rec_small_infer.onnx"
        private const val CLS_MODEL = "models/ch_ppocr_mobile_v2.0_cls_infer.onnx"
        private const val DICT_FILE = "models/ppocr_v6_dict.txt"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var det: OrtSession
    private var rec: OrtSession
    private var cls: OrtSession?

    /**
     * Backend actually in use. Equals [requestedBackend] unless the requested provider could not be
     * created, in which case the engine falls back to CPU and records why in [backendNote].
     */
    var activeBackend: InferenceBackend = requestedBackend
        private set

    /** Empty when the request was honoured; otherwise the reason the fallback happened. */
    var backendNote: String = ""
        private set

    /** Provider distribution of the previous NPU run (parsed from the ORT profile), or null. */
    var lastProfile: String? = null
        private set

    /** index 0 = CTC blank, 1..N-2 = dict characters, N-1 = space */
    private val chars: List<String>

    // reusable scratch buffers (avoid per-frame multi-MB allocations)
    private var detInput: FloatBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var detInputFloats = 0
    private var chBuf = FloatArray(0)
    private var probBuf = FloatArray(0)
    private var prob: Mat = Mat()
    private var lastNetW = 0
    private var lastNetH = 0

    val modelsLoaded: Boolean get() = true

    /** When true, intermediate tensors are written as PNGs into the app's external files dir. */
    @Volatile var dumpDebug: Boolean = false

    private fun dump(mat: Mat, name: String, scale: Double = 1.0) {
        if (!dumpDebug) return
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val out = Mat()
            if (scale == 1.0) mat.convertTo(out, CvType.CV_8U)
            else mat.convertTo(out, CvType.CV_8U, scale)
            val ok = org.opencv.imgcodecs.Imgcodecs.imwrite(File(dir, name).absolutePath, out)
            out.release()
            Log.i(TAG, "dump $name ok=$ok")
        } catch (t: Throwable) {
            Log.w(TAG, "dump $name failed", t)
        }
    }

    init {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        lastProfile = readPreviousProfile()
        val built = try {
            buildSessions(requestedBackend, threads)
        } catch (t: Throwable) {
            if (requestedBackend == InferenceBackend.CPU) throw t
            val reason = "${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "backend ${requestedBackend.label} unavailable ($reason) -> falling back to CPU", t)
            backendNote = "NPU 不可用，已回退 CPU（$reason）"
            buildSessions(InferenceBackend.CPU, threads)
        }
        det = built.det
        rec = built.rec
        cls = built.cls
        chars = loadChars()
        Log.i(
            TAG,
            "models ready: backend=$activeBackend (requested=$requestedBackend) " +
                "det=${det.inputNames} rec=${rec.inputNames} cls=${cls != null} " +
                "dict=${chars.size} threads=$threads note=$backendNote profile=$lastProfile"
        )
    }

    private class Sessions(val det: OrtSession, val rec: OrtSession, val cls: OrtSession?)

    /**
     * Creates the three sessions on the requested execution provider.
     *
     * NNAPI is the only NPU-capable provider compiled into this ORT build (there is no QNN EP), so
     * [InferenceBackend.NNAPI] enables it with FP16 and lets ORT partition the graph: whatever the
     * vendor driver cannot take stays on the CPU within the same session.
     */
    private fun buildSessions(backend: InferenceBackend, threads: Int): Sessions {
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(threads)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (backend == InferenceBackend.NNAPI) {
            opts.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
            // The profile is flushed when the session closes; readPreviousProfile() picks it up on
            // the next start to report which provider really executed the nodes. Written to the
            // app's external dir so it can be pulled with adb for verification.
            val profileDir = context.getExternalFilesDir(null) ?: context.filesDir
            val profilePrefix = File(profileDir, PROFILE_PREFIX).absolutePath
            try {
                opts.enableProfiling(profilePrefix)
                Log.i(TAG, "profiling enabled: $profilePrefix")
            } catch (t: Throwable) {
                Log.w(TAG, "enableProfiling failed", t)
            }
        }
        val detSession = env.createSession(readAsset(DET_MODEL), opts)
        val recSession = env.createSession(readAsset(REC_MODEL), opts)
        val clsSession = try {
            env.createSession(readAsset(CLS_MODEL), opts)
        } catch (t: Throwable) {
            Log.w(TAG, "cls model unavailable, 180° text will not be corrected", t)
            null
        }
        activeBackend = backend
        return Sessions(detSession, recSession, clsSession)
    }

    /** Parses (and consumes) the ORT profile written by the previous run, summarising providers. */
    private fun readPreviousProfile(): String? {
        return try {
            val dirs = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
            val files = dirs.flatMap { dir ->
                dir.listFiles { f -> f.name.startsWith(PROFILE_PREFIX) && f.name.endsWith(".json") }
                    ?.toList() ?: emptyList()
            }
            if (files.isEmpty()) {
                Log.i(TAG, "no previous ORT profile found")
                return null
            }
            val newest = files.maxByOrNull { it.lastModified() }!!
            val text = newest.readText()
            files.forEach { it.delete() }
            Log.i(TAG, "parsing ORT profile ${newest.name} (${text.length} chars)")
            val counts = Regex("\"provider\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(text)
                .groupingBy { it.groupValues[1] }
                .eachCount()
            val total = counts.values.sum()
            if (total == 0) null
            else counts.entries.sortedByDescending { it.value }
                .joinToString(" · ") { (provider, count) -> "$provider ${count * 100 / total}%" }
        } catch (t: Throwable) {
            Log.w(TAG, "profile parse failed", t)
            null
        }
    }

    /**
     * Swaps to a new backend at runtime (used when the requested provider fails mid-run).
     * Sessions are rebuilt on the CPU; the pipeline's scratch buffers are unaffected.
     */
    @Synchronized
    private fun fallbackToCpu(reason: String) {
        if (activeBackend == InferenceBackend.CPU) return
        Log.w(TAG, "runtime failure on $activeBackend ($reason) -> rebuilding on CPU")
        backendNote = "NPU 运行失败，已回退 CPU（$reason）"
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val built = buildSessions(InferenceBackend.CPU, threads)
        runCatching { det.close() }
        runCatching { rec.close() }
        runCatching { cls?.close() }
        det = built.det
        rec = built.rec
        cls = built.cls
    }

    // ------------------------------------------------------------------ assets

    private fun readAsset(path: String): ByteArray =
        context.assets.open(path).use { it.readBytes() }

    private fun loadChars(): List<String> {
        val text = context.assets.open(DICT_FILE).bufferedReader(Charsets.UTF_8).readText()
        val keys = text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        val list = ArrayList<String>(keys.size + 2)
        list.add("blank")          // index 0: CTC blank
        list.addAll(keys)          // 1..N
        list.add(" ")              // last: space (use_space_char)
        return list
    }

    // ------------------------------------------------------------------ public API

    /** Runs the full pipeline on an upright (already rotated) bitmap. */
    fun run(bitmap: Bitmap): List<OcrResult> = try {
        runPipeline(bitmap)
    } catch (t: Throwable) {
        // A provider can fail at inference time (driver errors on some SoCs) even though the session
        // was created fine — retry once on the CPU instead of losing every frame.
        if (activeBackend == InferenceBackend.CPU) throw t
        fallbackToCpu("${t.javaClass.simpleName}: ${t.message}")
        runPipeline(bitmap)
    }

    private fun runPipeline(bitmap: Bitmap): List<OcrResult> {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        // PaddleOCR/RapidOCR feed cv2 BGR images to the ONNX graphs, so convert to BGR to keep
        // channel order identical to the validated Python reference.
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        try {
            val boxes = detect(bgr)
            Log.i(TAG, "run: frame=${bitmap.width}x${bitmap.height} boxes=${boxes.size}")
            if (boxes.isEmpty()) return emptyList()
            val recognized = recognize(bgr, boxes)
            Log.i(TAG, "run: boxes=${boxes.size} lines=${recognized.size}")
            return sortReadingOrder(recognized)
        } finally {
            bgr.release()
        }
    }

    // ------------------------------------------------------------------ detection

    private fun detect(bgr: Mat): List<FloatArray> {
        val imgH = bgr.rows()
        val imgW = bgr.cols()
        // Normalise the short side to DET_LIMIT: upscale small frames (PaddleOCR limit_type=min)
        // and downscale large ones (a 1920x1080 analysis stream would otherwise cost ~2x the pixels
        // and turn into multi-second frames on a phone CPU).
        val shortSide = min(imgH, imgW)
        val scale = if (shortSide > 0) DET_LIMIT.toDouble() / shortSide else 1.0

        val netW = max((round(imgW * scale / 32.0) * 32).toInt(), 32)
        val netH = max((round(imgH * scale / 32.0) * 32).toInt(), 32)
        if (netW != lastNetW || netH != lastNetH) {
            Log.i(TAG, "det: frame=${imgW}x${imgH} -> net=${netW}x${netH} scale=$scale")
            lastNetW = netW
            lastNetH = netH
        }

        val resized = Mat()
        Imgproc.resize(bgr, resized, Size(netW.toDouble(), netH.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

        val normalized = Mat()
        resized.convertTo(normalized, CvType.CV_32FC3, 1.0 / 255.0)
        dump(resized, "01_det_input.png")
        resized.release()
        Core.subtract(normalized, Scalar(0.485, 0.456, 0.406), normalized)
        Core.divide(normalized, Scalar(0.229, 0.224, 0.225), normalized)

        val plane = netW * netH
        val fb = inputBuffer(3 * plane)
        val ch = Mat()
        val planeFloats = ensureChBuf(plane)
        for (c in 0 until 3) {
            Core.extractChannel(normalized, ch, c)
            ch.get(0, 0, planeFloats)
            fb.position(c * plane)
            fb.put(planeFloats, 0, plane)
        }
        ch.release()
        normalized.release()

        fb.rewind()
        var tensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        val boxes = ArrayList<FloatArray>()
        try {
            tensor = OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, netH.toLong(), netW.toLong()))
            result = det.run(mapOf("x" to tensor))
            val detOut = result.get(0) as OnnxTensor
            val shape = detOut.info.shape
            val outH = shape[shape.size - 2].toInt()
            val outW = shape[shape.size - 1].toInt()
            val outCount = outH * outW
            if (probBuf.size != outCount) probBuf = FloatArray(outCount)
            val outFb = detOut.floatBuffer
            outFb.position(0)
            outFb.get(probBuf, 0, outCount)
            if (prob.rows() != outH || prob.cols() != outW) {
                prob.release()
                prob = Mat(outH, outW, CvType.CV_32FC1)
            }
            prob.put(0, 0, probBuf)
            dump(prob, "02_prob.png", 255.0)

            dbPostProcess(prob, boxes, 1.0 / scale)
        } finally {
            result?.close()
            tensor?.close()
        }
        return boxes
    }

    /** PaddleOCR DBPostProcess (thresh / box_thresh / unclip), coordinates scaled back to the source image. */
    private fun dbPostProcess(prob: Mat, out: MutableList<FloatArray>, backScale: Double) {
        val binary = Mat()
        Imgproc.threshold(prob, binary, DET_THRESH.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        val mask8 = Mat()
        binary.convertTo(mask8, CvType.CV_8UC1)
        binary.release()

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask8, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        mask8.release()
        hierarchy.release()
        Log.i(TAG, "db: prob=${prob.cols()}x${prob.rows()} contours=${contours.size}")

        for (contour in contours) {
            if (contour.total() < 4) {
                contour.release()
                continue
            }
            val c2f = MatOfPoint2f(*contour.toArray())
            val rect = Imgproc.minAreaRect(c2f)
            val bRect = Imgproc.boundingRect(contour)
            Log.d(
                TAG,
                "db cand: bnd=[${bRect.x},${bRect.y},${bRect.width}x${bRect.height}] " +
                    "rect c=(${rect.center.x},${rect.center.y}) s=(${rect.size.width},${rect.size.height}) ang=${rect.angle}"
            )
            val (box, side) = miniBoxes(rect)
            val score = boxScoreFast(prob, box)
            Log.d(TAG, "db cand: pts=${contour.total()} box=${box.toList()} side=$side score=$score")
            if (side < MIN_BOX_SIDE) {
                contour.release(); c2f.release()
                continue
            }
            if (score < BOX_THRESH) {
                contour.release(); c2f.release()
                continue
            }
            // unclip: offset the min-area rectangle outward (equivalent to pyclipper JT_ROUND for a quad)
            val w = rect.size.width
            val h = rect.size.height
            val perimeter = 2.0 * (w + h)
            if (perimeter <= 0.0) {
                contour.release(); c2f.release()
                continue
            }
            val distance = (w * h) * UNCLIP_RATIO / perimeter
            val expanded = RotatedRect(rect.center, Size(w + 2 * distance, h + 2 * distance), rect.angle)
            val (finalBox, finalSide) = miniBoxes(expanded)
            c2f.release()
            contour.release()
            if (finalSide < UNCLIP_MIN_SIDE) continue

            for (i in finalBox.indices) {
                finalBox[i] = (finalBox[i] * backScale).toFloat()
            }
            out.add(finalBox)
        }
    }

    /**
     * PaddleOCR get_mini_boxes: the 4 min-area-rect corners reordered to (tl, tr, br, bl).
     * Uses explicit corner math identical to cv2.boxPoints / OpenCV's internal implementation,
     * because the Java binding writing into a fresh Mat via `Imgproc.boxPoints` returns zeros.
     */
    private fun miniBoxes(rect: RotatedRect): Pair<FloatArray, Double> {
        val pts = boxPoints(rect)
        val sorted = pts.sortedBy { it.x }
        val i1: Int; val i4: Int
        if (sorted[1].y > sorted[0].y) { i1 = 0; i4 = 1 } else { i1 = 1; i4 = 0 }
        val i2: Int; val i3: Int
        if (sorted[3].y > sorted[2].y) { i2 = 2; i3 = 3 } else { i2 = 3; i3 = 2 }

        val order = intArrayOf(i1, i2, i3, i4)
        val box = FloatArray(8)
        for (k in 0 until 4) {
            box[k * 2] = sorted[order[k]].x.toFloat()
            box[k * 2 + 1] = sorted[order[k]].y.toFloat()
        }
        return box to min(rect.size.width, rect.size.height)
    }

    /**
     * RotatedRect -> 4 corners, matching cv2.boxPoints for both angle conventions used by
     * OpenCV 4.5+ (angle 0 + wide size, or angle 90 + tall size):
     *   a = cos(angle)/2, b = sin(angle)/2
     *   p0 = (cx - a*w + b*h, cy - b*w - a*h)   // top-left
     *   p1 = (cx + a*w + b*h, cy + b*w - a*h)   // top-right
     *   p2 = (cx + a*w - b*h, cy + b*w + a*h)   // bottom-right
     *   p3 = (cx - a*w - b*h, cy - b*w + a*h)   // bottom-left
     * (The Java binding's `Imgproc.boxPoints(rect, mat)` leaves a freshly allocated Mat empty, so
     * the corners are computed here; PaddleOCR's get_mini_boxes re-sorts them anyway.)
     */
    private fun boxPoints(rect: RotatedRect): Array<Point> {
        val angle = Math.toRadians(rect.angle.toDouble())
        val a = kotlin.math.cos(angle) * 0.5
        val b = kotlin.math.sin(angle) * 0.5
        val cx = rect.center.x
        val cy = rect.center.y
        val w = rect.size.width
        val h = rect.size.height
        val p0 = Point(cx - a * w + b * h, cy - b * w - a * h)
        val p1 = Point(cx + a * w + b * h, cy + b * w - a * h)
        val p2 = Point(cx + a * w - b * h, cy + b * w + a * h)
        val p3 = Point(cx - a * w - b * h, cy - b * w + a * h)
        return arrayOf(p0, p1, p2, p3)
    }

    /** PaddleOCR box_score_fast: mean probability inside the polygon. */
    private fun boxScoreFast(prob: Mat, box: FloatArray): Double {
        val w = prob.cols()
        val h = prob.rows()
        var xmin = Int.MAX_VALUE; var xmax = Int.MIN_VALUE
        var ymin = Int.MAX_VALUE; var ymax = Int.MIN_VALUE
        for (i in 0 until 4) {
            val x = box[i * 2]
            val y = box[i * 2 + 1]
            xmin = min(xmin, floor(x).toInt()); xmax = max(xmax, ceil(x).toInt())
            ymin = min(ymin, floor(y).toInt()); ymax = max(ymax, ceil(y).toInt())
        }
        xmin = xmin.coerceIn(0, w - 1); xmax = xmax.coerceIn(0, w - 1)
        ymin = ymin.coerceIn(0, h - 1); ymax = ymax.coerceIn(0, h - 1)

        val mask = Mat.zeros(ymax - ymin + 1, xmax - xmin + 1, CvType.CV_8UC1)
        val poly = MatOfPoint(
            Point((box[0] - xmin).toDouble(), (box[1] - ymin).toDouble()),
            Point((box[2] - xmin).toDouble(), (box[3] - ymin).toDouble()),
            Point((box[4] - xmin).toDouble(), (box[5] - ymin).toDouble()),
            Point((box[6] - xmin).toDouble(), (box[7] - ymin).toDouble())
        )
        Imgproc.fillPoly(mask, listOf(poly), Scalar(1.0))
        val roi = prob.submat(ymin, ymax + 1, xmin, xmax + 1)
        val mean = Core.mean(roi, mask).`val`[0]
        roi.release(); mask.release(); poly.release()
        return mean
    }

    // ------------------------------------------------------------------ recognition

    private fun recognize(bgr: Mat, boxes: List<FloatArray>): List<OcrResult> {
        val results = ArrayList<OcrResult>(boxes.size)
        // PaddleOCR uses one dynamic width for the whole batch: imgW = int(48 * max aspect)
        var maxRatio = 1.0
        for (box in boxes) {
            val cropW = max(dist(box, 0, 1), dist(box, 2, 3))
            val cropH = max(dist(box, 0, 3), dist(box, 1, 2))
            if (cropH > 0) maxRatio = max(maxRatio, cropW / cropH)
        }
        for (box in boxes) {
            var crop = rotateCrop(bgr, box)
            try {
                if (crop.rows() < 2 || crop.cols() < 2) continue
                if (cls != null && isUpsideDown(crop)) {
                    val flipped = Mat()
                    Core.rotate(crop, flipped, Core.ROTATE_180)
                    crop.release()
                    crop = flipped
                }
                val (text, conf) = recognizeLine(crop, maxRatio)
                if (text.isNotEmpty() && conf >= MIN_CONFIDENCE) {
                    results.add(OcrResult(text, conf, box.copyOf()))
                }
            } finally {
                if (!crop.empty()) crop.release()
            }
        }
        return results
    }

    /** PaddleOCR get_rotate_crop_image (+ np.rot90 for tall boxes). */
    private fun rotateCrop(img: Mat, box: FloatArray): Mat {
        val p0 = Point(box[0].toDouble(), box[1].toDouble())
        val p1 = Point(box[2].toDouble(), box[3].toDouble())
        val p2 = Point(box[4].toDouble(), box[5].toDouble())
        val p3 = Point(box[6].toDouble(), box[7].toDouble())
        val w = max(segLen(p0, p1), segLen(p2, p3)).toInt().coerceAtLeast(1)
        val h = max(segLen(p0, p3), segLen(p1, p2)).toInt().coerceAtLeast(1)

        val src = MatOfPoint2f(p0, p1, p2, p3)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(w.toDouble(), 0.0),
            Point(w.toDouble(), h.toDouble()),
            Point(0.0, h.toDouble())
        )
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val out = Mat()
        Imgproc.warpPerspective(
            img, out, m, Size(w.toDouble(), h.toDouble()),
            Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, Scalar(0.0, 0.0, 0.0)
        )
        src.release(); dst.release(); m.release()
        if (out.rows().toDouble() / out.cols().toDouble() >= 1.5) {
            val rotated = Mat()
            Core.rotate(out, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            out.release()
            return rotated
        }
        return out
    }

    /** PaddleOCR resize_norm_img for the recognition model, then CTC greedy decode. */
    private fun recognizeLine(crop: Mat, maxRatio: Double): Pair<String, Float> {
        val imgW = max((REC_HEIGHT * maxRatio).toInt(), REC_HEIGHT)
        val h = crop.rows()
        val w = crop.cols()
        val ratio = w.toDouble() / h
        val resizedW = min(imgW, ceil(REC_HEIGHT * ratio).toInt()).coerceAtLeast(1)

        val resized = Mat()
        Imgproc.resize(crop, resized, Size(resizedW.toDouble(), REC_HEIGHT.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val normalized = Mat()
        // (x/255 - 0.5) / 0.5  ==  x/127.5 - 1
        resized.convertTo(normalized, CvType.CV_32FC3, 1.0 / 127.5, -1.0)
        resized.release()

        val plane = REC_HEIGHT * imgW
        val fb = ByteBuffer.allocateDirect(3 * plane * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val ch = Mat()
        val rowBuf = FloatArray(resizedW * REC_HEIGHT)
        for (c in 0 until 3) {
            Core.extractChannel(normalized, ch, c)
            ch.get(0, 0, rowBuf)
            val base = c * plane
            for (r in 0 until REC_HEIGHT) {
                fb.position(base + r * imgW)
                fb.put(rowBuf, r * resizedW, resizedW)
            }
        }
        ch.release()
        normalized.release()

        fb.rewind()
        var tensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        try {
            tensor = OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, REC_HEIGHT.toLong(), imgW.toLong()))
            result = rec.run(mapOf("x" to tensor))
            val outTensor = result.get(0) as OnnxTensor
            val shape = outTensor.info.shape
            val steps = shape[1].toInt()
            val classes = shape[2].toInt()
            val outFb = outTensor.floatBuffer
            outFb.position(0)

            val sb = StringBuilder()
            var prev = -1
            var sum = 0f
            var count = 0
            for (t in 0 until steps) {
                val base = t * classes
                var best = 0
                var bestScore = outFb.get(base)
                for (k in 1 until classes) {
                    val v = outFb.get(base + k)
                    if (v > bestScore) {
                        bestScore = v
                        best = k
                    }
                }
                if (best != 0 && best != prev) {
                    sb.append(chars[best])
                    sum += bestScore
                    count++
                }
                prev = best
            }
            val conf = if (count > 0) sum / count else 0f
            Log.d(TAG, "rec: crop=${w}x${h} imgW=$imgW resizedW=$resizedW text='$sb' conf=$conf")
            return sb.toString() to conf
        } finally {
            result?.close()
            tensor?.close()
        }
    }

    /** PaddleOCR cls (text line orientation) — true when the line is 180° rotated. */
    private fun isUpsideDown(crop: Mat): Boolean {
        val session = cls ?: return false
        val imgH = REC_HEIGHT
        val imgW = CLS_WIDTH
        val h = crop.rows()
        val w = crop.cols()
        val ratio = w.toDouble() / h
        var resizedW = ceil(imgH * ratio).toInt()
        if (resizedW > imgW) resizedW = imgW
        resizedW = resizedW.coerceAtLeast(1)

        val resized = Mat()
        Imgproc.resize(crop, resized, Size(resizedW.toDouble(), imgH.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val normalized = Mat()
        resized.convertTo(normalized, CvType.CV_32FC3, 1.0 / 127.5, -1.0)
        resized.release()

        val fb = ByteBuffer.allocateDirect(3 * imgH * imgW * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val ch = Mat()
        val rowBuf = FloatArray(resizedW * imgH)
        for (c in 0 until 3) {
            Core.extractChannel(normalized, ch, c)
            ch.get(0, 0, rowBuf)
            for (r in 0 until imgH) {
                fb.position(c * imgH * imgW + r * imgW)
                fb.put(rowBuf, r * resizedW, resizedW)
            }
        }
        ch.release()
        normalized.release()

        fb.rewind()
        var tensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        try {
            tensor = OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, imgH.toLong(), imgW.toLong()))
            result = session.run(mapOf("x" to tensor))
            val out = result.get(0) as OnnxTensor
            val fbOut = out.floatBuffer
            fbOut.position(0)
            val s0 = fbOut.get(0)
            val s1 = fbOut.get(1)
            return s1 > s0 && s1 > 0.9f
        } catch (t: Throwable) {
            Log.w(TAG, "cls inference failed", t)
            return false
        } finally {
            result?.close()
            tensor?.close()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun inputBuffer(size: Int): FloatBuffer {
        if (detInputFloats < size) {
            detInput = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            detInputFloats = size
        }
        val fb = detInput
        fb.clear()
        // **limit 必须压到本次真实元素数**：这个缓冲是复用的，只增不减；上一次网络是
        // 736x864（缓存 1,907,712 个 float），这一次 736x832 只要 1,837,056 个，
        // clear() 只把 limit 置回 capacity，于是 ORT 直接抛
        // "Shape [1,3,736,832] requires 1837056 elements but the buffer has 1907712"，
        // 用户看到的就是"识别失败"（除了宽高比刚好是 832/864 的那种照片，其余都能过）。
        fb.limit(size)
        return fb
    }

    private fun ensureChBuf(size: Int): FloatArray {
        if (chBuf.size != size) chBuf = FloatArray(size)
        return chBuf
    }

    private fun dist(box: FloatArray, a: Int, b: Int): Double {
        val dx = (box[a * 2] - box[b * 2]).toDouble()
        val dy = (box[a * 2 + 1] - box[b * 2 + 1]).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    private fun segLen(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    /** Groups lines into rows (top to bottom) and orders each row left to right. */
    private fun sortReadingOrder(list: List<OcrResult>): List<OcrResult> {
        if (list.size < 2) return list
        val avgHeight = list.map { boxHeight(it) }.average().coerceAtLeast(1.0)
        return list.sortedWith(
            compareBy({ floor(it.minY / avgHeight).toInt() }, { it.minX })
        )
    }

    private fun boxHeight(r: OcrResult): Double =
        max(dist(r.box, 0, 3), dist(r.box, 1, 2))

    fun close() {
        runCatching { det.close() }
        runCatching { rec.close() }
        cls?.let { runCatching { it.close() } }
        runCatching { prob.release() }
    }
}
