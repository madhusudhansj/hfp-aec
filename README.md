ARM64 debug prototype for Samsung Galaxy S9+ on PixelOS Android 12L, built from commit 20d82afd70b8ac478afc8ee302e4102c36cb2446.

Java and native compilation, APK v2 signature, alignment, ARM64 ELF and JNI exports were verified. No device was attached; microphone capture, output, foreground service lifecycle, boot behavior, USB routing and latency are not yet physically tested. No automated tests exist. Lint reports one Play target-SDK error and eight warnings; this prototype targets API 32 for sideloading.

This milestone contains passive boot standby and user-armed AAudio diagnostics. Bluetooth/HCI and WebRTC are not integrated. Existing issues include service status reporting running after native failure and coupled input/output startup.

Download s9-hfp-engine-v0.1.0-arm64-debug.apk, install it, grant microphone permission and arm audio from the visible activity. Enable boot standby separately; boot does not arm microphone capture.
