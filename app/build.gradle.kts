plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.aktv.app"
    compileSdk = 35
    defaultConfig { applicationId = "com.akshansh.aktv"; minSdk = 28; targetSdk = 33; versionCode = 14; versionName = "4.7.2" }
    flavorDimensions += "abi"
    productFlavors { create("arm32") { dimension = "abi" }; create("arm64") { dimension = "abi" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("androidx.palette:palette:1.0.0")
    "arm32Implementation"("org.mozilla.geckoview:geckoview-armeabi-v7a:123.0.20240213221259")
    "arm64Implementation"("org.mozilla.geckoview:geckoview-arm64-v8a:123.0.20240213221259")
}
