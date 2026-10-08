plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

base.archivesName.set("triche-stack")

android {
    namespace = "fr.triche.stack"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.triche.stack"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
