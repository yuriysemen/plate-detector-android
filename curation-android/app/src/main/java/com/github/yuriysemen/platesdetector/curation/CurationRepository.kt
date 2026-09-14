package com.github.yuriysemen.platesdetector.curation

import android.content.Context
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.GetObjectRequest
import com.amazonaws.services.s3.model.ListObjectsV2Request
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.services.s3.model.S3ObjectSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** `(current, total)` progress for a long-running S3 operation. */
typealias ProgressSink = (current: Int, total: Int) -> Unit

/**
 * An In-Progress package plus the S3 `LastModified` of its `manifest.json` — the "last activity"
 * timestamp used for stale detection (REQ-023).
 */
data class InProgressItem(val manifest: CurationManifest, val lastActivityMs: Long) {
    fun inactiveMs(nowMs: Long): Long = (nowMs - lastActivityMs).coerceAtLeast(0L)
    fun isStale(nowMs: Long): Boolean = inactiveMs(nowMs) > CurationRepository.STALE_IN_PROGRESS_MS
}

/**
 * Direct, IAM-scoped S3 access from the device (REQ-022/REQ-023). Credentials come from
 * [CuratorAuthManager] via the Identity Pool's token-based role mapping (`CuratorRole`). All
 * operations are plain S3 calls — there is no backend.
 */
class CurationRepository(
    context: Context,
    private val auth: CuratorAuthManager,
) {
    private val appContext = context.applicationContext
    private val bucket = CurationConfig.datasetBucket
    private val cache = PackageCache(context)
    private val tmpDir = File(appContext.cacheDir, "downloads").apply { mkdirs() }

    private suspend fun client(): AmazonS3Client = withContext(Dispatchers.IO) {
        AmazonS3Client(
            auth.newCredentialsProvider(),
            Region.getRegion(Regions.fromName(CurationConfig.region)),
        )
    }

    // ── REQ-022 access check ───────────────────────────────────────────────

    suspend fun checkAccess(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val req = ListObjectsV2Request()
                .withBucketName(bucket).withPrefix("uploads/").withMaxKeys(1)
            client().listObjectsV2(req).keyCount
        }
    }

    // ── REQ-025 vehicle categories ─────────────────────────────────────────

    /** The category list from `config/vehicle-categories.json`, falling back to the bundled
     *  asset on any failure (offline, not seeded yet, no `config/` read grant). */
    suspend fun fetchCategories(): VehicleCategories = withContext(Dispatchers.IO) {
        runCatching {
            VehicleCategories.fromJson(getText(client(), "config/vehicle-categories.json"))
        }.getOrElse { VehicleCategories.bundledDefault(appContext) }
    }

    // ── Listing the three screens ──────────────────────────────────────────

    suspend fun listNotProcessed(): List<UploadRef> = withContext(Dispatchers.IO) {
        val s3 = client()
        val taken = buildSet {
            addAll(packageIdsWithSuffix(s3, "curation/", "/manifest.json"))
            addAll(doneManifestPackageIds(s3))
        }
        listKeys(s3, "uploads/")
            .mapNotNull { parseUploadKey(it.key, it.size, it.lastModified?.time ?: 0L) }
            .filter { it.packageId !in taken }
            .sortedBy { it.lastModifiedMs }
    }

    /**
     * `packageId`s already completed under `done/`, in either naming scheme: the current nested
     * `done/<sub>/<device>/<stem>-curated-<ts>._manifest.json` (REQ-035), or the pre-REQ-035 flat
     * `done/<packageId>/_manifest.json` — both are supported so an upload completed before this
     * shipped doesn't reappear in Not Processed.
     */
    private fun doneManifestPackageIds(s3: AmazonS3Client): Set<String> =
        listKeys(s3, "done/")
            .filter { it.key.endsWith("_manifest.json") }
            .mapNotNull { summary ->
                packageIdFromDoneManifestKey(summary.key)
                    ?: summary.key.removePrefix("done/").removeSuffix("/_manifest.json")
                        .takeIf { it != summary.key }
            }
            .toSet()

    suspend fun listInProgress(): List<InProgressItem> = withContext(Dispatchers.IO) {
        val s3 = client()
        listKeys(s3, "curation/")
            .filter { it.key.endsWith("/manifest.json") }
            .mapNotNull { summary ->
                runCatching { CurationManifest.fromJson(getText(s3, summary.key)) }.getOrNull()
                    ?.let { InProgressItem(it, summary.lastModified?.time ?: 0L) }
            }
            .sortedBy { it.manifest.startedAt }
    }

    suspend fun listDone(): List<DoneManifest> = withContext(Dispatchers.IO) {
        val s3 = client()
        // Matches both the current `<stem>-curated-<ts>._manifest.json` (REQ-035) and the
        // pre-REQ-035 flat `<packageId>/_manifest.json` sidecar shape — DoneManifest.fromJson
        // already tolerates either schema (the REQ-035 fields are optional).
        listKeys(s3, "done/")
            .filter { it.key.endsWith("_manifest.json") }
            .mapNotNull { runCatching { DoneManifest.fromJson(getText(s3, it.key)) }.getOrNull() }
            .sortedByDescending { it.completedAt }
    }

    // ── Start / resume ─────────────────────────────────────────────────────

    suspend fun startPackage(
        upload: UploadRef,
        curatorEmail: String,
        categories: VehicleCategories,
        onProgress: ProgressSink,
    ): CurationManifest = withContext(Dispatchers.IO) {
        val s3 = client()
        onProgress(0, 3)
        val zip = File(tmpDir, "${upload.packageId}.zip")
        s3.getObject(GetObjectRequest(bucket, upload.key), zip)
        onProgress(1, 3)
        cache.unzip(upload.packageId, zip)
        onProgress(2, 3)
        val items = cache.listItems(upload.packageId).map {
            ManifestItem(it.subset, it.basename, it.imageName, ItemStatus.PENDING)
        }
        val manifest = CurationManifest(
            packageId = upload.packageId,
            sourceKey = upload.key,
            filename = upload.filename,
            userSub = upload.userSub,
            deviceId = upload.deviceId,
            startedAt = nowIso(),
            curatorEmail = curatorEmail,
            items = items,
            categoryListVersion = categories.version,
            categories = categories,
        )
        putManifest(s3, manifest)
        onProgress(3, 3)
        manifest
    }

    /** Re-download + re-unzip the source ZIP if the local cache is gone (reinstall / new device). */
    suspend fun ensureLocalCopy(
        manifest: CurationManifest,
        onProgress: ProgressSink,
    ): List<CachedItem> = withContext(Dispatchers.IO) {
        if (!cache.isPresent(manifest.packageId)) {
            onProgress(0, 2)
            val zip = File(tmpDir, "${manifest.packageId}.zip")
            client().getObject(GetObjectRequest(bucket, manifest.sourceKey), zip)
            onProgress(1, 2)
            cache.unzip(manifest.packageId, zip)
            onProgress(2, 2)
        }
        cache.listItems(manifest.packageId)
    }

    suspend fun putManifest(manifest: CurationManifest) = withContext(Dispatchers.IO) {
        putManifest(client(), manifest)
    }

    private fun putManifest(s3: AmazonS3Client, manifest: CurationManifest) {
        putText(s3, "curation/${manifest.packageId}/manifest.json", manifest.toJson())
    }

    // ── Complete / release ─────────────────────────────────────────────────

    /**
     * Uploads a package's outcome as compressed archives (REQ-035) — one `PUT` for every accepted
     * item's image+label plus `data.yaml`, one for every rejected item's image+label, and one
     * small `_manifest.json` sidecar — instead of the pre-REQ-035 individual `PUT` per file (200+
     * requests for a 100-image package). The manifest sidecar stays un-zipped so [listDone] can
     * keep listing completed packages without downloading and unzipping every archive.
     */
    suspend fun completePackage(
        manifest: CurationManifest,
        categories: VehicleCategories,
        onProgress: ProgressSink,
    ): DoneManifest = withContext(Dispatchers.IO) {
        // The labels already accepted into this package's manifest were classified against
        // `manifest.categoryListVersion`. Regenerating data.yaml/class_counts from a *different*
        // version here would silently produce a package whose labels fall outside its own
        // nc/names — reject rather than substitute. In normal operation `categories` is always
        // the manifest's own embedded snapshot (see CurationViewModel.complete), so this only
        // trips for a pre-fix manifest with no snapshot whose best-effort fallback disagrees.
        check(categories.version == manifest.categoryListVersion) {
            "Category list mismatch: package \"${manifest.filename}\" was reviewed against " +
                "version ${manifest.categoryListVersion}, but version ${categories.version} is " +
                "what's available now. Completing would produce labels outside data.yaml's " +
                "nc/names range — refusing. Reconnect so the package's own list can be used."
        }
        val s3 = client()
        val id = manifest.packageId
        val timestamp = completionTimestamp()

        manifest.items.forEach { item ->
            if (item.status == ItemStatus.ACCEPTED && item.labelContent != null) {
                cache.writeLabel(id, item.subset, item.basename, item.labelContent)
            }
        }

        val acceptedItems = manifest.items.filter { it.status == ItemStatus.ACCEPTED }
        val rejectedItems = manifest.items.filter { it.status == ItemStatus.REJECTED }
        val steps = (if (acceptedItems.isNotEmpty()) 1 else 0) +
            (if (rejectedItems.isNotEmpty()) 1 else 0) + 1 // + manifest sidecar
        var step = 0

        var doneZipKey: String? = null
        if (acceptedItems.isNotEmpty()) {
            val zipFile = File(tmpDir, "$id-done.zip")
            buildZip(
                zipFile,
                itemFileEntries(id, acceptedItems),
                listOf("data.yaml" to rewriteDataYamlHeader(cache.readDataYaml(id), categories)),
            )
            doneZipKey = "${doneBaseKey(manifest.userSub, manifest.deviceId, manifest.filename, timestamp)}.zip"
            putFile(s3, doneZipKey, zipFile)
            zipFile.delete()
            onProgress(++step, steps)
        }

        var rejectedZipKey: String? = null
        if (rejectedItems.isNotEmpty()) {
            val zipFile = File(tmpDir, "$id-rejected.zip")
            buildZip(zipFile, itemFileEntries(id, rejectedItems))
            rejectedZipKey = "${rejectedBaseKey(manifest.userSub, manifest.deviceId, manifest.filename, timestamp)}.zip"
            putFile(s3, rejectedZipKey, zipFile)
            zipFile.delete()
            onProgress(++step, steps)
        }

        val done = DoneManifest(
            packageId = id,
            filename = manifest.filename,
            curatorEmail = manifest.curatorEmail,
            completedBy = auth.currentUserEmail(),
            startedAt = manifest.startedAt,
            completedAt = nowIso(),
            total = manifest.total,
            accepted = manifest.accepted,
            rejected = manifest.rejected,
            perSubsetAccepted = manifest.perSubsetAccepted(),
            reviewers = manifest.reviewers(),
            categoryListVersion = manifest.categoryListVersion,
            classCounts = manifest.classCounts(categories),
            doneZipKey = doneZipKey,
            rejectedZipKey = rejectedZipKey,
        )
        val manifestKey = "${doneBaseKey(manifest.userSub, manifest.deviceId, manifest.filename, timestamp)}._manifest.json"
        putText(s3, manifestKey, done.toJson())
        onProgress(++step, steps)

        s3.deleteObject(bucket, "curation/$id/manifest.json")
        cache.delete(id)
        done
    }

    /** `<subset>/images/<image>` + `<subset>/labels/<basename>.txt` zip-entry-name → local-file
     *  pairs for the given items, skipping any file that isn't actually present on disk. */
    private fun itemFileEntries(packageId: String, items: List<ManifestItem>): List<Pair<String, File>> =
        items.flatMap { item ->
            val img = File(cache.dir(packageId), "${item.subset}/images/${item.imageName}")
            val lbl = File(cache.dir(packageId), "${item.subset}/labels/${item.basename}.txt")
            buildList {
                if (img.exists()) add("${item.subset}/images/${item.imageName}" to img)
                if (lbl.exists()) add("${item.subset}/labels/${item.basename}.txt" to lbl)
            }
        }

    /** Zips [fileEntries] (zip-entry-name → source file) plus [textEntries] (zip-entry-name →
     *  literal content) into [dest]. */
    private fun buildZip(
        dest: File,
        fileEntries: List<Pair<String, File>>,
        textEntries: List<Pair<String, String>> = emptyList(),
    ) {
        ZipOutputStream(FileOutputStream(dest).buffered()).use { zos ->
            fileEntries.forEach { (entryName, file) ->
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            textEntries.forEach { (entryName, text) ->
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
    }

    suspend fun releasePackage(packageId: String) = withContext(Dispatchers.IO) {
        client().deleteObject(bucket, "curation/$packageId/manifest.json")
        cache.delete(packageId)
    }

    // ── S3 helpers ─────────────────────────────────────────────────────────

    private fun listKeys(s3: AmazonS3Client, prefix: String): List<S3ObjectSummary> {
        val out = mutableListOf<S3ObjectSummary>()
        var token: String? = null
        do {
            val req = ListObjectsV2Request().withBucketName(bucket).withPrefix(prefix)
            if (token != null) req.withContinuationToken(token)
            val res = s3.listObjectsV2(req)
            out += res.objectSummaries
            token = if (res.isTruncated) res.nextContinuationToken else null
        } while (token != null)
        return out
    }

    /** Collects `<package_id>` from keys shaped `<prefix><package_id><suffix>`. */
    private fun packageIdsWithSuffix(s3: AmazonS3Client, prefix: String, suffix: String): Set<String> =
        listKeys(s3, prefix)
            .filter { it.key.endsWith(suffix) }
            .map { it.key.removePrefix(prefix).removeSuffix(suffix) }
            .toSet()

    private fun getText(s3: AmazonS3Client, key: String): String =
        s3.getObject(bucket, key).objectContent.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun putText(s3: AmazonS3Client, key: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val meta = ObjectMetadata().apply {
            contentLength = bytes.size.toLong()
            contentType = "application/json"
        }
        s3.putObject(PutObjectRequest(bucket, key, ByteArrayInputStream(bytes), meta))
    }

    private fun putFile(s3: AmazonS3Client, key: String, file: File) {
        s3.putObject(PutObjectRequest(bucket, key, file))
    }

    /**
     * Regenerates the YOLO header (`train`/`val`/`test`/`nc`/`names`) from the vehicle-category
     * list (REQ-025), preserving the `device:` provenance block from the source `data.yaml`.
     */
    private fun rewriteDataYamlHeader(source: String?, categories: VehicleCategories): String {
        val names = categories.orderedNames()
        val header = buildString {
            appendLine("train: train/images")
            appendLine("val: val/images")
            appendLine("test: test/images")
            appendLine("nc: ${names.size}")
            appendLine("names: [${names.joinToString(", ") { "'$it'" }}]")
        }
        val tail = source?.lineSequence()
            ?.dropWhile { !it.trimStart().startsWith("device:") }
            ?.joinToString("\n")
            ?.takeIf { it.isNotBlank() }
        return if (tail != null) "$header\n$tail\n" else header
    }

    companion object {
        /** An In-Progress package with no manifest write in this long is treated as abandoned. */
        const val STALE_IN_PROGRESS_MS = 2 * 60 * 60 * 1000L
    }
}
