plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = providers.gradleProperty("buildNumber").orNull?.toIntOrNull() ?: 1
val appVersion = providers.gradleProperty("appVersion").orNull ?: "1.0.$buildNumber"
val keystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull?.takeIf { it.isNotBlank() }
val storePasswordValue = providers.environmentVariable("ANDROID_STORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val keyAliasValue = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
val keyPasswordValue = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "by.salesmaster.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "by.salesmaster.app"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = appVersion

        buildConfigField("String", "GITHUB_REPOSITORY", "\"zigorminsk-debug/sales-master\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
