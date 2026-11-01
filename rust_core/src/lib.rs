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
    pub fn derive_from_password(password: &mut String, salt: &[u8]) -> Result<Self, String> {
        let mut key = SecureKey { bytes: [0; 32] };
        let argon2 = Argon2::default();
        if let Err(e) = argon2.hash_password_into(password.as_bytes(), salt, &mut key.bytes) {
            password.zeroize();
            return Err(format!("Argon2 error: {}", e));
        }

        // We overwrite the password string with zeros in RAM.
        password.zeroize();

        Ok(key)
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

        // 4. Package everything up: [12 bytes Nonce] + [Ciphertext]
        let mut payload = nonce_bytes.to_vec();
        payload.extend_from_slice(&ciphertext);

        Ok(payload)
    }

    pub fn decrypt_data(&self, ciphertext: &[u8]) -> Result<Vec<u8>, String> {
        // Validate minimum length (12 bytes nonce + 16 bytes GCM auth tag)
        if ciphertext.len() < 28 {
            return Err("Decryption error: Ciphertext is too short".to_string());
        }

        // Extract the 12-byte nonce
        let nonce = Nonce::from_slice(&ciphertext[..12]);
        let actual_ciphertext = &ciphertext[12..];

        if self.bytes.len() != 32 {
            return Err("Decryption error: Invalid key length".to_string());
        }

        let key = GenericArray::from_slice(&self.bytes);
        let cipher = Aes256Gcm::new(key);

        let plaintext = cipher
            .decrypt(nonce, actual_ciphertext)
            .map_err(|e| format!("Decryption error: {}", e))?;

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
    let Ok(password_str) = env.get_string(&password) else {
        return std::ptr::null_mut();
    };
    let mut password_rust: String = password_str.into();

    let Ok(plaintext_rust) = env.convert_byte_array(&data) else {
        return std::ptr::null_mut();
    };

    // 1. Generate a random 16-byte salt for Argon2
    let mut salt = [0u8; 16];
    OsRng.fill_bytes(&mut salt);

    // 2. Derive key using the unique salt
    let Ok(key) = SecureKey::derive_from_password(&mut password_rust, &salt) else {
        return std::ptr::null_mut();
    };

    // 3. Encrypt data (returns [12 bytes Nonce] + [Ciphertext])
    let Ok(encrypted_payload) = key.encrypt_data(&plaintext_rust) else {
        return std::ptr::null_mut();
    };

    // 4. Prepend the 16-byte salt to the output payload:
    // Final layout: [16 bytes Salt] + [12 bytes Nonce] + [Ciphertext]
    let mut final_payload = salt.to_vec();
    final_payload.extend_from_slice(&encrypted_payload);

    match env.byte_array_from_slice(&final_payload) {
        Ok(output) => output.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_tuidev_ramlock_security_SecurityCore_decryptData<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    password: JString<'local>,
    data: JByteArray<'local>,
) -> jbyteArray {
    // 1. Safely extract password and ciphertext
    let Ok(password_str) = env.get_string(&password) else {
        return std::ptr::null_mut();
    };
    let mut password_rust: String = password_str.into();

    let Ok(payload_rust) = env.convert_byte_array(&data) else {
        return std::ptr::null_mut();
    };

    // Minimum payload: 16 bytes Salt + 12 bytes Nonce + 16 bytes Auth Tag = 44 bytes
    if payload_rust.len() < 44 {
        return std::ptr::null_mut();
    }

    // 2. Extract the 16-byte Argon2 salt from the beginning
    let salt = &payload_rust[..16];
    let encrypted_payload = &payload_rust[16..];

    // 3. Derive key using the extracted salt
    let Ok(key) = SecureKey::derive_from_password(&mut password_rust, salt) else {
        return std::ptr::null_mut();
    };

    // 4. Decrypt payload ([12 bytes Nonce] + [Ciphertext])
    match key.decrypt_data(encrypted_payload) {
        Ok(plaintext) => match env.byte_array_from_slice(&plaintext) {
            Ok(output) => output.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_tuidev_ramlock_security_SecurityCore_helloFromRust<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let rust_string = "¡Hola desde el núcleo criptográfico en Rust!";
    match env.new_string(rust_string) {
        Ok(output) => output.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
