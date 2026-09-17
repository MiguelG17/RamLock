# 🔒 RamLock

!\[Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge\&logo=android\&logoColor=white)
!\[Rust](https://img.shields.io/badge/Rust-000000?style=for-the-badge\&logo=rust\&logoColor=white)
!\[Kotlin](https://img.shields.io/badge/Kotlin-0095D5?style=for-the-badge\&logo=kotlin\&logoColor=white)
!\[Jetpack Compose](https://img.shields.io/badge/Compose-4285F4?style=for-the-badge\&logo=jetpackcompose\&logoColor=white)
!\[License](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)

> \*\*Secure, local, and memory-safe file encryption vault for Android.\*\*

RamLock is a modern, production-ready Android application that provides true local file encryption. By combining a sleek Jetpack Compose interface with a low-level cryptographic engine written in Rust, RamLock guarantees that your data and passwords remain in RAM for the shortest possible time, mitigating memory scraping and unauthorized access.

## ✨ Features

* **Memory-Safe Core:** Cryptographic operations are handled natively in Rust. Plaintext buffers are zeroed out (`zeroize`) immediately after use.
* **Zero-Knowledge Architecture:** Everything happens locally. No cloud sync, no tracking, no external servers.
* **Agnostic File Support:** Encrypts everything from small documents to massive video files using block-based AES-256-GCM streaming via JNI.
* **Modern UI/UX:** Built entirely with Kotlin and Jetpack Compose (Material Design 3), featuring a dark-themed, intuitive vault experience.
* **Seamless Integration:** Uses Android's Storage Access Framework (SAF) to securely import and isolate files into an encrypted sandbox.

## 🏗️ Architecture

RamLock follows Clean Architecture principles, ensuring a strict boundary between the UI and the native security core:

* **UI Layer (Kotlin):** Jetpack Compose, ViewModels, and StateFlow.
* **Data Layer (Kotlin):** Room Database for encrypted file metadata indexing, and Coroutines (`Dispatchers.IO`) for non-blocking JNI calls.
* **Native Security Layer (Rust):** Shared library (`.so`) compiled via `rust-android-gradle` exposing a safe JNI bridge to Android.

## 🚀 Getting Started

### Prerequisites

* [Android Studio](https://developer.android.com/studio) (Koala or newer recommended)
* Android NDK and CMake (Install via SDK Manager)
* [Rust Toolchain](https://rustup.rs/) (`rustup default stable`)
* Cross-compilation targets for Android:

```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86\_64-linux-android
  ```

### Build Instructions

1. Clone the repository:

```bash
   git clone https://github.com/MiguelG17/RamLock.git
   ```

2. Open the project in Android Studio.
3. Gradle will automatically invoke Cargo to compile the `rust\_core` library during the build process.
4. Run the app on your emulator or physical device.

## 🧪 Testing

We enforce strict quality control:

* **Rust Core:** Run unit tests and memory safety checks via `cargo test` and `cargo clippy`.
* **Android:** Run UI and unit tests via `./gradlew test` and linting via `./gradlew detekt`.

\---

*Crafted with precision for secure local storage.*

