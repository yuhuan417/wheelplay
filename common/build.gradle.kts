plugins {
    id("com.android.library")
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { targets += "wheelplay-video" } }
    }

    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    implementation("com.google.android.material:material:1.13.0")
    androidTestImplementation(libs.bouncycastle)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    api(project(":shared"))
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
