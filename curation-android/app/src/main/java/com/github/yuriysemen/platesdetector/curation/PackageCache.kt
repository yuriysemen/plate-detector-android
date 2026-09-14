package com.github.yuriysemen.platesdetector.curation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/** One reviewable item from the unzipped package. */
data class CachedItem(
    val subset: String,
    val basename: String,
    val imageName: String,
    val imageFile: File,
    val labelFile: File,
)

/**
 * Unzips [zip] into [targetDir] (created if needed), guarding against zip-slip. Does not delete
 * [zip] or clear [targetDir] first — callers that want either do it themselves. Shared by
 * [PackageCache.unzip] (the working review copy) and [CurationRepository]'s Done-package viewer
 * (REQ-036), which unzips into its own temp directory instead.
 */
fun unzipInto(zip: File, targetDir: File) {
    targetDir.mkdirs()
    val targetCanonical = targetDir.canonicalPath + File.separator
    ZipInputStream(zip.inputStream().buffered()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            val out = File(targetDir, entry.name)
            if (!out.canonicalPath.startsWith(targetCanonical)) {
                throw SecurityException("Zip entry escapes target dir: ${entry.name}")
            }
            if (entry.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile?.mkdirs()
                out.outputStream().use { zis.copyTo(it) }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
}

/**
 * On-device working copy of a package: the source ZIP is downloaded and unzipped into
 * `filesDir/packages/<package_id>/` and review reads/writes entirely from there. Only
 * `manifest.json` is synced to S3 (REQ-022 working-copy strategy) — so this cache is rebuildable
 * from the immutable source ZIP but never resumable from S3 alone.
 */
class PackageCache(context: Context) {

    private val root = File(context.applicationContext.filesDir, "packages")

    fun dir(packageId: String): File = File(root, packageId)

    private fun marker(packageId: String) = File(dir(packageId), ".extracted")

    fun isPresent(packageId: String): Boolean = marker(packageId).exists()

    /** Unzips [zip] into the package dir (replacing any partial extraction), then deletes [zip]. */
    suspend fun unzip(packageId: String, zip: File) = withContext(Dispatchers.IO) {
        val target = dir(packageId)
        target.deleteRecursively()
        unzipInto(zip, target)
        marker(packageId).writeText(System.currentTimeMillis().toString())
        zip.delete()
    }

    fun listItems(packageId: String): List<CachedItem> {
        val base = dir(packageId)
        val subsets = listOf("train", "val", "test")
        return subsets.flatMap { subset ->
            val imagesDir = File(base, "$subset/images")
            val labelsDir = File(base, "$subset/labels")
            (imagesDir.listFiles()?.toList().orEmpty())
                .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTS }
                .sortedBy { it.name }
                .map { img ->
                    val stem = img.nameWithoutExtension
                    CachedItem(
                        subset = subset,
                        basename = stem,
                        imageName = img.name,
                        imageFile = img,
                        labelFile = File(labelsDir, "$stem.txt"),
                    )
                }
        }
    }

    fun readDataYaml(packageId: String): String? =
        File(dir(packageId), "data.yaml").takeIf { it.exists() }?.readText()

    fun writeLabel(packageId: String, subset: String, basename: String, content: String) {
        val f = File(dir(packageId), "$subset/labels/$basename.txt")
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    fun delete(packageId: String) {
        dir(packageId).deleteRecursively()
    }

    companion object {
        val IMAGE_EXTS = setOf("jpg", "jpeg", "png")
    }
}
