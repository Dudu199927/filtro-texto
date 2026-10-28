plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Clave de firma fija. El flujo de GitHub la reconstruye a partir de app/filtro.jks.b64.
// Con la misma clave, Android instala cada versión nueva como una actualización.
val claveFija = file("filtro.jks")

android {
    namespace = "com.filtrotexto.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.filtrotexto.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("fija") {
            if (claveFija.exists()) {
                storeFile = claveFija
                storePassword = "android"
                keyAlias = "filtro"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            if (claveFija.exists()) {
                signingConfig = signingConfigs.getByName("fija")
            }
        }
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

dependencies {
    // Lee RAR (incluido RAR5) y 7z. Versión fija: commit del repositorio en JitPack.
    implementation("com.github.seven332.a7zip:extract-lite:cba0ce9586")
}
