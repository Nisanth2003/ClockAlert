plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.alarmtracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.alarmtracker"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
    lint {
        // androidx.lifecycle's RepeatOnLifecycleDetector throws inside lint on this AGP/Kotlin
        // combination and takes the whole run down with it. Skipping that one check keeps the rest
        // of lint usable; drop this line once the androidx.lifecycle lint artifact is fixed.
        disable += "RepeatOnLifecycleWrongUsage"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.kotlinx.coroutines.android)
    // Phase B: on-device camera (QR + photo missions) and barcode scanning.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    // Phase C: nightly pre-flight health-check job (never fires the alarm itself).
    implementation(libs.androidx.work.runtime.ktx)
    // v3 event alarms: geofencing (OS-woken transitions) + one-shot location fixes.
    // No map SDK, no API key — destinations resolve via android.location.Geocoder.
    implementation(libs.play.services.location)
    // v6 "pick on a map": OpenStreetMap tiles, NO API key / billing (only internet + a cache dir).
    implementation(libs.osmdroid.android)
    // v6 sleep: read real sleep sessions from other health/watch apps via Health Connect (optional).
    implementation(libs.health.connect.client)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
