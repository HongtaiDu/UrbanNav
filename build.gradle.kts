plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.urban"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.urban"
        minSdk = 26  // BLE scanning needs at least API 31 for new permission model
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")       // Fixes :39
    implementation("com.google.android.material:material:1.12.0") // Fixes :40
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") // Fixes :41
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7") // Fixes :42
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    // Nordic BLE Library
    implementation("no.nordicsemi.android:ble:2.11.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}