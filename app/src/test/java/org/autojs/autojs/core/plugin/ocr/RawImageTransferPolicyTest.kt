package org.autojs.autojs.core.plugin.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawImageTransferPolicyTest {

    @Test
    fun acceptsImageAtAdvertisedLimit() {
        assertTrue(
            RawImageTransferPolicy.canTransferRaw(
                rowBytes = 4096L * 4,
                height = 4096,
                maxBytes = 64L * 1024 * 1024,
            ),
        )
    }

    @Test
    fun rejectsImageAboveAdvertisedLimit() {
        assertFalse(
            RawImageTransferPolicy.canTransferRaw(
                rowBytes = 4097L * 4,
                height = 4096,
                maxBytes = 64L * 1024 * 1024,
            ),
        )
    }

    @Test
    fun preservesRawForPluginsWithoutAdvertisedLimit() {
        assertTrue(
            RawImageTransferPolicy.canTransferRaw(
                rowBytes = 8192L * 4,
                height = 8192,
                maxBytes = null,
            ),
        )
    }
}
