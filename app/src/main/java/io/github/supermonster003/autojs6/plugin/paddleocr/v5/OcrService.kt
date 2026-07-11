package io.github.supermonster003.autojs6.plugin.paddleocr.v5

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.annotation.RequiresApi
import com.baidu.paddle.lite.ocr.PaddleOcrEngine
import com.baidu.paddle.lite.ocr.VariantSpec
import org.autojs.autojs6.BuildConfig
import org.autojs.autojs6.R
import org.autojs.plugin.paddle.ocr.api.IOcrPlugin
import org.autojs.plugin.paddle.ocr.api.OcrOptions
import org.autojs.plugin.paddle.ocr.api.OcrResult
import org.autojs.plugin.paddle.ocr.api.PluginInfo
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OcrService : Service() {

    private val engineDelegate = lazy { PaddleOcrEngine(this, VariantSpec.v5()) }
    private val engine by engineDelegate

    private val binder = object : IOcrPlugin.Stub() {

        override fun getInfo(): PluginInfo {
            ensureNativeRuntimeAvailable()
            return PluginInfo().apply {
                name = "Paddle OCR (PP-OCRv5)"
                author = "SuperMonster003"
                id = "paddle-ocr-pp-ocrv5"
                engine = "paddle-ocr"
                variant = "v5"
                description = getString(R.string.plugin_description)
                versionName = BuildConfig.VERSION_NAME
                versionCode = BuildConfig.VERSION_CODE.toLong()
                versionDate = BuildConfig.VERSION_DATE
                capabilities = android.os.Bundle().apply {
                    putBoolean("supportsCls", false)
                    putBoolean("supportsRawImage", true)
                    putLong(CAPABILITY_MAX_RAW_IMAGE_BYTES, RawImageLayout.MAX_RAW_IMAGE_BYTES.toLong())
                    putString("labels", "labels/ppocr_keys_ocrv5.txt")
                }
            }
        }

        override fun recognizeText(
            image: ParcelFileDescriptor,
            options: OcrOptions,
        ): List<String> {
            ensureNativeRuntimeAvailable()
            return useDecodedBitmap(image, options) { bitmap ->
                engine.recognizeText(bitmap, options)
            }
        }

        override fun detect(
            image: ParcelFileDescriptor,
            options: OcrOptions,
        ): List<OcrResult> {
            ensureNativeRuntimeAvailable()
            return useDecodedBitmap(image, options) { bitmap ->
                engine.detect(bitmap, options)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        try {
            if (engineDelegate.isInitialized()) {
                engine.close()
            }
        } finally {
            super.onDestroy()
        }
    }

    private inline fun <T> useDecodedBitmap(
        descriptor: ParcelFileDescriptor,
        options: OcrOptions,
        block: (Bitmap) -> T,
    ): T {
        val bitmap = try {
            decodeImage(descriptor, options)
        } catch (t: Throwable) {
            runCatching { descriptor.close() }
            throw t
        }
        return try {
            block(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeImage(descriptor: ParcelFileDescriptor, options: OcrOptions): Bitmap {
        val extras = options.extras
        val useRaw = extras?.getBoolean(EXTRA_RAW_IMAGE, false) == true
        if (useRaw) {
            val width = extras.getInt(EXTRA_RAW_WIDTH, -1)
            val height = extras.getInt(EXTRA_RAW_HEIGHT, -1)
            val stride = extras.getInt(EXTRA_RAW_STRIDE, width * 4)
            val layout = RawImageLayout.create(width, height, stride)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val decoded = tryDecodeSharedMemory(descriptor, layout)
                if (decoded != null) {
                    runCatching { descriptor.close() }
                    return decoded
                }
            }
            return decodeRawStream(descriptor, layout)
        }
        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        return descriptor.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, decodeOptions) }
            ?: error("decode image failed")
    }

    private fun decodeRawStream(descriptor: ParcelFileDescriptor, layout: RawImageLayout.Spec): Bitmap {
        val raw = ByteArray(layout.totalBytes)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            var offset = 0
            while (offset < layout.totalBytes) {
                val read = input.read(raw, offset, layout.totalBytes - offset)
                if (read < 0) break
                offset += read
            }
            if (offset < layout.totalBytes) {
                throw IllegalStateException("read raw image failed: $offset/${layout.totalBytes}")
            }
        }
        val packed = RawImageLayout.packRows(raw, layout)
        return createBitmap(layout, ByteBuffer.wrap(packed))
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun tryDecodeSharedMemory(descriptor: ParcelFileDescriptor, layout: RawImageLayout.Spec): Bitmap? {
        val dup = try {
            ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        } catch (_: IOException) {
            return null
        }
        val shm = try {
            SharedMemory.fromFileDescriptor(dup)
        } catch (_: IllegalArgumentException) {
            runCatching { dup.close() }
            return null
        }
        var buffer: ByteBuffer? = null
        try {
            val mapped = shm.mapReadOnly().apply { order(ByteOrder.nativeOrder()) }
            buffer = mapped
            check(mapped.capacity() >= layout.totalBytes) {
                "shared memory is too small: ${mapped.capacity()}/${layout.totalBytes}"
            }
            val raw = ByteArray(layout.totalBytes)
            mapped.get(raw)
            return createBitmap(layout, ByteBuffer.wrap(RawImageLayout.packRows(raw, layout)))
        } finally {
            buffer?.let(SharedMemory::unmap)
            shm.close()
        }
    }

    private fun createBitmap(layout: RawImageLayout.Spec, pixels: ByteBuffer): Bitmap {
        val bitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.copyPixelsFromBuffer(pixels)
            bitmap
        } catch (t: Throwable) {
            bitmap.recycle()
            throw t
        }
    }

    private fun ensureNativeRuntimeAvailable() {
        val nativeLibraryDir = File(applicationInfo.nativeLibraryDir)
        val missingLibraries = REQUIRED_NATIVE_LIBRARIES.filterNot { name ->
            File(nativeLibraryDir, name).isFile
        }
        check(missingLibraries.isEmpty()) {
            getString(
                R.string.error_paddle_ocr_native_runtime_unavailable_for_current_abi,
                Build.SUPPORTED_ABIS.joinToString(),
                missingLibraries.joinToString(),
            )
        }
    }

    companion object {
        private val REQUIRED_NATIVE_LIBRARIES = listOf(
            "libNative.so",
            "libc++_shared.so",
            "libopencl_probe.so",
            "libopencv_java4.so",
            "libpaddle_light_api_shared.so",
        )
        private const val EXTRA_RAW_IMAGE = "rawImage"
        private const val EXTRA_RAW_WIDTH = "rawWidth"
        private const val EXTRA_RAW_HEIGHT = "rawHeight"
        private const val EXTRA_RAW_STRIDE = "rawStride"
        private const val CAPABILITY_MAX_RAW_IMAGE_BYTES = "maxRawImageBytes"
    }
}
