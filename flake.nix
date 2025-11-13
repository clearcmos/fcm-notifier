{
  description = "FCM Notifier Android app development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    android-nixpkgs = {
      url = "github:tadfisher/android-nixpkgs";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, android-nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { inherit system; };

      android-sdk = android-nixpkgs.sdk.${system} (sdkPkgs: with sdkPkgs; [
        cmdline-tools-latest
        build-tools-35-0-0
        platform-tools
        platforms-android-35
        emulator
      ]);

    in {
      devShells.${system}.default = pkgs.mkShell {
        buildInputs = with pkgs; [
          android-sdk
          jdk17
          gradle
        ];

        shellHook = ''
          export ANDROID_HOME="${android-sdk}/share/android-sdk"
          export ANDROID_SDK_ROOT="$ANDROID_HOME"
          export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

          echo "🔔 FCM Notifier development environment ready!"
          echo "ANDROID_HOME: $ANDROID_HOME"
          echo ""
          echo "Commands:"
          echo "  ./gradlew assembleDebug          - Build debug APK"
          echo "  ./gradlew installDebug            - Build and install to device"
          echo "  adb devices                       - List connected devices"
          echo ""
          echo "APK will be at: app/build/outputs/apk/debug/app-debug.apk"
        '';
      };
    };
}
