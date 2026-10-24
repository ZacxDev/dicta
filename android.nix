# Android SDK + JDK 17 + Gradle dev shell, for NixOS hosts.
#
#   nix-shell android.nix --run './gradlew :app:testDebugUnitTest \
#     -Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/34.0.0/aapt2"'
#
# The aapt2 override is not optional here — see README "Build". AGP downloads a
# generic-linux aapt2 that a NixOS host cannot exec, and the failure names a
# missing interpreter rather than the real cause.
#
# 🔴 nixpkgs is PINNED to an exact revision on purpose. An `import <nixpkgs>`
# here would resolve against whatever channel the invoking host happens to have,
# so two machines — or the same machine a month apart — would silently build
# against different build-tools, a different JDK patch and a different Gradle.
# That makes "the suite is green" a claim about a toolchain nobody recorded.
# Bump deliberately: change `rev`, then re-run
#   nix-prefetch-url --unpack "https://github.com/NixOS/nixpkgs/archive/<rev>.tar.gz"
# and replace `sha256` with what it prints. A stale sha256 fails loudly (hash
# mismatch) rather than quietly fetching something else, which is the point.
let
  # nixos-unstable @ 2026-08-19. This is the toolchain the suite was last run
  # green against, at the head of feat/bubble-state-tile-boot on 2026-10-23.
  #
  # 🔴 THE SUITE SIZE IS DELIBERATELY NOT RESTATED HERE, and re-adding it is
  # the regression. It used to be, with the argument that a count makes "last
  # run against" checkable rather than a bare date — but nothing in this file
  # asserts on that count, so the copy here could only ever rot, and it did:
  # within four lines it gave two different sizes for the same suite. A number
  # nothing checks is unpinned by construction. The count lives in
  # .github/workflows/tests.yml, which is the file that actually gates on it,
  # and TestCountFloorTest.onlyTheCiGateQuotesASuiteSizeBaseline fails if it
  # reappears here. The checkable claim is the `rev` below plus the branch and
  # date above — identify the TREE, not the size of its suite.
  rev = "ffb3c9b700e759be2ef13237c9d8f953b32a1e46";
  nixpkgs = builtins.fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/${rev}.tar.gz";
    sha256 = "0h1nqp7vdqqivfc8fimdc6rmjyrnjlsmd28zxbl1iy46c0ss8ga4";
  };

  pkgs = import nixpkgs {
    config.allowUnfree = true;
    config.android_sdk.accept_license = true;
  };

  sdk = (pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "34" ];
    buildToolsVersions = [ "34.0.0" ];
    includeEmulator = false;
    includeSystemImages = false;
    includeNDK = false;
  }).androidsdk;
in
pkgs.mkShell {
  packages = [ sdk pkgs.gradle_8 pkgs.jdk17 pkgs.kotlin pkgs.zip pkgs.android-tools ];
  ANDROID_HOME = "${sdk}/libexec/android-sdk";
  ANDROID_SDK_ROOT = "${sdk}/libexec/android-sdk";
  JAVA_HOME = "${pkgs.jdk17}";
}
