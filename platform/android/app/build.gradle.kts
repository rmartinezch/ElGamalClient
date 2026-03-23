val androidXCoreVersion = "1.13.1"
val androidXAppCompatVersion = "1.7.0"
val materialVersion = "1.12.0"
val usbSerialVersion = "3.9.0"
val serialCommVersion = "2.11.4"
val verificatumBundleVersion = "3.1.0"
val verificatumVecjVersion = "2.2.0"
val verificatumVmgjVersion = "1.3.0"
val junit4Version = "4.13.2"
val androidXTestCoreVersion = "1.6.1"
val androidXTestRunnerVersion = "1.6.2"
val androidXTestJunitVersion = "1.2.1"
val androidXEspressoVersion = "3.6.1"

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pe.gob.onpe.votodigital.cifrador.android"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        targetSdk = 34

        ndk {
            abiFilters += listOf("x86_64", "arm64-v8a")
        }
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

    sourceSets {
        getByName("main") {
            java.srcDir("../../../src/main/java")
            jniLibs.srcDir("../../../prebuilt/android/jniLibs")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:$androidXCoreVersion")
    implementation("androidx.appcompat:appcompat:$androidXAppCompatVersion")
    implementation("com.google.android.material:material:$materialVersion")
    implementation("com.github.mik3y:usb-serial-for-android:$usbSerialVersion")
    implementation("com.verificatum:verificatum-vcr-vmgj-vecj:$verificatumBundleVersion")
    implementation("com.verificatum:verificatum-vecj:$verificatumVecjVersion")
    implementation("com.verificatum:verificatum-vmgj:$verificatumVmgjVersion")

    compileOnly("com.fazecast:jSerialComm:$serialCommVersion")

    testImplementation("junit:junit:$junit4Version")

    androidTestImplementation("androidx.test:core:$androidXTestCoreVersion")
    androidTestImplementation("androidx.test:runner:$androidXTestRunnerVersion")
    androidTestImplementation("androidx.test.ext:junit:$androidXTestJunitVersion")
    androidTestImplementation("androidx.test.espresso:espresso-core:$androidXEspressoVersion")
}
