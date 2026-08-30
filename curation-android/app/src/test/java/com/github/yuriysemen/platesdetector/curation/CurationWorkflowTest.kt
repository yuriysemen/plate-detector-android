package com.github.yuriysemen.platesdetector.curation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageIdTest {

    @Test
    fun derivesFromKeyParts() {
        assertEquals(
            "abc-123__deadbeefcafe0000__plates_dataset_20260101_120000",
            packageIdOf("abc-123", "deadbeefcafe0000", "plates_dataset_20260101_120000.zip"),
        )
    }

    @Test
    fun sanitizesUnsafeCharacters() {
        assertEquals("a_b__c_d__e_f", packageIdOf("a/b", "c d", "e+f.zip"))
    }

    @Test
    fun parseUploadKeyHappyPath() {
        val ref = parseUploadKey("uploads/sub1/dev1/file.zip", 100, 42)!!
        assertEquals("sub1", ref.userSub)
        assertEquals("dev1", ref.deviceId)
        assertEquals("file.zip", ref.filename)
        assertEquals("sub1__dev1__file", ref.packageId)
    }

    @Test
    fun parseUploadKeyRejectsNonUploads() {
        assertNull(parseUploadKey("curation/x/manifest.json", 0, 0))
        assertNull(parseUploadKey("uploads/sub/dev/notazip.txt", 0, 0))
        assertNull(parseUploadKey("uploads/sub/dev/a/b.zip", 0, 0))
    }
}

class YoloLabelTest {

    @Test
    fun parsesLinesIgnoringBlanksAndCommas() {
        val boxes = YoloLabel.parse("0 0.5 0.5 0.1 0.2\n\n0 0,25 0,25 0,1 0,1\n")
        assertEquals(2, boxes.size)
        assertEquals(0.25f, boxes[1].xCenter)
    }

    @Test
    fun emptyOrNullIsNoBoxes() {
        assertTrue(YoloLabel.parse(null).isEmpty())
        assertTrue(YoloLabel.parse("   ").isEmpty())
    }

    @Test
    fun formatRoundTrips() {
        val src = listOf(YoloBox(0, 0.5f, 0.5f, 0.123456f, 0.2f))
        val reparsed = YoloLabel.parse(YoloLabel.format(src))
        assertEquals(1, reparsed.size)
        assertEquals(0.123456f, reparsed[0].width, 1e-6f)
    }
}

class CurationManifestTest {

    private fun sample() = CurationManifest(
        packageId = "p1", sourceKey = "uploads/s/d/f.zip", filename = "f.zip",
        userSub = "s", deviceId = "d", startedAt = "2026-08-30T12:00:00Z",
        curatorEmail = "c@example.com",
        items = listOf(
            ManifestItem("train", "a", "a.jpg"),
            ManifestItem("train", "b", "b.jpg"),
            ManifestItem("val", "c", "c.jpg"),
        ),
    )

    @Test
    fun jsonRoundTrip() {
        val m = sample()
            .withDecision(0, ItemStatus.ACCEPTED, labelContent = "0 0.5 0.5 0.1 0.1",
                decidedBy = "a@x.com", decidedAt = "2026-08-30T12:01:00Z")
            .withDecision(2, ItemStatus.REJECTED, reason = "blurry",
                decidedBy = "b@x.com", decidedAt = "2026-08-30T12:02:00Z")
        val back = CurationManifest.fromJson(m.toJson())
        assertEquals(m, back)
        assertEquals(1, back.accepted)
        assertEquals(1, back.rejected)
        assertEquals(1, back.pending)
        assertEquals(3, back.total)
        assertEquals("a@x.com", back.items[0].decidedBy)
        assertEquals("2026-08-30T12:02:00Z", back.items[2].decidedAt)
    }

    @Test
    fun reviewersTallyByCurator() {
        val m = sample()
            .withDecision(0, ItemStatus.ACCEPTED, labelContent = "x", decidedBy = "a@x.com")
            .withDecision(1, ItemStatus.REJECTED, decidedBy = "a@x.com")
            .withDecision(2, ItemStatus.ACCEPTED, labelContent = "x", decidedBy = "b@x.com")
        assertEquals(
            mapOf(
                "a@x.com" to ReviewerCount(accepted = 1, rejected = 1),
                "b@x.com" to ReviewerCount(accepted = 1, rejected = 0),
            ),
            m.reviewers(),
        )
    }

    @Test
    fun firstPendingIndexAdvances() {
        val m = sample().withDecision(0, ItemStatus.ACCEPTED, labelContent = "x")
        assertEquals(1, m.firstPendingIndex())
        val all = m.withDecision(1, ItemStatus.REJECTED).withDecision(2, ItemStatus.REJECTED)
        assertEquals(-1, all.firstPendingIndex())
    }

    @Test
    fun rejectDropsLabelContent_acceptDropsReason() {
        val m = sample()
            .withDecision(0, ItemStatus.ACCEPTED, labelContent = "L", reason = "ignored")
            .withDecision(1, ItemStatus.REJECTED, labelContent = "ignored", reason = "R")
        assertEquals("L", m.items[0].labelContent)
        assertNull(m.items[0].reason)
        assertNull(m.items[1].labelContent)
        assertEquals("R", m.items[1].reason)
    }

    @Test
    fun perSubsetAcceptedCounts() {
        val m = sample()
            .withDecision(0, ItemStatus.ACCEPTED, labelContent = "x")
            .withDecision(1, ItemStatus.ACCEPTED, labelContent = "x")
            .withDecision(2, ItemStatus.ACCEPTED, labelContent = "x")
        assertEquals(mapOf("train" to 2, "val" to 1), m.perSubsetAccepted())
    }
}

class DoneManifestTest {

    @Test
    fun jsonRoundTrip() {
        val d = DoneManifest(
            packageId = "p1", filename = "f.zip",
            curatorEmail = "c@example.com", completedBy = "d@example.com",
            startedAt = "2026-08-30T12:00:00Z", completedAt = "2026-08-30T13:00:00Z",
            total = 10, accepted = 7, rejected = 3,
            perSubsetAccepted = mapOf("train" to 5, "val" to 2),
            reviewers = mapOf(
                "c@example.com" to ReviewerCount(4, 1),
                "d@example.com" to ReviewerCount(3, 2),
            ),
        )
        assertEquals(d, DoneManifest.fromJson(d.toJson()))
    }
}
