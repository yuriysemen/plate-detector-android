import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val localProperties = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}
fun localProp(key: String) = localProperties.getProperty(key, "")

val defaultModelFiles = listOf(
    "plate_numbers.tflite",
    "plate_numbers.txt"
)

val modelFilesFromProperty = providers.gradleProperty("MODEL_FILES")
    .orNull
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() }

val modelFiles = modelFilesFromProperty ?: defaultModelFiles

val modelDownloadToken =
    localProp("MODEL_DOWNLOAD_TOKEN").takeIf { it.isNotBlank() }
        ?: providers.gradleProperty("MODEL_DOWNLOAD_TOKEN").orNull
        ?: System.getenv("MODEL_DOWNLOAD_TOKEN")
        ?: System.getenv("GITHUB_TOKEN")

// ── GitHub release helpers ─────────────────────────────────────────────────

data class GithubAsset(val name: String, val url: String)
data class GithubRelease(val tagName: String, val assets: List<GithubAsset>)

fun findLatestModelRelease(token: String?): GithubRelease? {
    val apiUrl = "https://api.github.com/repos/yuriysemen/plate-detector-android/releases"
    val conn = URI(apiUrl).toURL().openConnection() as HttpURLConnection
    conn.setRequestProperty("Accept", "application/vnd.github+json")
    conn.setRequestProperty("User-Agent", "PlateDetector-Gradle")
    if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    return try {
        if (conn.responseCode !in 200..299) return null
        val json = JSONArray(conn.inputStream.bufferedReader().readText())
        val tagRegex = Regex("""^model_v(\d+)\.(\d+)\.(\d+)$""")
        (0 until json.length())
            .mapNotNull { i ->
                val obj = json.getJSONObject(i)
                val tag = obj.getString("tag_name")
                val m = tagRegex.matchEntire(tag) ?: return@mapNotNull null
                val (x, y, z) = m.destructured
                val assetsArr = obj.getJSONArray("assets")
                val assets = (0 until assetsArr.length()).map { j ->
                    val a = assetsArr.getJSONObject(j)
                    GithubAsset(a.getString("name"), a.getString("url"))
                }
                Triple(Triple(x.toInt(), y.toInt(), z.toInt()), tag, assets)
            }
            .maxByOrNull { (ver, _, _) -> ver.first * 1_000_000 + ver.second * 1_000 + ver.third }
            ?.let { (_, tag, assets) -> GithubRelease(tag, assets) }
    } catch (e: Exception) {
        null
    } finally {
        conn.disconnect()
    }
}

fun downloadAsset(assetApiUrl: String, dest: File, token: String?) {
    val tmp = File(dest.parent, "${dest.name}.download")
    val conn = URI(assetApiUrl).toURL().openConnection() as HttpURLConnection
    conn.setRequestProperty("Accept", "application/octet-stream")
    conn.setRequestProperty("User-Agent", "PlateDetector-Gradle")
    if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
    conn.instanceFollowRedirects = true
    conn.connectTimeout = 15_000
    conn.readTimeout = 60_000
    try {
        if (conn.responseCode !in 200..299) {
            logger.warn("[WARN] Failed to download asset from $assetApiUrl (HTTP ${conn.responseCode}). Skipping.")
            return
        }
        conn.inputStream.use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o) } }
        if (!tmp.renameTo(dest)) logger.warn("[WARN] Could not rename ${tmp.name} → ${dest.name}.")
    } catch (e: Exception) {
        logger.warn("[WARN] Download error for ${dest.name}: ${e.message}. Skipping.")
    } finally {
        runCatching { tmp.delete() }
        conn.disconnect()
    }
}

val defaultModelsDir = layout.buildDirectory.dir("generated/assets/defaultModels")

val downloadDefaultModels = tasks.register("downloadDefaultModels") {
    val outputDir = defaultModelsDir.get().asFile
    outputs.dir(outputDir)
    doLast {
        val modelsDir = File(outputDir, "models").apply { mkdirs() }

        val release = findLatestModelRelease(modelDownloadToken) ?: run {
            logger.warn("[WARN] No model_v* release found on GitHub. Building without bundled model.")
            return@doLast
        }
        logger.lifecycle("Using model release: ${release.tagName}")

        modelFiles.forEach { fileName ->
            val destFile = File(modelsDir, fileName)
            if (destFile.exists() && destFile.length() > 0L) {
                logger.lifecycle("Skipping $fileName (already present).")
                return@forEach
            }
            val asset = release.assets.firstOrNull { it.name == fileName } ?: run {
                logger.warn("[WARN] Asset '$fileName' not found in release '${release.tagName}'. Skipping.")
                return@forEach
            }
            logger.lifecycle("Downloading $fileName from ${release.tagName}...")
            downloadAsset(asset.url, destFile, modelDownloadToken)
        }
    }
}

val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")
val keystorePassword: String? = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val keyAlias: String? = System.getenv("ANDROID_KEY_ALIAS")
val keyPassword: String? = System.getenv("ANDROID_KEY_PASSWORD")
val hasSigningEnv = listOf(keystorePath, keystorePassword, keyAlias, keyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.github.yuriysemen.platesdetector.training"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.github.yuriysemen.platesdetector.training"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "COGNITO_USER_POOL_ID",   "\"${localProp("COGNITO_USER_POOL_ID")}\"")
        buildConfigField("String", "COGNITO_APP_CLIENT_ID",  "\"${localProp("COGNITO_APP_CLIENT_ID")}\"")
        buildConfigField("String", "COGNITO_IDENTITY_POOL_ID","\"${localProp("COGNITO_IDENTITY_POOL_ID")}\"")
        buildConfigField("String", "UPLOAD_SERVICE_URL",     "\"${localProp("UPLOAD_SERVICE_URL")}\"")
    }
    val releaseSigningConfig = if (hasSigningEnv) {
        signingConfigs.create("release") {
            storeFile = file(keystorePath!!)
            storePassword = keystorePassword
            this.keyAlias = keyAlias
            this.keyPassword = keyPassword
        }
    } else {
        null
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = releaseSigningConfig
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress.add("tflite")
    }
    sourceSets["main"].assets.directories.add(defaultModelsDir.get().asFile.absolutePath)
}

tasks.named("preBuild") {
    dependsOn(downloadDefaultModels)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view) // PreviewView

    implementation(libs.tensorflow.lite)
    implementation(libs.mlkit.text.recognition)

    implementation(libs.aws.cognitoidentityprovider)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.testJunit4)

    debugImplementation(libs.androidx.compose.ui.testManifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
