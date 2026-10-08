import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.ksp)
    alias(libs.plugins.google.hilt)
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

android {
    namespace = "de.lwp2070809.speculonic"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.lwp2070809.speculonic"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.9.11"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isDefault = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true

            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                FileInputStream(localPropertiesFile).use { stream -> localProperties.load(stream) }
            }
            val envGithubRepo = System.getenv("GITHUB_REPO")
            val propGithubRepo = project.findProperty("githubRepo")?.toString()
            val localPropGithubRepo = localProperties.getProperty("githubRepo")

            val githubRepo = envGithubRepo ?: propGithubRepo ?: localPropGithubRepo ?: "lwp2070809/speculonic-android"
            val updateCheckEnabled = githubRepo.isNotEmpty()

            buildConfigField("boolean", "UPDATE_CHECK_ENABLED", updateCheckEnabled.toString())
            buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "UPDATE_CHECK_ENABLED", "false")
            buildConfigField("String", "GITHUB_REPO", "\"\"")
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

    sourceSets {
        getByName("debug") {
            java.srcDir("build/generated/ksp/debug/java")
        }
        getByName("release") {
            java.srcDir("build/generated/ksp/release/java")
        }
        val hasLocalExtension = file("${rootDir}/local.gradle.kts").exists()
        getByName("github") {
            if (hasLocalExtension) {
                res.srcDirs("build/generated/res/easter-eggs")
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xannotation-default-target=param-property")
}


ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.material.icons.core)
    
    
    implementation(libs.io.coil.kt.coil.compose)
    implementation(libs.io.coil.kt.coil.network)
    implementation(libs.io.coil.kt.coil.svg)
    implementation(libs.io.coil.kt.coil.gif)
    
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.google.material)
    
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.appcompat)
    implementation(libs.work.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.security.crypto)
    ksp(libs.androidx.room.compiler)
    
    implementation(libs.jaudiotagger)
    
    implementation(libs.google.hilt.android)
    ksp(libs.google.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.room.paging)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

detekt {
    toolVersion = "1.23.7"
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
    ignoreFailures = true
}


val localGradle = file("${rootDir}/local.gradle.kts")
val isFdroidTask = gradle.startParameter.taskNames.any { it.contains("fdroid", ignoreCase = true) }
if (localGradle.exists() && !isFdroidTask) {
    apply(from = localGradle)
}
