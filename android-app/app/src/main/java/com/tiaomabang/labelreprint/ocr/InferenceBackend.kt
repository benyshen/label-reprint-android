package com.tiaomabang.labelreprint.ocr

/**
 * Execution provider used for the three ONNX models.
 *
 * PP-OCRv4 ships as ONNX, which is backend agnostic, so the only real question is which execution
 * provider runs the graph. This ORT build exposes NNAPI (and no QNN), so:
 *
 *  - [CPU]   : plain multi-threaded CPU (MLAS/XNNPACK).
 *  - [NNAPI] : NNAPI execution provider. Supported subgraphs are handed to the platform driver,
 *              which on Qualcomm devices dispatches them to the Hexagon NPU/DSP; ops the driver
 *              cannot take stay on the CPU inside the same session (hence "partial" acceleration).
 */
enum class InferenceBackend(val id: String, val label: String, val detail: String) {
    CPU("cpu", "CPU", "多线程 CPU 推理"),
    NNAPI("nnapi", "NPU", "Hexagon NPU/DSP（NNAPI 通道，不支持的算子回退 CPU）");

    companion object {
        fun fromId(id: String?): InferenceBackend = entries.firstOrNull { it.id == id } ?: CPU
    }
}
