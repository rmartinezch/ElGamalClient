import java.time.OffsetDateTime

val androidXCoreVersion = "1.13.1"
val androidXAppCompatVersion = "1.7.0"
val materialVersion = "1.12.0"
val usbSerialVersion = "3.9.0"
val verificatumBundleVersion = "3.1.0"
val verificatumVecjVersion = "2.2.0"
val verificatumVmgjVersion = "1.3.0"
val junit4Version = "4.13.2"
val cifradorAar = rootProject.projectDir.resolve("../../../prebuilt/android/aar/ElGamalCipher-android-debug.aar")
val debugApkMarker = "20260323"
val interfaceBuildTimestamp = OffsetDateTime.now().toString()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pe.gob.onpe.votodigital.votante.android"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "pe.gob.onpe.votodigital.votante.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "INTERFACE_BUILD_TIMESTAMP", "\"$interfaceBuildTimestamp\"")
        buildConfigField("String", "INTERFACE_DISPLAY_NAME", "\"Votante Android\"")

        ndk {
            abiFilters += listOf("x86_64", "arm64-v8a")
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-$debugApkMarker"
            resValue("string", "app_name", "Votante Android $debugApkMarker")
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

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir("src/main/assets")
            assets.srcDir("src/generated/assets")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/*.kotlin_module",
                "/META-INF/versions/**"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:$androidXCoreVersion")
    implementation("androidx.appcompat:appcompat:$androidXAppCompatVersion")
    implementation("com.google.android.material:material:$materialVersion")
    implementation("com.github.mik3y:usb-serial-for-android:$usbSerialVersion")
    implementation(files(cifradorAar))
    implementation("com.verificatum:verificatum-vcr-vmgj-vecj:$verificatumBundleVersion")
    implementation("com.verificatum:verificatum-vecj:$verificatumVecjVersion")
    implementation("com.verificatum:verificatum-vmgj:$verificatumVmgjVersion")

    testImplementation("junit:junit:$junit4Version")
}
