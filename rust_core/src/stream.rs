use crate::SecureKey;
use aes_gcm::aead::{Aead, generic_array::GenericArray};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use rand_core::{OsRng, RngCore};
use std::io::{Read, Write};
use zeroize::Zeroize;

/// Helper function to fill a slice completely unless EOF is encountered.
/// Handles partial reads gracefully in Linux/Android filesystems.
fn read_full<R: Read>(reader: &mut R, buf: &mut [u8]) -> Result<usize, std::io::Error> {
    let mut total_read = 0;
    while total_read < buf.len() {
        match reader.read(&mut buf[total_read..]) {
            Ok(0) => break, // True EOF
            Ok(n) => total_read += n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        }
    }
    Ok(total_read)
}

pub fn encrypt_stream<R: Read, W: Write>(
    password: &mut String,
    reader: &mut R,
    writer: &mut W,
) -> Result<(), String> {
    encrypt_stream_with_progress(password, reader, writer, None::<fn(u64)>)
}

pub fn encrypt_stream_with_progress<R: Read, W: Write, F: FnMut(u64)>(
    password: &mut String,
    reader: &mut R,
    writer: &mut W,
    mut progress_cb: Option<F>,
) -> Result<(), String> {
    // 1. Generate random salt (16 bytes) and nonce prefix (7 bytes)
    let mut salt = [0u8; 16];
    OsRng.fill_bytes(&mut salt);

    let mut nonce_prefix = [0u8; 7];
    OsRng.fill_bytes(&mut nonce_prefix);

    // 2. Derive 32-byte key with Argon2id and zeroize password
    let key = SecureKey::derive_from_password(password, &salt)?;

    // 3. Write header: b"RMLK" (4 bytes) + version 0x01 (1 byte) + salt (16 bytes) + nonce_prefix (7 bytes)
    writer.write_all(b"RMLK").map_err(|e| format!("Header write error: {}", e))?;
    writer.write_all(&[0x01]).map_err(|e| format!("Header write error: {}", e))?;
    writer.write_all(&salt).map_err(|e| format!("Header write error: {}", e))?;
    writer.write_all(&nonce_prefix).map_err(|e| format!("Header write error: {}", e))?;

    // 4. Initialize AES-256-GCM cipher
    let cipher_key = GenericArray::from_slice(&key.bytes);
    let cipher = Aes256Gcm::new(cipher_key);

    const CHUNK_SIZE: usize = 256 * 1024; // 256 KB (4x fewer I/O syscalls)

    // Zero allocations inside loop: allocate two buffers once before entering the loop
    let mut buf_current = vec![0u8; CHUNK_SIZE];
    let mut buf_next = vec![0u8; CHUNK_SIZE];

    let mut bytes_read = read_full(reader, &mut buf_current)
        .map_err(|e| format!("IO read error: {}", e))?;

    let mut chunk_counter: u32 = 0;
    let mut total_processed: u64 = 0;

    loop {
        let next_bytes_read = read_full(reader, &mut buf_next)
            .map_err(|e| format!("IO read error: {}", e))?;

        let is_last: u8 = if next_bytes_read == 0 { 1 } else { 0 };

        // Build 12-byte Nonce: 7 bytes prefix + 4 bytes counter (BE) + 1 byte is_last flag
        let mut nonce_bytes = [0u8; 12];
        nonce_bytes[..7].copy_from_slice(&nonce_prefix);
        nonce_bytes[7..11].copy_from_slice(&chunk_counter.to_be_bytes());
        nonce_bytes[11] = is_last;

        let nonce = Nonce::from_slice(&nonce_bytes);

        let encrypted_chunk = cipher
            .encrypt(nonce, &buf_current[..bytes_read])
            .map_err(|e| format!("Encryption error at chunk {}: {}", chunk_counter, e))?;

        let chunk_len = (encrypted_chunk.len() as u32).to_be_bytes();
        writer.write_all(&chunk_len).map_err(|e| format!("IO write error: {}", e))?;
        writer.write_all(&encrypted_chunk).map_err(|e| format!("IO write error: {}", e))?;

        total_processed += bytes_read as u64;
        if let Some(ref mut cb) = progress_cb {
            cb(total_processed);
        }

        if is_last == 1 {
            break;
        }

        // Zero-allocation buffer swap
        std::mem::swap(&mut buf_current, &mut buf_next);
        bytes_read = next_bytes_read;
        chunk_counter = chunk_counter.checked_add(1).ok_or("Chunk counter overflow")?;
    }

    writer.flush().map_err(|e| format!("IO flush error: {}", e))?;

    Ok(())
}

pub fn decrypt_stream<R: Read, W: Write>(
    password: &mut String,
    reader: &mut R,
    writer: &mut W,
) -> Result<(), String> {
    decrypt_stream_with_progress(password, reader, writer, None::<fn(u64)>)
}

pub fn decrypt_stream_with_progress<R: Read, W: Write, F: FnMut(u64)>(
    password: &mut String,
    reader: &mut R,
    writer: &mut W,
    mut progress_cb: Option<F>,
) -> Result<(), String> {
    // 1. Read header (28 bytes): 4B magic + 1B version + 16B salt + 7B nonce prefix
    let mut header = [0u8; 28];
    let header_read = read_full(reader, &mut header).map_err(|e| {
        password.zeroize();
        format!("Header read error: {}", e)
    })?;

    if header_read < 28 {
        password.zeroize();
        return Err("Invalid header: Stream is too short".to_string());
    }

    // Validate Magic bytes b"RMLK"
    if &header[..4] != b"RMLK" {
        password.zeroize();
        return Err("Invalid header: Missing RMLK magic bytes".to_string());
    }

    // Validate Version 0x01
    if header[4] != 0x01 {
        password.zeroize();
        return Err(format!("Unsupported version: 0x{:02x}", header[4]));
    }

    let salt = &header[5..21];
    let nonce_prefix = &header[21..28];

    // 2. Derive 32-byte key with Argon2id and zeroize password
    let key = SecureKey::derive_from_password(password, salt)?;

    // 3. Initialize AES-256-GCM cipher
    let cipher_key = GenericArray::from_slice(&key.bytes);
    let cipher = Aes256Gcm::new(cipher_key);

    const MAX_CHUNK_LEN: usize = 1024 * 1024 + 16; // Up to 1 MB per chunk (supports 64KB, 256KB, 1MB)

    // 4. Read initial 4-byte chunk length
    let mut len_buf = [0u8; 4];
    let len_bytes_read = read_full(reader, &mut len_buf)
        .map_err(|e| format!("IO read error: {}", e))?;

    if len_bytes_read == 0 {
        return Err("Invalid encrypted file: No data chunks found".to_string());
    } else if len_bytes_read < 4 {
        return Err("Corrupted file: Truncated chunk length header".to_string());
    }

    let mut chunk_counter: u32 = 0;
    let mut total_processed: u64 = 0;

    loop {
        let chunk_len = u32::from_be_bytes(len_buf) as usize;
        if chunk_len > MAX_CHUNK_LEN {
            return Err(format!(
                "Corrupted or malicious file: Chunk {} length {} exceeds limit of {} bytes",
                chunk_counter, chunk_len, MAX_CHUNK_LEN
            ));
        }
        let mut encrypted_chunk = vec![0u8; chunk_len];

        let bytes_read = read_full(reader, &mut encrypted_chunk)
            .map_err(|e| format!("IO read error: {}", e))?;

        if bytes_read < chunk_len {
            return Err(format!(
                "Corrupted file: Truncated chunk {} (expected {} bytes, got {})",
                chunk_counter, chunk_len, bytes_read
            ));
        }

        // Read ahead 4 bytes to check if another chunk follows
        let mut next_len_buf = [0u8; 4];
        let next_len_read = read_full(reader, &mut next_len_buf)
            .map_err(|e| format!("IO read error: {}", e))?;

        let is_last: u8 = if next_len_read == 0 {
            1
        } else if next_len_read == 4 {
            0
        } else {
            return Err("Corrupted file: Truncated next chunk header".to_string());
        };

        // Reconstruct expected 12-byte Nonce
        let mut nonce_bytes = [0u8; 12];
        nonce_bytes[..7].copy_from_slice(nonce_prefix);
        nonce_bytes[7..11].copy_from_slice(&chunk_counter.to_be_bytes());
        nonce_bytes[11] = is_last;

        let nonce = Nonce::from_slice(&nonce_bytes);

        // Decrypt chunk with AES-256-GCM (fails if key/nonce/ciphertext/tag is invalid or tampered)
        let plaintext_chunk = cipher
            .decrypt(nonce, encrypted_chunk.as_slice())
            .map_err(|e| format!("Decryption error at chunk {}: {}", chunk_counter, e))?;

        writer
            .write_all(&plaintext_chunk)
            .map_err(|e| format!("IO write error: {}", e))?;

        total_processed += plaintext_chunk.len() as u64;
        if let Some(ref mut cb) = progress_cb {
            cb(total_processed);
        }

        if is_last == 1 {
            break;
        }

        len_buf = next_len_buf;
        chunk_counter = chunk_counter.checked_add(1).ok_or("Chunk counter overflow")?;
    }

    writer.flush().map_err(|e| format!("IO flush error: {}", e))?;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn test_encrypt_stream_success() {
        let mut password = String::from("StreamTestPassword2026!");
        let plaintext = b"Hello, this is a test payload for streaming encryption!";
        let mut reader = Cursor::new(plaintext);
        let mut writer = Vec::new();

        let result = encrypt_stream(&mut password, &mut reader, &mut writer);
        assert!(result.is_ok(), "encrypt_stream should succeed");
        assert!(password.is_empty(), "Password should be zeroized");

        // Header (28 bytes) + Length (4 bytes) + Ciphertext + Tag
        assert!(writer.len() > 28 + 4);
        assert_eq!(&writer[..4], b"RMLK");
        assert_eq!(writer[4], 0x01);
    }

    #[test]
    fn test_encrypt_and_decrypt_stream_roundtrip() {
        let original_data = b"Streaming encryption and decryption roundtrip test payload! RamLock 2026.";

        let mut enc_pass = String::from("SecretPass123!");
        let mut reader = Cursor::new(original_data);
        let mut encrypted_stream = Vec::new();

        let enc_res = encrypt_stream(&mut enc_pass, &mut reader, &mut encrypted_stream);
        assert!(enc_res.is_ok());

        let mut dec_pass = String::from("SecretPass123!");
        let mut enc_reader = Cursor::new(encrypted_stream);
        let mut decrypted_stream = Vec::new();

        let dec_res = decrypt_stream(&mut dec_pass, &mut enc_reader, &mut decrypted_stream);
        assert!(dec_res.is_ok(), "decrypt_stream should succeed: {:?}", dec_res.err());
        assert!(dec_pass.is_empty(), "Password should be zeroized");
        assert_eq!(decrypted_stream, original_data);
    }

    #[test]
    fn test_decrypt_stream_wrong_password_fails() {
        let original_data = b"Confidential streaming payload.";

        let mut enc_pass = String::from("RightPassword");
        let mut reader = Cursor::new(original_data);
        let mut encrypted_stream = Vec::new();

        encrypt_stream(&mut enc_pass, &mut reader, &mut encrypted_stream).unwrap();

        let mut wrong_pass = String::from("WrongPassword");
        let mut enc_reader = Cursor::new(encrypted_stream);
        let mut decrypted_stream = Vec::new();

        let dec_res = decrypt_stream(&mut wrong_pass, &mut enc_reader, &mut decrypted_stream);
        assert!(dec_res.is_err(), "Decryption with wrong password must fail");
        assert!(wrong_pass.is_empty(), "Password should be zeroized on failure");
    }

    #[test]
    fn test_decrypt_stream_tampered_ciphertext_fails() {
        let original_data = b"Data to be tampered with in transit.";

        let mut enc_pass = String::from("TamperTestPass");
        let mut reader = Cursor::new(original_data);
        let mut encrypted_stream = Vec::new();

        encrypt_stream(&mut enc_pass, &mut reader, &mut encrypted_stream).unwrap();

        // Flip a bit in the encrypted payload area
        let last_idx = encrypted_stream.len() - 1;
        encrypted_stream[last_idx] ^= 0x01;

        let mut dec_pass = String::from("TamperTestPass");
        let mut enc_reader = Cursor::new(encrypted_stream);
        let mut decrypted_stream = Vec::new();

        let dec_res = decrypt_stream(&mut dec_pass, &mut enc_reader, &mut decrypted_stream);
        assert!(dec_res.is_err(), "Decryption of tampered ciphertext must fail tag validation");
    }

    #[test]
    fn test_decrypt_stream_invalid_header_magic_fails() {
        let mut invalid_header = vec![0x00u8; 50]; // Random bytes without b"RMLK"
        let mut dec_pass = String::from("AnyPass");
        let mut reader = Cursor::new(&mut invalid_header);
        let mut writer = Vec::new();

        let res = decrypt_stream(&mut dec_pass, &mut reader, &mut writer);
        assert!(res.is_err());
        assert!(res.unwrap_err().contains("RMLK"));
    }

    #[test]
    fn test_decrypt_stream_exceeding_max_chunk_len_fails() {
        let original_data = b"Short text payload";
        let mut enc_pass = String::from("MaxLenTestPass");
        let mut reader = Cursor::new(original_data);
        let mut encrypted_stream = Vec::new();

        encrypt_stream(&mut enc_pass, &mut reader, &mut encrypted_stream).unwrap();

        // Header is 28 bytes. The next 4 bytes are chunk length.
        // Overwrite chunk length with 0xFFFFFFFF (~4 GB)
        encrypted_stream[28..32].copy_from_slice(&0xFFFF_FFFFu32.to_be_bytes());

        let mut dec_pass = String::from("MaxLenTestPass");
        let mut enc_reader = Cursor::new(encrypted_stream);
        let mut decrypted_stream = Vec::new();

        let dec_res = decrypt_stream(&mut dec_pass, &mut enc_reader, &mut decrypted_stream);
        assert!(dec_res.is_err(), "Decryption must fail when chunk length exceeds MAX_CHUNK_LEN");
        assert!(dec_res.unwrap_err().contains("exceeds limit"));
    }

    #[test]
    fn test_encrypt_and_decrypt_large_multi_chunk_stream() {
        // Create 200 KB payload (spans 4 chunks of 64 KB)
        let large_payload = vec![0x37u8; 200 * 1024];

        let mut enc_pass = String::from("LargeMultiChunkPass!");
        let mut reader = Cursor::new(&large_payload);
        let mut encrypted_stream = Vec::new();

        let enc_res = encrypt_stream(&mut enc_pass, &mut reader, &mut encrypted_stream);
        assert!(enc_res.is_ok());

        let mut dec_pass = String::from("LargeMultiChunkPass!");
        let mut enc_reader = Cursor::new(encrypted_stream);
        let mut decrypted_stream = Vec::new();

        let dec_res = decrypt_stream(&mut dec_pass, &mut enc_reader, &mut decrypted_stream);
        assert!(dec_res.is_ok());
        assert_eq!(decrypted_stream, large_payload);
    }

    struct PartialReader<R> {
        inner: R,
        max_bytes_per_read: usize,
    }

    impl<R: Read> Read for PartialReader<R> {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            let limit = std::cmp::min(buf.len(), self.max_bytes_per_read);
            self.inner.read(&mut buf[..limit])
        }
    }

    #[test]
    fn test_encrypt_stream_handles_partial_reads() {
        let mut password = String::from("PartialReadPass123");
        let plaintext = vec![0xABu8; 200 * 1024];
        let mut reader = PartialReader {
            inner: Cursor::new(plaintext),
            max_bytes_per_read: 1024,
        };
        let mut writer = Vec::new();

        let result = encrypt_stream(&mut password, &mut reader, &mut writer);
        assert!(result.is_ok(), "encrypt_stream should handle partial reads seamlessly");
    }

    #[test]
    fn test_stream_progress_reporting() {
        let payload = vec![0x55u8; 600 * 1024]; // 600 KB (spans multiple chunks)
        let mut enc_pass = String::from("ProgressPass123");
        let mut reader = Cursor::new(&payload);
        let mut writer = Vec::new();

        let mut enc_progress = Vec::new();
        let enc_res = encrypt_stream_with_progress(
            &mut enc_pass,
            &mut reader,
            &mut writer,
            Some(|bytes| enc_progress.push(bytes)),
        );
        assert!(enc_res.is_ok());
        assert!(!enc_progress.is_empty());
        assert_eq!(*enc_progress.last().unwrap(), 600 * 1024);

        let mut dec_pass = String::from("ProgressPass123");
        let mut enc_reader = Cursor::new(writer);
        let mut dec_writer = Vec::new();

        let mut dec_progress = Vec::new();
        let dec_res = decrypt_stream_with_progress(
            &mut dec_pass,
            &mut enc_reader,
            &mut dec_writer,
            Some(|bytes| dec_progress.push(bytes)),
        );
        assert!(dec_res.is_ok());
        assert_eq!(*dec_progress.last().unwrap(), 600 * 1024);
        assert_eq!(dec_writer, payload);
    }
}
