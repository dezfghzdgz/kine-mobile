plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kine.clipper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kine.clipper"
        // Android 10+: zvuk hry (AudioPlaybackCapture) a ukládání do Galerie přes MediaStore bez oprávnění k úložišti.
        minSdk = 29
        targetSdk = 34
        versionCode = 6
        versionName = "0.4.2"
    }

    // Ostrý podpis z prostředí (GitHub Actions: tajemství KEYSTORE_* a cesta KINE_KEYSTORE); bez něj se release nepodepíše.
    val keystorePath = System.getenv("KINE_KEYSTORE")
    if (!keystorePath.isNullOrEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!keystorePath.isNullOrEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // Úmyslně nic: jen Android + standardní knihovna Kotlinu (přidá ji plugin sám).
}
