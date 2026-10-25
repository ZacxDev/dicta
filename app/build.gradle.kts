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
        // 🔴 THE TREE IS THE SOURCE OF TRUTH FOR THE VERSION, NOT THE TAG.
        //
        // versionCode is derived from versionName by the convention
        //   major*10000 + minor*100 + patch   (0.2.0 -> 200)
        // and `scripts/assert-version-matches-tag.py` refuses a release tag
        // that disagrees with BOTH values here.
        //
        // The rejected alternative was injecting the version from the git tag
        // at build time. That leaves every LOCAL build at the gradle file's
        // unbumped value — versionCode 1 — and Android refuses to install a
        // lower versionCode over a higher one, so the first OTA would brick
        // the dev loop: `adb install` of a local debug build onto an OTA'd
        // handset fails with INSTALL_FAILED_VERSION_DOWNGRADE until you
        // uninstall, which destroys the dictation history. Bumping here and
        // making the tag match keeps local and published builds identical.
        versionCode = 201
        versionName = "0.2.1"
        // Instrumented tests only (androidTest/). They never run in CI —
        // there is no device there — and they are packaged into a SEPARATE
        // test APK, so this adds nothing to app-debug.apk. See the
        // androidTestImplementation note in `dependencies`.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 🔴 THE DEBUG SIGNING KEY IS EXPLICIT ON PURPOSE — do not delete this
    // block and let AGP fall back to its default.
    //
    // AGP AUTO-GENERATES ~/.android/debug.keystore when none exists. That is
    // convenient locally and a silent disaster in CI: a fresh GitHub runner has
    // no keystore, so AGP would mint a brand-new one, the release would build,
    // sign, upload and publish entirely green, and the ONLY place the problem
    // would ever surface is the operator's handset at install time, as
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE. The remedy at that point is an
    // uninstall, which destroys the on-device dictation history.
    //
    // So the keystore is a parameter, the release workflow supplies the real
    // one from a secret, and `scripts/assert-apk-signer.sh` verifies the BUILT
    // APK's signer fingerprint before anything is published. Two independent
    // mechanisms, because the failure is invisible until it is expensive.
    //
    // The defaults are the standard AGP debug values, so a plain local
    // `./gradlew :app:assembleDebug` behaves exactly as it did before this
    // block existed.
    //
    // Override with:
    //   -Pdicta.debugKeystore=/path/to/debug.keystore
    //   -Pdicta.debugKeystorePassword=... -Pdicta.debugKeyAlias=...
    //   -Pdicta.debugKeyPassword=...
    signingConfigs {
        getByName("debug") {
            storeFile = file(
                (project.findProperty("dicta.debugKeystore") as String?)
                    ?: "${System.getProperty("user.home")}/.android/debug.keystore"
            )
            storePassword =
                (project.findProperty("dicta.debugKeystorePassword") as String?) ?: "android"
            keyAlias =
                (project.findProperty("dicta.debugKeyAlias") as String?) ?: "androiddebugkey"
            keyPassword =
                (project.findProperty("dicta.debugKeyPassword") as String?) ?: "android"
        }
    }

    buildTypes {
        debug {
            // Explicit, though AGP would also wire this by name. Stated so that
            // deleting the signingConfigs block above is a compile error here
            // rather than a silent fallback to a generated key.
            signingConfig = signingConfigs.getByName("debug")
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
    // ## Runtime dependencies: still NONE — but now by decision, not default.
    //
    // Material Components (com.google.android.material) was explicitly
    // permitted for the 2026-10 design pass and was deliberately NOT taken.
    // Two reasons, in order:
    //
    //  1. Using M3 components means re-parenting the themes to
    //     Theme.Material3.*, and Theme.Dicta.Overlay's parent is load-bearing:
    //     the floating card's screen margin is decor padding taken from the
    //     <inset> in @drawable/overlay_window_bg, and Material3 dialog themes
    //     handle windowBackground differently. The overlay already shipped
    //     full-screen once from exactly that mechanism — see themes.xml and
    //     DictateOverlayThemeTest.
    //  2. Nothing in the design needed it. The palette is a surface ramp in
    //     colors.xml, the shapes are <shape>/<selector> drawables, the four
    //     actions are platform ImageButtons, and ripples/tooltips/
    //     compound-drawable tinting are all platform features at minSdk 26.
    //     (This used to say "the overflow is a platform ImageButton". The
    //     overflow was deleted — see activity_dictate.xml's header — so the
    //     example was naming a control that no longer exists.)
    //
    // 🔴 The claim is unchanged, so it was re-MEASURED rather than left
    // standing on its own authority. Package prefixes actually present in the
    // debug APK's dex (unzip classes*.dex + a type-descriptor scan,
    // 2026-10-12):
    //
    //     dev/zacx                       the app
    //     kotlin/*                       Kotlin stdlib
    //     org/jetbrains/, org/intellij/  the @Nullable/@NotNull annotation
    //                                    classes that ship INSIDE
    //                                    kotlin-stdlib. Not a separate
    //                                    dependency — but they are in the dex,
    //                                    so the old wording "the APK ships
    //                                    nothing but our own code" was
    //                                    overstated and is corrected here.
    //     java/*, android/*, dalvik/*    platform
    //
    // No androidx, no com.google.android.material, no third-party runtime code.
    //
    // The entries below are testImplementation: compiled into the unit-test
    // classpath only and never packaged. "No external RUNTIME dependencies" is
    // therefore true of the APK, not of the build.
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
