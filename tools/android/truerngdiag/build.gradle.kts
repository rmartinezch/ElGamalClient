val androidXCoreVersion = "1.13.1"
val androidXAppCompatVersion = "1.7.0"
val materialVersion = "1.12.0"
val usbSerialVersion = "3.9.0"
val verificatumBundleVersion = "3.1.0"
val verificatumVecjVersion = "2.2.0"
val verificatumVmgjVersion = "1.3.0"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pe.gob.onpe.votodigital.truerngdiag"
    compileSdk = 34

    defaultConfig {
        applicationId = "pe.gob.onpe.votodigital.truerngdiag"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val cifradorAar = file("../../../prebuilt/android/aar/ElGamalCipher-android-debug.aar")

    implementation("androidx.core:core-ktx:$androidXCoreVersion")
    implementation("androidx.appcompat:appcompat:$androidXAppCompatVersion")
    implementation("com.google.android.material:material:$materialVersion")
    implementation(files(cifradorAar))
    implementation("com.github.mik3y:usb-serial-for-android:$usbSerialVersion")
    implementation("com.verificatum:verificatum-vcr-vmgj-vecj:$verificatumBundleVersion")
    implementation("com.verificatum:verificatum-vecj:$verificatumVecjVersion")
    implementation("com.verificatum:verificatum-vmgj:$verificatumVmgjVersion")
}
