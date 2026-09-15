package com.github.yuriysemen.platesdetector.curation

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── REQ-035: completed-package archive naming ───────────────────────────

    @Test
    fun doneAndRejectedBaseKeysMirrorUploadPath() {
        assertEquals(
            "done/sub1/dev1/file-curated-20260101_120000",
            doneBaseKey("sub1", "dev1", "file.zip", "20260101_120000"),
        )
        assertEquals(
            "rejected/sub1/dev1/file-rejected-20260101_120000",
            rejectedBaseKey("sub1", "dev1", "file.zip", "20260101_120000"),
        )
    }

    @Test
    fun packageIdFromDoneManifestKeyRoundTripsWithPackageIdOf() {
        val key = "${doneBaseKey("sub1", "dev1", "file.zip", "20260101_120000")}._manifest.json"
        assertEquals(packageIdOf("sub1", "dev1", "file.zip"), packageIdFromDoneManifestKey(key))
    }

    @Test
    fun packageIdFromDoneManifestKeyRejectsOldFlatShape() {
        // Pre-REQ-035 shape: done/<packageId>/_manifest.json — only two segments after "done/",
        // not the three this parser expects. CurationRepository falls back to a separate,
        // string-based path for this shape rather than this function returning something wrong.
        assertNull(packageIdFromDoneManifestKey("done/sub1__dev1__file/_manifest.json"))
    }

    @Test
    fun packageIdFromDoneManifestKeyRejectsNonDonePrefix() {
        assertNull(packageIdFromDoneManifestKey("rejected/sub1/dev1/file-rejected-20260101_120000.zip"))
        assertNull(packageIdFromDoneManifestKey("done/sub1/dev1/file-curated-20260101_120000.zip"))
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

    @Test
    fun formatAppliesClassOverrides() {
        val src = listOf(
            YoloBox(0, 0.1f, 0.1f, 0.1f, 0.1f),
            YoloBox(0, 0.2f, 0.2f, 0.1f, 0.1f),
        )
        val out = YoloLabel.format(src, listOf(2, null))  // null keeps the box's own class
        assertEquals(listOf("2", "0"), out.lines().map { it.substringBefore(' ') })
    }
}

class VehicleCategoriesTest {

    private val json = """
        {"version":3,"classes":[
          {"id":0,"key":"license_plate","label":"License plate"},
          {"id":2,"key":"police","label":"Police car"},
          {"id":1,"key":"civil","label":"Civil car"}
        ]}
    """.trimIndent()

    @Test
    fun parsesAndSortsById() {
        val c = VehicleCategories.fromJson(json)
        assertEquals(3, c.version)
        assertEquals(listOf(0, 1, 2), c.classes.map { it.id })
        assertEquals("Police car", c.labelFor(2))
        assertTrue(c.contains(1))
        assertFalse(c.contains(9))
    }

    @Test
    fun orderedNamesFillsById() {
        assertEquals(listOf("license_plate", "civil", "police"),
            VehicleCategories.fromJson(json).orderedNames())
    }

    @Test
    fun toJsonObjectRoundTrips() {
        // The manifest snapshot (REQ-025 offline-completion fix) embeds this — must survive a
        // full serialize/deserialize, not just the version number.
        val c = VehicleCategories.fromJson(json)
        val back = VehicleCategories.fromJsonObject(c.toJsonObject())
        assertEquals(c, back)
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

    // ── REQ-025 ───────────────────────────────────────────────────────────

    @Test
    fun boxClassesRoundTripWithNulls() {
        val m = sample().withItemBoxes(0, "0 0.5 0.5 0.1 0.1\n1 0.2 0.2 0.1 0.1", listOf(2, null))
        val back = CurationManifest.fromJson(m.toJson())
        assertEquals(listOf<Int?>(2, null), back.items[0].boxClasses)
        assertEquals("0 0.5 0.5 0.1 0.1\n1 0.2 0.2 0.1 0.1", back.items[0].workingLabel)
        assertEquals(1, back.categoryListVersion)
    }

    @Test
    fun allBoxesClassifiedGate() {
        val m = sample().withItemBoxes(0, "l", listOf(3, null))
        assertFalse(m.allBoxesClassified(0, boxCount = 2))
        val m2 = m.withItemBoxes(0, "l", listOf(3, 1))
        assertTrue(m2.allBoxesClassified(0, boxCount = 2))
        assertFalse(m2.allBoxesClassified(0, boxCount = 0))  // zero-box never acceptable
    }

    @Test
    fun classCountsOverAcceptedItems() {
        val cats = VehicleCategories.fromJson(
            """{"version":1,"classes":[
                 {"id":0,"key":"license_plate","label":"L"},
                 {"id":2,"key":"police","label":"P"}]}"""
        )
        val m = sample()
            .withDecision(0, ItemStatus.ACCEPTED,
                labelContent = "0 0.1 0.1 0.1 0.1\n2 0.2 0.2 0.1 0.1")
            .withDecision(1, ItemStatus.ACCEPTED, labelContent = "2 0.3 0.3 0.1 0.1")
            .withDecision(2, ItemStatus.REJECTED)
        assertEquals(mapOf("license_plate" to 1, "police" to 2), m.classCounts(cats))
    }

    @Test
    fun categorySnapshotRoundTripsWithManifest() {
        // Offline-completion fix: the package's own category-list snapshot must survive a
        // manifest write/read, not just its version number.
        val cats = VehicleCategories.fromJson(
            """{"version":2,"classes":[
                 {"id":0,"key":"license_plate","label":"L"},
                 {"id":6,"key":"other","label":"Other"}]}"""
        )
        val m = sample().copy(categoryListVersion = cats.version, categories = cats)
        val back = CurationManifest.fromJson(m.toJson())
        assertEquals(cats, back.categories)
        assertEquals(2, back.categoryListVersion)
    }

    @Test
    fun categorySnapshotAbsentOnLegacyManifest() {
        // A manifest written before this field existed has no "category_list" key at all.
        val back = CurationManifest.fromJson(sample().toJson())
        assertNull(back.categories)
    }
}

// ── REQ-024 ──────────────────────────────────────────────────────────────

class BoxGeometryTest {

    private val eps = 1e-5f
    private fun box(cx: Float, cy: Float, w: Float, h: Float) = YoloBox(0, cx, cy, w, h)

    @Test
    fun cornerResizeMovesOnlyThatCorner() {
        val out = BoxGeometry.applyHandle(box(0.5f, 0.5f, 0.2f, 0.2f), DragHandle.TL, dx = 0.05f, dy = 0.05f)
        assertEquals(0.15f, out.width, eps)
        assertEquals(0.15f, out.height, eps)
        assertEquals(0.6f, out.xCenter + out.width / 2f, eps)   // right edge unchanged
        assertEquals(0.6f, out.yCenter + out.height / 2f, eps)  // bottom edge unchanged
    }

    @Test
    fun edgeHandleResizeIsSingleAxis() {
        val out = BoxGeometry.applyHandle(box(0.5f, 0.5f, 0.2f, 0.2f), DragHandle.RM, dx = 0.1f, dy = 0f)
        assertEquals(0.3f, out.width, eps)
        assertEquals(0.2f, out.height, eps)  // untouched by a right-edge drag
        assertEquals(0.5f, out.yCenter, eps)
    }

    @Test
    fun moveTranslatesWithoutResizing() {
        val out = BoxGeometry.applyHandle(box(0.5f, 0.5f, 0.2f, 0.2f), DragHandle.MOVE, dx = 0.1f, dy = -0.1f)
        assertEquals(0.2f, out.width, eps)
        assertEquals(0.2f, out.height, eps)
        assertEquals(0.6f, out.xCenter, eps)
        assertEquals(0.4f, out.yCenter, eps)
    }

    @Test
    fun moveClampsAtImageBounds() {
        val out = BoxGeometry.applyHandle(box(0.9f, 0.5f, 0.1f, 0.1f), DragHandle.MOVE, dx = 0.5f, dy = 0f)
        assertEquals(0.1f, out.width, eps)  // size preserved, just pinned to the edge
        assertEquals(1f, out.xCenter + out.width / 2f, eps)
    }

    @Test
    fun resizeClampsAtImageBounds() {
        val out = BoxGeometry.applyHandle(box(0.9f, 0.5f, 0.1f, 0.1f), DragHandle.TR, dx = 0.5f, dy = -0.5f)
        assertEquals(1f, out.xCenter + out.width / 2f, eps)   // right edge pinned at 1
        assertEquals(0f, out.yCenter - out.height / 2f, eps)  // top edge pinned at 0
    }

    @Test
    fun resizeNeverShrinksBelowMinSize() {
        val out = BoxGeometry.applyHandle(box(0.5f, 0.5f, 0.1f, 0.1f), DragHandle.TL, dx = 0.5f, dy = 0.5f)
        assertTrue(out.width >= BoxGeometry.MIN_SIZE - eps)
        assertTrue(out.height >= BoxGeometry.MIN_SIZE - eps)
    }

    @Test
    fun resizeOnAlreadyTinyBoxDoesNotThrow() {
        // A box narrower than MIN_SIZE can exist from earlier real-world data; resizing it must
        // recover rather than crash on an inverted coerceIn range.
        val out = BoxGeometry.applyHandle(box(0.5f, 0.5f, 0.005f, 0.005f), DragHandle.LM, dx = 0.1f, dy = 0f)
        assertTrue(out.width >= BoxGeometry.MIN_SIZE - eps)
    }

    @Test
    fun hitTestPrefersSelectedBoxHandleOverAnotherBoxBody() {
        val a = box(0.5f, 0.5f, 0.2f, 0.2f)   // edges 0.4..0.6
        val b = box(0.55f, 0.55f, 0.3f, 0.3f) // edges 0.4..0.7 — covers a's TL handle too
        val hit = BoxGeometry.hitTest(
            Offset(0.4f, 0.4f), listOf(a, b), selected = 0,
            handleRadiusX = 0.02f, handleRadiusY = 0.02f,
        )
        assertEquals(0 to DragHandle.TL, hit)
    }

    @Test
    fun hitTestFallsBackToTopmostBoxBody() {
        val a = box(0.5f, 0.5f, 0.2f, 0.2f)
        val b = box(0.55f, 0.55f, 0.3f, 0.3f)
        val hit = BoxGeometry.hitTest(
            Offset(0.55f, 0.55f), listOf(a, b), selected = null,
            handleRadiusX = 0.02f, handleRadiusY = 0.02f,
        )
        assertEquals(1 to DragHandle.MOVE, hit)
    }

    @Test
    fun hitTestReturnsNullOnEmptySpace() {
        val a = box(0.5f, 0.5f, 0.2f, 0.2f)
        val hit = BoxGeometry.hitTest(
            Offset(0.05f, 0.05f), listOf(a), selected = null,
            handleRadiusX = 0.02f, handleRadiusY = 0.02f,
        )
        assertNull(hit)
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
            categoryListVersion = 2,
            classCounts = mapOf("license_plate" to 12, "police" to 3, "civil" to 40),
        )
        assertEquals(d, DoneManifest.fromJson(d.toJson()))
    }
}
