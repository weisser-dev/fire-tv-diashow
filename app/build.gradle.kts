plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "dev.weisser.googlephotoscarousel"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.weisser.googlephotoscarousel"
        minSdk = 22
        targetSdk = 35
        versionCode = 16
        versionName = "0.10.6"
        val ambientClientId = providers.gradleProperty("ambientClientId").orElse(providers.environmentVariable("AMBIENT_CLIENT_ID")).getOrElse("")
        val ambientClientSecret = providers.gradleProperty("ambientClientSecret").orElse(providers.environmentVariable("AMBIENT_CLIENT_SECRET")).getOrElse("")
        buildConfigField("String", "AMBIENT_CLIENT_ID", "\"${ambientClientId}\"")
        buildConfigField("String", "AMBIENT_CLIENT_SECRET", "\"${ambientClientSecret}\"")
    }

    val releaseStoreFile = providers.gradleProperty("releaseStoreFile").orNull
    val releaseStorePassword = providers.gradleProperty("releaseStorePassword").orNull
    val releaseKeyAlias = providers.gradleProperty("releaseKeyAlias").orNull
    val releaseKeyPassword = providers.gradleProperty("releaseKeyPassword").orNull
    if (releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            if (releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("com.google.android.gms:play-services-auth:21.4.0")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
