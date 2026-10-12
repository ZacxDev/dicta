plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.zacx.dicta"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.zacx.dicta"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // Instrumented tests only (androidTest/). They never run in CI —
        // there is no device there — and they are packaged into a SEPARATE
        // test APK, so this adds nothing to app-debug.apk. See the
        // androidTestImplementation note in `dependencies`.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
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

    testOptions {
        unitTests {
            // Robolectric needs the merged resources/assets/manifest to inflate
            // layouts and resolve theme attributes.
            isIncludeAndroidResources = true
            all {
                // Robolectric reflects into java.io/java.lang internals to
                // emulate libcore (e.g. FileDescriptor.fd for
                // ParcelFileDescriptor). Under JDK 17's module system that
                // throws InaccessibleObjectException unless the packages are
                // opened.
                it.jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.nio=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                )
            }
        }
    }
}

dependencies {
    // Runtime: no external dependencies — framework + Kotlin stdlib only, so
    // the APK ships nothing but our own code. The entries below are
    // testImplementation: they are compiled into the unit-test classpath only
    // and are never packaged. "No external dependencies" is therefore true of
    // the APK, not of the build.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")

    // androidTestImplementation: instrumented tests, packaged into a SEPARATE
    // test APK (dev.zacx.dicta.test) and never into app-debug.apk. The
    // "zero androidx classes in the shipped dex" property is therefore
    // untouched — and it is checked rather than asserted: see the dex scan in
    // the PR, which parses every classes*.dex in app-debug.apk and reports
    // both the forbidden prefixes (0) and a positive control (>0), because a
    // zero from a scan that matches nothing is indistinguishable from a real
    // zero.
    //
    // Why instrumented tests exist at all: three things cannot be established
    // on the JVM. AndroidKeyStore has no Robolectric implementation, so the
    // real KeystoreCipher is unverifiable off-device; MediaCodec/MediaMuxer
    // likewise, so whether our Opus/Ogg output is actually ffmpeg-decodable is
    // a device question; and the manifest's INTERNET permission plus the
    // scoped networkSecurityConfig only take effect in a real app process.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
