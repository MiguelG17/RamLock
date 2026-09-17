use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;

// #[no_mangle] evita que el compilador de Rust cambie el nombre de la función,
// permitiendo que la JVM de Android la encuentre exactamente como la definimos.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_tuidev_ramlock_SecurityCore_helloFromRust<'local>(
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