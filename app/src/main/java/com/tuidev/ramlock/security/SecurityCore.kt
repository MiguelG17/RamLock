package com.tuidev.ramlock.security

import android.util.Log

object SecurityCore {
    // Bloque init que carga la librería compilada en C/Rust al arrancar
    init {
        try {
            System.loadLibrary("rust_core")
        } catch (e: Throwable) {
            Log.e("RamLock", "Failed to load rust_core native library", e)
        }
    }

    external fun helloFromRust(): String

    // Returns null if the password is incorrect or data is corrupted
    external fun decryptData(password: String, data: ByteArray): ByteArray?
    external fun encryptData(password: String, data: ByteArray): ByteArray?
}
