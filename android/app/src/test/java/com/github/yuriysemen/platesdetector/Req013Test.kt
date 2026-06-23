package com.github.yuriysemen.platesdetector

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Req013Test {

    // ── File naming ───────────────────────────────────────────────────────────

    @Test
    fun frameNameMatchesDateTimeSeqPattern() {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
        val date = Date()
        val seq = 1
        val name = "${fmt.format(date)}_${"%06d".format(seq)}"
        val pattern = Regex("""^\d{8}_\d{6}_\d{6}$""")
        assertTrue("Name '$name' does not match YYYYMMDD_HHmmss_NNNNNN", pattern.matches(name))
    }

    @Test
    fun imageAndLabelShareBaseName() {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
        val base = "${fmt.format(Date())}_${"%06d".format(42)}"
        assertEquals("$base.jpg".substringBeforeLast('.'), "$base.txt".substringBeforeLast('.'))
    }

    @Test
    fun seqIsZeroPaddedToSixDigits() {
        assertEquals("000001", "%06d".format(1))
        assertEquals("000999", "%06d".format(999))
        assertEquals("999999", "%06d".format(999999))
    }

    // ── Device ID ─────────────────────────────────────────────────────────────

    @Test
    fun computeDeviceIdIsExactly16HexChars() {
        val id = DatasetExporter.computeDeviceId("test-android-id")
        assertEquals(16, id.length)
        assertTrue("Not all hex: $id", id.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun computeDeviceIdDoesNotContainRawInput() {
        val raw = "my-secret-android-id"
        val id = DatasetExporter.computeDeviceId(raw)
        assertFalse(id.contains(raw))
    }

    @Test
    fun computeDeviceIdIsDeterministic() {
        val raw = "same-id"
        assertEquals(DatasetExporter.computeDeviceId(raw), DatasetExporter.computeDeviceId(raw))
    }

    @Test
    fun computeDeviceIdDiffersForDifferentInputs() {
        assertNotEquals(
            DatasetExporter.computeDeviceId("device-a"),
            DatasetExporter.computeDeviceId("device-b")
        )
    }

    @Test
    fun computeDeviceIdHandlesEmptyInput() {
        val id = DatasetExporter.computeDeviceId("")
        assertEquals(16, id.length)
    }

    // ── data.yaml ─────────────────────────────────────────────────────────────

    private fun makeStats(modelId: String? = "asset:model_v1") = DatasetExporter.Stats(
        totalFrames = 100,
        totalDetections = 150,
        collectedFrom = "2026-06-22T10:00:00",
        collectedTo = "2026-06-22T11:00:00",
        modelId = modelId,
        appVersion = "1.3.0"
    )

    private fun makeMeta() = DatasetExporter.DeviceMetadata(
        phoneModel = "Pixel 8",
        phoneManufacturer = "Google",
        androidVersion = "14",
        androidSdk = 34,
        deviceId = DatasetExporter.computeDeviceId("test-id")
    )

    @Test
    fun buildDataYamlContainsStandardYoloFields() {
        val yaml = DatasetExporter.buildDataYaml(makeStats(), makeMeta(), "2026-06-22T14:30:00")
        assertTrue(yaml.contains("train: train/images"))
        assertTrue(yaml.contains("val: val/images"))
        assertTrue(yaml.contains("test: test/images"))
        assertTrue(yaml.contains("nc: 1"))
        assertTrue(yaml.contains("names: ['License_Plate']"))
    }

    @Test
    fun buildDataYamlContainsAllDeviceFields() {
        val meta = makeMeta()
        val yaml = DatasetExporter.buildDataYaml(makeStats(), meta, "2026-06-22T14:30:00")
        assertTrue(yaml.contains("phone_model:"))
        assertTrue(yaml.contains("phone_manufacturer:"))
        assertTrue(yaml.contains("android_version:"))
        assertTrue(yaml.contains("android_sdk:"))
        assertTrue(yaml.contains("app_version:"))
        assertTrue(yaml.contains("model_id:"))
        assertTrue(yaml.contains("export_timestamp:"))
        assertTrue(yaml.contains("device_id:"))
        assertTrue(yaml.contains("total_frames:"))
        assertTrue(yaml.contains("total_detections:"))
        assertTrue(yaml.contains("collected_from:"))
        assertTrue(yaml.contains("collected_to:"))
    }

    @Test
    fun buildDataYamlEmbedsMeta() {
        val meta = makeMeta()
        val yaml = DatasetExporter.buildDataYaml(makeStats(), meta, "2026-06-22T14:30:00")
        assertTrue(yaml.contains("Pixel 8"))
        assertTrue(yaml.contains("Google"))
        assertTrue(yaml.contains("\"14\""))
        assertTrue(yaml.contains("34"))
        assertTrue(yaml.contains("1.3.0"))  // appVersion from stats
        assertTrue(yaml.contains(meta.deviceId))
    }

    @Test
    fun buildDataYamlEmbedsStats() {
        val yaml = DatasetExporter.buildDataYaml(makeStats(), makeMeta(), "2026-06-22T14:30:00")
        assertTrue(yaml.contains("100"))
        assertTrue(yaml.contains("150"))
        assertTrue(yaml.contains("2026-06-22T10:00:00"))
        assertTrue(yaml.contains("2026-06-22T11:00:00"))
    }

    @Test
    fun buildDataYamlFallsBackWhenModelIdIsNull() {
        val yaml = DatasetExporter.buildDataYaml(makeStats(modelId = null), makeMeta(), "2026-06-22T14:30:00")
        assertTrue(yaml.contains("model_id: \"unknown\""))
    }

    @Test
    fun buildDataYamlOmitsCollectedDatesWhenNull() {
        val stats = DatasetExporter.Stats(10, 15, null, null, "model", "1.0.0")
        val yaml = DatasetExporter.buildDataYaml(stats, makeMeta(), "2026-06-22T14:30:00")
        assertFalse(yaml.contains("collected_from"))
        assertFalse(yaml.contains("collected_to"))
    }

    @Test
    fun buildDataYamlEscapesQuotesInDeviceFields() {
        val meta = makeMeta().copy(phoneModel = "Phone \"X\" Pro")
        val yaml = DatasetExporter.buildDataYaml(makeStats(), meta, "2026-06-22T14:30:00")
        assertTrue(yaml.contains("\\\"X\\\""))
    }
}
