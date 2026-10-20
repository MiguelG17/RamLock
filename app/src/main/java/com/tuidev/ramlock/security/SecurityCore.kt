package com.tuidev.ramlock.security

object SecurityCore {
    // Bloque init que carga la librería compilada en C/Rust al arrancar
    init {
        // El nombre debe coincidir con el 'libname' configurado en build.gradle
        System.loadLibrary("rust_core")
    }

    // La palabra clave 'external' le dice a Kotlin que esta función no tiene cuerpo,
    // sino que la JVM debe buscarla en la librería nativa cargada.
    external fun helloFromRust(): String

    // Returns null if the password is incorrect or data is corrupted
    external fun decryptData(password: String, data: ByteArray): ByteArray?
    external fun encryptData(password: String, data: ByteArray): ByteArray
}
