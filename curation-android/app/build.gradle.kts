import org.jetbrains.kotlin.gradle.dsl.JvmTarget
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

val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")
val keystorePassword: String? = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val keyAlias: String? = System.getenv("ANDROID_KEY_ALIAS")
val keyPassword: String? = System.getenv("ANDROID_KEY_PASSWORD")
val hasSigningEnv = listOf(keystorePath, keystorePassword, keyAlias, keyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.github.yuriysemen.platesdetector.curation"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.github.yuriysemen.platesdetector.curation"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "COGNITO_USER_POOL_ID",    "\"${localProp("COGNITO_USER_POOL_ID")}\"")
        buildConfigField("String", "COGNITO_APP_CLIENT_ID",   "\"${localProp("COGNITO_APP_CLIENT_ID")}\"")
        buildConfigField("String", "COGNITO_IDENTITY_POOL_ID","\"${localProp("COGNITO_IDENTITY_POOL_ID")}\"")
        buildConfigField("String", "DATASET_BUCKET_NAME",     "\"${localProp("DATASET_BUCKET_NAME")}\"")
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

    implementation(libs.aws.cognitoidentityprovider)
    implementation(libs.aws.s3)

    testImplementation(libs.junit)
    // Real org.json — the android.jar bundled for unit tests only stubs it.
    testImplementation(libs.org.json)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.testJunit4)

    debugImplementation(libs.androidx.compose.ui.testManifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
