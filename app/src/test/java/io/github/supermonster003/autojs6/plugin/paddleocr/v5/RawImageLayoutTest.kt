package io.github.supermonster003.autojs6.plugin.paddleocr.v5

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RawImageLayoutTest {

    @Test
    fun rejectsStrideSmallerThanPixelRow() {
        assertThrows(IllegalArgumentException::class.java) {
            RawImageLayout.create(width = 3, height = 2, stride = 11)
        }
    }

    @Test
    fun rejectsIntegerOverflow() {
        assertThrows(ArithmeticException::class.java) {
            RawImageLayout.create(width = Int.MAX_VALUE, height = 2, stride = Int.MAX_VALUE)
        }
    }

    @Test
    fun removesPaddingFromEveryRow() {
        val spec = RawImageLayout.create(width = 1, height = 2, stride = 6)
        val raw = byteArrayOf(1, 2, 3, 4, 90, 91, 5, 6, 7, 8, 92, 93)

        assertEquals(8, spec.rowBytes * spec.height)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), RawImageLayout.packRows(raw, spec))
    }

    @Test
    fun reusesExactTightlyPackedBuffer() {
        val spec = RawImageLayout.create(width = 1, height = 2, stride = 4)
        val raw = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        assertSame(raw, RawImageLayout.packRows(raw, spec))
    }

    @Test
    fun rejectsRawImageAboveMemoryLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            RawImageLayout.create(width = 4097, height = 4096, stride = 4097 * 4)
        }
    }
}
