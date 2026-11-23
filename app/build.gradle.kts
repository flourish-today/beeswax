import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

// Test builds get their own app ID and name, and are signed locally with the separate test key.
val testBuild = providers.gradleProperty("testBuild").orNull == "true"

android {
    namespace = "org.getflourish.beeswax"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "org.getflourish.beeswax"
        minSdk = 33
        targetSdk = 37
        versionCode = 33
        versionName = "1.04"
        manifestPlaceholders["appName"] = if (testBuild) "Beeswax Test" else "Beeswax"
    }

    buildTypes {
        release {
            if (testBuild) applicationIdSuffix = ".test"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            vcsInfo {
                include = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}
