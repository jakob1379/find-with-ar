{
  description = "AR BLE locator Android development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    { nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachSystem [ "x86_64-linux" ] (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        buildToolsVersion = "36.0.0";

        baseAndroidArgs = {
          platformVersions = [ "37" ];
          buildToolsVersions = [ buildToolsVersion ];
          cmdLineToolsVersion = "latest";
          platformToolsVersion = "latest";
          includeCmake = false;
          includeNDK = false;
        };

        android = pkgs.androidenv.composeAndroidPackages baseAndroidArgs;

        androidWithEmulator = pkgs.androidenv.composeAndroidPackages (
          baseAndroidArgs
          // {
            platformVersions = [ "37.0" ];
            includeEmulator = true;
            emulatorVersion = "latest";
            includeSystemImages = true;
            systemImageTypes = [ "google_apis" ];
            abiVersions = [ "x86_64" ];
          }
        );

        mkAndroidShell =
          composition: extraPackages:
          let
            sdkRoot = "${composition.androidsdk}/libexec/android-sdk";
          in
          pkgs.mkShell {
            packages = [
              pkgs.binutils
              pkgs.curl
              pkgs.git
              pkgs.jdk17_headless
              pkgs.jq
              pkgs.just
              pkgs.openssl
              pkgs.python312
              pkgs.unzip
              pkgs.uv
              composition.androidsdk
            ]
            ++ extraPackages;

            JAVA_HOME = pkgs.jdk17_headless.home;
            ANDROID_HOME = sdkRoot;
            ANDROID_SDK_ROOT = sdkRoot;
            GRADLE_OPTS =
              "-Dorg.gradle.project.android.aapt2FromMavenOverride="
              + "${sdkRoot}/build-tools/${buildToolsVersion}/aapt2";

            shellHook = ''
              export ANDROID_USER_HOME="$PWD/.android"
              export GRADLE_USER_HOME="$PWD/.gradle-home"
            '';
          };
      in
      {
        formatter = pkgs.nixfmt;
        devShells.default = mkAndroidShell android [ ];
        devShells.ci = mkAndroidShell androidWithEmulator [ ];
        devShells.ide = mkAndroidShell androidWithEmulator [
          (pkgs.android-studio.withSdk androidWithEmulator.androidsdk)
        ];
      }
    );
}
