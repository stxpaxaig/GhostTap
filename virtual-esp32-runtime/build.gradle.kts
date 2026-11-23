plugins {
    id("com.android.library")
}

android {
    namespace = "dev.virtualesp32"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // Reuse the exact QEMU binary and ESP32 ROM assets already shipped by the companion app.
            jniLibs.srcDir("../virtual-esp32/app/src/main/jniLibs")
            assets.srcDir("../virtual-esp32/app/src/main/assets")
        }
    }
}
