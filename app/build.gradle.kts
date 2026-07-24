plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.whispera.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.whispera.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Native API: only arm64-v8a supported (ONNX Runtime + models optimized for arm64)
        ndk { abiFilters += "arm64-v8a" }
    }

    // Product flavors so the same source can be packaged in two sizes.
    flavorDimensions += "engine"
    productFlavors {
        create("offlineFull") {
            dimension = "engine"
            // App ships with VAD/ASR/TTS ONNX models in assets/models/.
            // LLM is configured at runtime (local llama-server OR remote OpenAI-compatible API).
            buildConfigField("boolean", "BUNDLE_MODELS", "true")
        }
        create("liteCloud") {
            dimension = "engine"
            // Models are downloaded on first launch; leaner APK.
            buildConfigField("boolean", "BUNDLE_MODELS", "false")
        }
    }

    // ---- Signing config ----
    // 1. If user has a local keystore.properties (from scripts/gen-keystore.sh) -> use that.
    // 2. Otherwise use the project's built-in test keystore under app/keystore/.
    //    The built-in key is committed to the repo, so cloning + building a signed
    //    release APK requires zero setup. Everyone shares this key — see README.
    val localKeystoreProps = rootProject.file("keystore.properties")
    if (localKeystoreProps.exists()) {
        val props = java.util.Properties().apply { localKeystoreProps.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
            }
        }
    } else {
        // Built-in test keystore (committed, public, shared).
        // Alias/keystore paths are fixed here so we don't need a properties file.
        signingConfigs {
            create("release") {
                keyAlias = "whispera-builtin"
                keyPassword = "whispera"
                storeFile = file("keystore/builtin-release.p12")
                storePassword = "whispera"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed always — with user's own key if keystore.properties exists,
            // otherwise with the builtin public test key committed in this repo.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // sherpa-onnx AAR contains native libs we must keep.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    // Android UI
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui:1.6.0")
    implementation("androidx.compose.ui:ui-graphics:1.6.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.0")
    implementation("androidx.compose.material3:material3:1.1.2")
    implementation("androidx.compose.material:material-icons-extended:1.6.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp — for streaming LLM (OpenAI-compatible SSE) & llama-server control
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Sherpa-ONNX — local VAD / ASR / TTS inference (ONNX Runtime CPU, arm64-v8a).
    // Pulled as a local AAR via libs/ — see scripts/setup_models.sh.
    implementation(fileTree("libs") { include("*.aar") })

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
