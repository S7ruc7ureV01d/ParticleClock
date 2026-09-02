plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.exoticbutters.amoledclock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.exoticbutters.amoledclock"
        minSdk = 16
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    val releaseKeystore = file("../release.keystore")
    if (releaseKeystore.exists()) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = "particleclock123"
                keyAlias = "particleclock"
                keyPassword = "particleclock123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = false
    }

    lint {
        abortOnError = false
    }
}

dependencies {
}
