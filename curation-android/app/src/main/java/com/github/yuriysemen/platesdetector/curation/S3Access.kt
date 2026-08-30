package com.github.yuriysemen.platesdetector.curation

import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ListObjectsV2Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Direct, IAM-scoped S3 access from the device (REQ-022). Credentials come from
 * [CuratorAuthManager] via the Identity Pool's token-based role mapping — a signed-in `curators`
 * member gets `CuratorRole`. This is the seed for REQ-023's package-listing repository; for now
 * it only proves the role works.
 */
class S3Access(private val auth: CuratorAuthManager) {

    private suspend fun client(): AmazonS3Client = withContext(Dispatchers.IO) {
        val provider = auth.newCredentialsProvider()
        AmazonS3Client(
            provider,
            Region.getRegion(Regions.fromName(CurationConfig.region))
        )
    }

    /**
     * Lists a single key under `uploads/` to confirm `CuratorRole` can reach the dataset bucket.
     * Returns the number of keys the bucket reports (0 is a valid success — an empty prefix).
     */
    suspend fun checkAccess(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val req = ListObjectsV2Request()
                .withBucketName(CurationConfig.datasetBucket)
                .withPrefix("uploads/")
                .withMaxKeys(1)
            client().listObjectsV2(req).keyCount
        }
    }
}
