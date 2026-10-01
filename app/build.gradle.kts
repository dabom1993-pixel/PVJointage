plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.adf.pvjointage"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.adf.pvjointage"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Deux variantes installables côte à côte sur une même tablette (applicationId différent) :
    // - "stable" : l'application de production, accessible à tous (release GitHub "tablette-latest").
    // - "beta"   : app distincte ("PV Jointage BETA"), pour tester les nouveautés sur une tablette
    //              avant de les diffuser à tous (release GitHub "tablette-beta").
    // Chaque variante vérifie/installe ses propres mises à jour séparément (voir UpdateManager.kt),
    // sans jamais interférer avec l'autre.
    flavorDimensions += "channel"
    productFlavors {
        create("stable") {
            dimension = "channel"
            resValue("string", "app_name", "PV Jointage")
            buildConfigField("String", "UPDATE_RELEASE_TAG", "\"tablette-latest\"")
            buildConfigField("String", "UPDATE_ASSET_NAME", "\"PVJointage.apk\"")
        }
        create("beta") {
            dimension = "channel"
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            resValue("string", "app_name", "PV Jointage BETA")
            buildConfigField("String", "UPDATE_RELEASE_TAG", "\"tablette-beta\"")
            buildConfigField("String", "UPDATE_ASSET_NAME", "\"PVJointage-beta.apk\"")
        }
    }

    signingConfigs {
        // Clé de debug fixe (générée une fois par le workflow GitHub Actions et
        // committée dans le dépôt) afin que chaque nouvelle APK puisse s'installer
        // par-dessus la précédente sans devoir désinstaller l'app à chaque mise à jour.
        getByName("debug") {
            val debugKeystore = file("debug.keystore")
            if (debugKeystore.exists()) {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }

    packaging {
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Room (local storage)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Image loading for photo thumbnails
    implementation("io.coil-kt:coil:2.6.0")
}
