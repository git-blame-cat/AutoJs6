package io.github.supermonster003.autojs6.plugin.paddleocr.v5

internal object RawImageLayout {

    private const val BYTES_PER_PIXEL = 4
    const val MAX_RAW_IMAGE_BYTES = 64 * 1024 * 1024

    data class Spec(
        val width: Int,
        val height: Int,
        val stride: Int,
        val rowBytes: Int,
        val totalBytes: Int,
    )

    fun create(width: Int, height: Int, stride: Int): Spec {
        require(width > 0 && height > 0) { "invalid raw image dimensions: ${width}x$height" }
        val rowBytes = Math.multiplyExact(width, BYTES_PER_PIXEL)
        require(stride >= rowBytes) { "invalid raw image stride: $stride < $rowBytes" }
        val totalBytes = Math.multiplyExact(stride, height)
        require(totalBytes <= MAX_RAW_IMAGE_BYTES) { "raw image is too large: $totalBytes" }
        return Spec(width, height, stride, rowBytes, totalBytes)
    }

    fun packRows(raw: ByteArray, spec: Spec): ByteArray {
        require(raw.size >= spec.totalBytes) { "raw image buffer is too small: ${raw.size}/${spec.totalBytes}" }
        if (spec.stride == spec.rowBytes) {
            val packedBytes = spec.rowBytes * spec.height
            return if (raw.size == packedBytes) raw else raw.copyOf(packedBytes)
        }
        val packed = ByteArray(spec.rowBytes * spec.height)
        repeat(spec.height) { row ->
            raw.copyInto(
                destination = packed,
                destinationOffset = row * spec.rowBytes,
                startIndex = row * spec.stride,
                endIndex = row * spec.stride + spec.rowBytes,
            )
        }
        return packed
    }
}
