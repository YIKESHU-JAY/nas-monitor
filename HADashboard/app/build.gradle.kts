import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "io.github.nasmonitor.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.nasmonitor.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 238
        versionName = "238.0"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "mipmap-anydpi-v26/ic_launcher.png"
        }
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        val debugSigningConfig = getByName("debug")
        create("release") {
            if (keystorePropertiesFile.exists()) {
                val configuredStoreFile = keystoreProperties.getProperty("storeFile")
                    ?.takeIf { it.isNotBlank() } ?: error("keystore.properties 缺少 storeFile")
                storeFile = rootProject.file(configuredStoreFile)
                storePassword = keystoreProperties.getProperty("storePassword")
                    ?.takeIf { it.isNotBlank() } ?: error("keystore.properties 缺少 storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                    ?.takeIf { it.isNotBlank() } ?: error("keystore.properties 缺少 keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                    ?.takeIf { it.isNotBlank() } ?: error("keystore.properties 缺少 keyPassword")
            } else {
                initWith(debugSigningConfig)
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.github.mwiede:jsch:0.2.23")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Swipe to refresh
    implementation("com.google.accompanist:accompanist-swiperefresh:0.32.0")
    // System UI
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.32.0")
}
