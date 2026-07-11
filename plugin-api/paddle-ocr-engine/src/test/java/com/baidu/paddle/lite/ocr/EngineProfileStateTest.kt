package com.baidu.paddle.lite.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineProfileStateTest {

    @Test
    fun sameProfileDoesNotRequireReinitialization() {
        val state = EngineProfileState()
        val profile = profile()

        assertTrue(state.requiresInitialization(profile))
        state.markInitialized(profile)
        assertFalse(state.requiresInitialization(profile.copy()))
    }

    @Test
    fun modelAndRuntimeChangesRequireReinitialization() {
        val state = EngineProfileState()
        val initial = profile()
        state.markInitialized(initial)

        assertTrue(state.requiresInitialization(initial.copy(useSlim = false)))
        assertTrue(state.requiresInitialization(initial.copy(useOpenCL = true)))
        assertTrue(state.requiresInitialization(initial.copy(cpuThreadNum = 8)))
    }

    @Test
    fun resetRequiresInitializationAgain() {
        val state = EngineProfileState()
        val profile = profile()
        state.markInitialized(profile)

        state.reset()

        assertTrue(state.requiresInitialization(profile))
    }

    @Test
    fun failedProfileSwitchRequiresPreviousProfileToReinitialize() {
        val state = EngineProfileState()
        val initial = profile()
        state.markInitialized(initial)

        state.markInitializationStarted()

        assertTrue(state.requiresInitialization(initial))
    }

    private fun profile() = ModelProfile(
        variantName = "v5",
        labelAssetPath = "labels/keys.txt",
        modelDir = null,
        useSlim = true,
        useOpenCL = false,
        cpuThreadNum = 4,
        detModelFile = "det.nb",
        recModelFile = "rec.nb",
        clsModelFile = "cls.nb",
        assetsToCheck = listOf("labels/keys.txt", "models/det.nb"),
    )
}
