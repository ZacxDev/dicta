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
    //     colors.xml, the shapes are <shape>/<selector> drawables, the overflow
    //     is a platform ImageButton, and ripples/tooltips/compound-drawable
    //     tinting are all platform features at minSdk 26.
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
}
