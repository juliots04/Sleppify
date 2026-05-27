plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.example.sleppify"
    val youtubeDataApiKey = (project.findProperty("YOUTUBE_DATA_API_KEY") as String?) ?: ""
    val sleppifyDownloadServiceUrl = (project.findProperty("SLEPPIFY_DOWNLOAD_SERVICE_URL") as String?) ?: ""

    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.sleppify"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "YOUTUBE_DATA_API_KEY", "\"$youtubeDataApiKey\"")
        buildConfigField("String", "SLEPPIFY_DOWNLOAD_SERVICE_URL", "\"$sleppifyDownloadServiceUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val cameraXVersion = "1.4.2"

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation(libs.glide)
    implementation("androidx.palette:palette:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("androidx.media:media:1.7.0")
    val media3Version = "1.6.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-datasource:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-database:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.3")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

kotlin {
    jvmToolchain(17)
}