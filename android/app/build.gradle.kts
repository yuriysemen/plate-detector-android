import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val modelReleaseBaseUrl = "https://github.com/yuriysemen/plate-detector-android/releases/latest/download"

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

val modelDownloadToken = providers.gradleProperty("MODEL_DOWNLOAD_TOKEN").orNull
    ?: System.getenv("MODEL_DOWNLOAD_TOKEN")
    ?: System.getenv("GITHUB_TOKEN")

val defaultModelsDir = layout.buildDirectory.dir("generated/assets/defaultModels")

val downloadDefaultModels by tasks.registering {
    val outputDir = defaultModelsDir.get().asFile
    outputs.dir(outputDir)
    doLast {
        val modelsDir = File(outputDir, "models").apply { mkdirs() }
        val baseUrl = modelReleaseBaseUrl.trimEnd('/')

        modelFiles.forEach { fileName ->
            val destFile = File(modelsDir, fileName)
            if (destFile.exists() && destFile.length() > 0L) return@forEach

            val tmpFile = File(modelsDir, "${fileName}.download")
            val url = "$baseUrl/$fileName"
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "PlateDetector-Android-Gradle")
                if (!modelDownloadToken.isNullOrBlank()) {
                    connection.setRequestProperty("Authorization", "token $modelDownloadToken")
                }
                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) {
                    if (modelDownloadToken.isNullOrBlank()) {
                        error("Failed to download $url (HTTP $code). Set MODEL_DOWNLOAD_TOKEN for private releases.")
                    }
                    error("Failed to download $url (HTTP $code)")
                }
                connection.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (!tmpFile.renameTo(destFile)) {
                    error("Failed to move ${tmpFile.name} to ${destFile.name}")
                }
            } catch (t: Throwable) {
                runCatching { tmpFile.delete() }
                throw t
            } finally {
                connection.disconnect()
            }
        }
    }
}

android {
    namespace = "com.github.yuriysemen.platesdetector"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.github.yuriysemen.platesdetector"
        minSdk = 23
        targetSdk = 36
        versionCode = 11
        versionName = "0.0.11"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val keyAlias = System.getenv("ANDROID_KEY_ALIAS")
    val keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
    val releaseSigningConfig =
        if (
            listOf(
                keystorePath,
                keystorePassword,
                keyAlias,
                keyPassword
            ).all { !it.isNullOrBlank() }
        ) {
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
    }
    androidResources {
        noCompress += "tflite"
    }
    sourceSets["main"].assets.srcDir(defaultModelsDir)
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

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view) // PreviewView

    implementation(libs.tensorflow.lite)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.testJunit4)

    debugImplementation(libs.androidx.compose.ui.testManifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
