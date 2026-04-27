import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val keystoreProperties = Properties()
// 先读仓库内默认签名，再由 local.properties 覆盖（便于本机覆盖）
rootProject.file("key/release_signing.properties").takeIf { it.exists() }?.inputStream()?.use {
    keystoreProperties.load(it)
}
rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use {
    keystoreProperties.load(it)
}

val releaseStorePath = keystoreProperties.getProperty("release.storeFile")?.takeIf { it.isNotBlank() }
val releaseStoreFile = releaseStorePath?.let(rootProject::file)
val hasSharedSigning =
    releaseStoreFile?.isFile == true &&
    !keystoreProperties.getProperty("release.storePassword").isNullOrBlank() &&
    !keystoreProperties.getProperty("release.keyAlias").isNullOrBlank() &&
    !keystoreProperties.getProperty("release.keyPassword").isNullOrBlank()

if (releaseStorePath != null && releaseStoreFile?.isFile != true) {
    logger.warn("Shared signing is disabled because the keystore file was not found: $releaseStorePath")
}

android {
    namespace = "com.vaca.callmate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vaca.callmate"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "1.0.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (hasSharedSigning) {
            create("shared") {
                storeFile = releaseStoreFile
                storePassword = keystoreProperties.getProperty("release.storePassword")
                keyAlias = keystoreProperties.getProperty("release.keyAlias")
                keyPassword = keystoreProperties.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hasSharedSigning) {
                signingConfig = signingConfigs.getByName("shared")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSharedSigning) {
                signingConfig = signingConfigs.getByName("shared")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// URL injection — read from local.properties or environment variables
// (loaded after the android {} block so we can access rootProject safely)
android.defaultConfig.apply {
    val localProps = java.util.Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { localProps.load(it) }
    fun envOrProp(envKey: String, propKey: String, default: String) =
        System.getenv(envKey) ?: localProps.getProperty(propKey) ?: default

    buildConfigField("String", "API_BASE_URL",
        "\"${envOrProp("ECHOCARD_API_BASE_URL", "echocard.api.base.url", "https://echocard.xiaozhi.me")}\"")
    buildConfigField("String", "VOICE_API_BASE_URL",
        "\"${envOrProp("ECHOCARD_VOICE_API_BASE_URL", "echocard.voice.api.base.url", "http://120.79.156.134:8002")}\"")
    buildConfigField("String", "WS_BASE_URL",
        "\"${envOrProp("ECHOCARD_WS_BASE_URL", "echocard.ws.base.url", "ws://120.79.156.134:8081")}\"")
    buildConfigField("String", "FW_SERVER_BASE_URL",
        "\"${envOrProp("ECHOCARD_FW_SERVER_BASE_URL", "echocard.fw.server.base.url", "http://120.24.162.199/echocard")}\"")
}

dependencies {
    implementation(files("libs/callmate-ble-0.1.0.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.okhttp)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
