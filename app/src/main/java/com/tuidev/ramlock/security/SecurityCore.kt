package com.tuidev.ramlock.security

import android.util.Log

fun interface StreamProgressListener {
    fun onProgress(bytesProcessed: Long, totalBytes: Long)
}

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

    // Streaming encryption and decryption with File Descriptors (0-heap allocation)
    external fun encryptStream(
        password: String,
        inFd: Int,
        outFd: Int,
        totalBytes: Long,
        listener: StreamProgressListener?
    ): Boolean

    fun encryptStream(password: String, inFd: Int, outFd: Int): Boolean =
        encryptStream(password, inFd, outFd, 0L, null)

    external fun decryptStream(
        password: String,
        inFd: Int,
        outFd: Int,
        totalBytes: Long,
        listener: StreamProgressListener?
    ): Boolean

    fun decryptStream(password: String, inFd: Int, outFd: Int): Boolean =
        decryptStream(password, inFd, outFd, 0L, null)
}
