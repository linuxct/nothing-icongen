plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "space.linuxct.nothing.icongen"
    compileSdk = 36

    defaultConfig {
        applicationId = "space.linuxct.nothing.icongen"
        minSdk = 22
        targetSdk = 22

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // VersionCode: set by CI via CI_VERSION_CODE env (monotonically increasing)
        val envVersionCode = System.getenv("CI_VERSION_CODE")?.toIntOrNull()
        versionCode = envVersionCode ?: 1
        // VersionName: set by CI via CI_VERSION_NAME env (e.g. "1.0.0.5")
        versionName = System.getenv("CI_VERSION_NAME") ?: "1.0.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/**", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        }
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libaapt2.so", "**/libaapt.so")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation(files("libs/apksig-8.11.0.jar"))
    implementation(files("libs/bcpkix-jdk18on-1.80.2.jar"))
    implementation(files("libs/bcprov-jdk18on-1.80.2.jar"))
    implementation(files("libs/bcutil-jdk18on-1.80.2.jar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation("org.conscrypt:conscrypt-android:2.8.0")
}

// Robolectric supplies desktop Conscrypt. Android's JNI provider cannot run on the host JVM.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "org.conscrypt", module = "conscrypt-android")
}
