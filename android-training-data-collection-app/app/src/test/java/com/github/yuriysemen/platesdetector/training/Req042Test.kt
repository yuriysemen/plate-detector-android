package com.github.yuriysemen.platesdetector.training

import org.junit.Assert.*
import org.junit.Test

class Req042Test {

    @Test
    fun onlyTwoResolutionsOffered() {
        assertEquals(
            listOf(640 to 480, 1280 to 720),
            AnalysisResolution.entries.map { it.width to it.height }
        )
    }

    @Test
    fun onlyHdIsWide() {
        assertFalse(AnalysisResolution.LOW.isWide)
        assertTrue(AnalysisResolution.HD.isWide)
    }

    @Test
    fun allowedFrameSizesAcceptEitherOrientation() {
        assertTrue(AnalysisResolution.isAllowedFrameSize(640, 480))
        assertTrue(AnalysisResolution.isAllowedFrameSize(480, 640))
        assertTrue(AnalysisResolution.isAllowedFrameSize(1280, 720))
        assertTrue(AnalysisResolution.isAllowedFrameSize(720, 1280))
    }

    @Test
    fun otherFrameSizesRejected() {
        assertFalse(AnalysisResolution.isAllowedFrameSize(1280, 960)) // 4:3 "HD" CameraX would pick without a 16:9 pin
        assertFalse(AnalysisResolution.isAllowedFrameSize(1920, 1080))
        assertFalse(AnalysisResolution.isAllowedFrameSize(640, 640))
    }

    @Test
    fun legacyDefaultPreferenceNoLongerParses() {
        // A pre-REQ-042 install may have "DEFAULT" stored; ModelPrefs falls back to LOW when this throws.
        assertTrue(runCatching { AnalysisResolution.valueOf("DEFAULT") }.isFailure)
    }

    private val meta = DatasetExporter.DeviceMetadata("Pixel", "Google", "15", 35, "abcd1234abcd1234")

    @Test
    fun dataYamlListsFrameSizes() {
        val stats = DatasetExporter.Stats(
            3, 3, null, null, "m", "1.0",
            frameSizes = mapOf("640x480" to 2, "480x640" to 1)
        )
        val yaml = DatasetExporter.buildDataYaml(stats, meta, "2026-09-21T10:00:00")
        assertTrue(yaml, yaml.contains("  frame_sizes:\n    \"480x640\": 1\n    \"640x480\": 2\n"))
    }

    @Test
    fun dataYamlOmitsFrameSizesWhenUnknown() {
        val stats = DatasetExporter.Stats(0, 0, null, null, null, null)
        assertFalse(DatasetExporter.buildDataYaml(stats, meta, "t").contains("frame_sizes"))
    }
}
