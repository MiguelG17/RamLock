use argon2::Argon2;
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jstring};
use zeroize::{Zeroize, ZeroizeOnDrop};

// 1. THE SECURITY LOGIC (Rust's internal logic)
// ====================================================

use aes_gcm::aead::{Aead, generic_array::GenericArray};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use rand_core::{OsRng, RngCore};

// Structure that will store the 32-byte key and self-destruct upon completion.
#[derive(Zeroize, ZeroizeOnDrop)]
pub struct SecureKey {
    pub bytes: [u8; 32],
}

impl SecureKey {
    pub fn derive_from_password(password: &mut String, salt: &[u8]) -> Self {
        let mut key = SecureKey { bytes: [0; 32] };
        let argon2 = Argon2::default();
        argon2
            .hash_password_into(password.as_bytes(), salt, &mut key.bytes)
            .expect("Fatal error in the Argon2 derivation");

        // We overwrite the password string with zeros in RAM.
        password.zeroize();

        key
    }

    pub fn encrypt_data(&self, plaintext: &[u8]) -> Result<Vec<u8>, String> {
        // 1. Initialize the AES cipher with the 32 bytes of the derived key.
        let key = GenericArray::from_slice(&self.bytes);
        let cipher = Aes256Gcm::new(key);

        // 2. We generate the 12-byte nonce using operating-system-level randomness.
        let mut nonce_bytes = [0u8; 12];
        OsRng.fill_bytes(&mut nonce_bytes);
        let nonce = Nonce::from_slice(&nonce_bytes);

        // 3. We execute the encryption.
        // AES-GCM encrypts the data and also adds an authentication tag to prevent tampering.
        let ciphertext = cipher
            .encrypt(nonce, plaintext)
            .map_err(|e| format!("Encryption error: {}", e))?;

        // 4. Package everything up.
        // To decrypt later, we need the exact Nonce, so we store it at the beginning of the file.
        let mut final_payload = nonce_bytes.to_vec();
        final_payload.extend_from_slice(&ciphertext);

        // We return the dynamic vector (Vec<u8>), which now contains: [12 bytes of Nonce] + [N bytes of encrypted data]
        Ok(final_payload)
    }

    pub fn decrypt_data(&self, ciphertext: &[u8]) -> Result<Vec<u8>, String> {
        // 1. Validate the minimum length to avoid a panic in the slice
        // (12 bytes of nonce + 16 bytes for the GCM authentication tag as an absolute minimum)
        if ciphertext.len() < 28 {
            return Err("Decryption error: Ciphertext is too short".to_string());
        }
        // 2. Extract the nonce from the ciphertext.
        let nonce = Nonce::from_slice(&ciphertext[..12]);
        let actual_ciphertext = &ciphertext[12..];

        // 3. Validate the key size before creating the GenericArray (32 bytes for AES-256)
        if self.bytes.len() != 32 {
            return Err("Decryption error: Invalid key length".to_string());
        }

        // 4. Initialize the AES cipher with the 32 bytes of the derived key.
        let key = GenericArray::from_slice(&self.bytes);
        let cipher = Aes256Gcm::new(key);

        // 5. We execute the decryption.

        let plaintext = cipher
            .decrypt(nonce, actual_ciphertext)
            .map_err(|e| format!("Decryption error: {}", e))?;

        // We return the dynamic vector (Vec<u8>), which now contains data without encryption.
        Ok(plaintext)
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_tuidev_ramlock_security_SecurityCore_encryptData<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    password: JString<'local>,
    data: JByteArray<'local>,
) -> jbyteArray {
    let mut password_rust: String = env.get_string(&password).unwrap().into();
    let plainttext_rust = env.convert_byte_array(&data).unwrap();

    let key = SecureKey::derive_from_password(&mut password_rust, b"saltsalt");
    let ciphertext = key.encrypt_data(&plainttext_rust).unwrap();
    let output = env.byte_array_from_slice(&ciphertext).unwrap();
    output.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_tuidev_ramlock_security_SecurityCore_decryptData<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    password: JString<'local>,
    data: JByteArray<'local>,
) -> jbyteArray {
    let mut password_rust: String = env.get_string(&password).unwrap().into();
    let ciphertext_rust = env.convert_byte_array(&data).unwrap();

    let key = SecureKey::derive_from_password(&mut password_rust, b"saltsalt");
    let plaintext = key.decrypt_data(&ciphertext_rust).unwrap();
    let output = env.byte_array_from_slice(&plaintext).unwrap();
    output.into_raw()
}

// #[no_mangle] evita que el compilador de Rust cambie el nombre de la función,
// permitiendo que la JVM de Android la encuentre exactamente como la definimos.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_tuidev_ramlock_security_SecurityCore_helloFromRust<'local>(
    env: JNIEnv<'local>,
    // JClass representa la clase de Kotlin que llama a esta función (SecurityCore)
    _class: JClass<'local>,
) -> jstring {
    // 1. Creamos un String normal en Rust
    let rust_string = "¡Hola desde el núcleo criptográfico en Rust!";

    // 2. Convertimos el String de Rust a un formato que la JVM entienda (JString)
    // El JNIEnv requiere mutabilidad ('mut env') para alojar memoria en la JVM
    let output: JString = env
        .new_string(rust_string)
        .expect("Fallo al crear el string de Java");

    // 3. Devolvemos el puntero en crudo a la JVM para que ella gestione la memoria a partir de ahora
    output.into_raw()
}
