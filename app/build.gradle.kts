import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val keystoreProperties = Properties().apply {
    val local = rootProject.file("keystore.properties")
    if (local.exists()) {
        local.inputStream().use(::load)
    }
}

fun signingValue(envName: String, propertyName: String, default: String? = null): String? =
    System.getenv(envName)?.takeIf { it.isNotEmpty() }
        ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotEmpty() }
        ?: default

val releaseStorePath = signingValue("KEYSTORE_PATH", "storeFile", "release.jks")
val releaseStoreFile = releaseStorePath?.let { path ->
    val store = File(path)
    if (store.isAbsolute) store else rootProject.file(path)
}
val releaseStorePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("KEY_ALIAS", "keyAlias", "recordingcompressor")
val releaseKeyPassword = signingValue("KEY_PASSWORD", "keyPassword")
val releaseSigningReady =
    releaseStoreFile != null &&
        releaseStoreFile.isFile &&
        !releaseStorePassword.isNullOrEmpty() &&
        !releaseKeyAlias.isNullOrEmpty() &&
        !releaseKeyPassword.isNullOrEmpty()

if (!releaseSigningReady) {
    logger.lifecycle(
        "Release signing skipped: keystore or passwords are missing. " +
            "assembleRelease will produce an unsigned APK.",
    )
}

android {
    namespace = "com.androidcompress.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.androidcompress.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"
        ndk {
            // ffmpeg-kit-full 8.1.7 and the sherpa-onnx AAR we ship are arm64-v8a in the APK.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseStoreFile!!
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += "**/libc++_shared.so"
        }
    }
    bundle {
        abi {
            enableSplit = true
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    kotlin {
        compilerOptions {
            // App Functions KSP emits `parameters["uriOrPaths"] as List<String>`
            // in generated CompressAppFunctionService; that generic cast cannot
            // be annotated from our source.
            freeCompilerArgs.add("-Xwarning-level=UNCHECKED_CAST:disabled")
        }
    }
}

ksp {
    arg("appfunctions:aggregateAppFunctions", "true")
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.window)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ffmpeg.kit)
    implementation(libs.smart.exception)
    implementation(libs.sherpa.onnx) {
        isTransitive = false
        artifact {
            name = "sherpa-onnx"
            type = "aar"
            extension = "aar"
        }
    }
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.muxer)
    implementation(libs.androidx.appfunctions)
    ksp(libs.androidx.appfunctions.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
