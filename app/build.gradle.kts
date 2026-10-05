plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

base.archivesName.set("triche-bot")

android {
    namespace = "fr.triche.bot"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.triche.bot"
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
