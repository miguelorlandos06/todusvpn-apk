import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.flutter.flutter-gradle-plugin")
}

// Firma de la APK de release: app/keystore.properties + app/release.jks (repo privado).
// Es el MISMO keystore de la APK Kotlin, así que la versión Flutter se instala
// como actualización directa encima de la original (mismo applicationId y firma).
// Si no existen, la release queda sin firmar y solo se usa la debug.
val keystoreProperties = Properties()
val keystorePropertiesFile = file("keystore.properties")
val keystoreAvailable = keystorePropertiesFile.exists()
if (keystoreAvailable) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "cu.todus.relayvpn"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    defaultConfig {
        // applicationId idéntico a la APK Kotlin: misma app, ahora con UI Flutter.
        applicationId = "cu.todus.relayvpn"
        minSdk = 24
        targetSdk = 36
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        ndk {
            abiFilters.clear()
            abiFilters.add("armeabi-v7a")
        }
    }

    signingConfigs {
        if (keystoreAvailable) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Igual que la APK original: sin ofuscación ni recorte (el núcleo
            // usa reflexión mínima y el usuario prefiere builds reproducibles).
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Si no hay keystore de release (p.ej. en CI), firmar con el de debug
            // para que la APK sea instalable sin subir la clave privada al repo.
            signingConfig = if (keystoreAvailable) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    // Solo lo que usa el núcleo Kotlin (idéntico al de la APK original).
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
