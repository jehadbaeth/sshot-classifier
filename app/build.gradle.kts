import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing credentials come from keystore.properties (gitignored) when
// present, otherwise from environment variables (CI). When neither is configured
// the release build falls back to debug signing: fine for a sideload APK, but a
// debug-signed AAB cannot be uploaded to Play, so we warn loudly below.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)
val releaseStoreFile: String? = signingValue("storeFile", "RELEASE_STORE_FILE")

android {
    namespace = "com.okapiorbits.sshotclassifier"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.okapiorbits.sshotclassifier"
        minSdk = 26
        targetSdk = 35
        versionCode = 29
        versionName = "0.9.17" // Motion + feedback: image crossfade, animated grid items, haptics

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (releaseStoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "RELEASE SIGNING: no keystore configured (keystore.properties / " +
                        "RELEASE_STORE_FILE). Falling back to DEBUG signing. The APK is " +
                        "sideloadable but the AAB is NOT uploadable to Play."
                )
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
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
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Background work
    implementation(libs.androidx.work.runtime.ktx)

    // Preferences
    implementation(libs.androidx.datastore.preferences)

    // Image loading
    implementation(libs.coil.compose)

    // Dependency injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // ML
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.image.labeling)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.support)

    // Arabic OCR (opt-in): ML Kit has no Arabic recognizer, so use Tesseract for that script.
    // Vendored AAR under app/libs (see settings.gradle.kts); its only transitive dep is
    // androidx.annotation, already present.
    implementation(":tesseract4android-4.9.0@aar")

    // On-device generative VLM captions (experimental, opt-in, high-end devices only).
    // MediaPipe LLM Inference API; the Gemma .task model is user-provided, never bundled.
    // tasks-core supplies the framework MPImage/BitmapImageBuilder that addImage() needs
    // (pure Java, no extra native libs; tasks-vision would add unused vision .so files).
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.mediapipe.tasks.core)

    // Camera (in-app capture for the real-world inventory feature)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}
