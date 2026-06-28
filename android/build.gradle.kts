buildscript {
    dependencies {
        classpath("org.json:json:20231013")
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
extra["kotlinJvmTarget"] = "17"
