import java.io.FileInputStream
import java.util.Properties
import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import com.android.build.api.variant.HasUnitTestBuilder

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.ksp)
    alias(libs.plugins.google.hilt)
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

android {
    namespace = "de.lwp2070809.speculonic"
    compileSdk = 37

    val supportedAbis = listOf("arm64-v8a", "x86_64")
    val injectedAbi = project.findProperty("android.injected.build.abi")?.toString()

    defaultConfig {
        applicationId = "de.lwp2070809.speculonic"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.10.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        if (!injectedAbi.isNullOrBlank()) {
            ndk {
                val targetAbis = injectedAbi.split(",")
                    .map { it.trim() }
                    .filter { it in supportedAbis }
                if (targetAbis.isNotEmpty()) {
                    abiFilters.addAll(targetAbis)
                } else {
                    abiFilters.addAll(supportedAbis)
                }
            }
        }
    }

    if (injectedAbi.isNullOrBlank()) {
        splits {
            abi {
                isEnable = true
                reset()
                include(*supportedAbis.toTypedArray())
                isUniversalApk = false
            }
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
            assets.srcDirs("$projectDir/schemas")
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
        getByName("test") {
            assets.srcDirs("$projectDir/schemas")
            resources.srcDirs("$projectDir/schemas")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        screenshotTests.create("screenshotTest") {
            engineVersion = "0.0.1-alpha16"
            targetVariants.add("githubDebug")
            dependencies {
                implementation(libs.androidx.compose.ui.tooling)
                implementation("com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha16")
            }
        }
    }
}

val abiCodes = mapOf("x86_64" to 1, "arm64-v8a" to 2)

androidComponents {
    beforeVariants { variantBuilder ->
        (variantBuilder as? HasUnitTestBuilder)?.enableUnitTest = true
    }
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val name = output.filters.find { it.filterType == ABI }?.identifier
            val baseAbiCode = abiCodes[name]
            if (baseAbiCode != null) {
                output.versionCode.set(baseAbiCode * 1000 + (output.versionCode.get() ?: 0))
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xannotation-default-target=param-property")
}

tasks.register<Sync>("syncRoomSchemasToTestAssets") {
    from("$projectDir/schemas")
    into("$projectDir/src/test/assets")
}
tasks.named("preBuild") {
    dependsOn("syncRoomSchemasToTestAssets")
}
tasks.withType<Test>().configureEach {
    dependsOn("syncRoomSchemasToTestAssets")
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
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
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
    implementation(libs.androidx.documentfile)
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
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

detekt {
    toolVersion = "1.23.8"
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

tasks.register("validateScreenshotTest") {
    group = "verification"
    description = "执行 Compose Preview 截图比对验证测试"
    dependsOn("testGithubDebugScreenshotTestDefaultTestSuite")
}

tasks.register("updateScreenshotTest") {
    group = "verification"
    description = "生成并更新 Compose Preview 截图测试黄金基准图片 (Golden Images)"
    dependsOn("updateGithubDebugScreenshotTestDefaultTestSuite")
}

tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests = false
}


