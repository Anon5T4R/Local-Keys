//! O gerador dos bindings Kotlin: `cargo run --bin uniffi-bindgen -- generate --library <lib> --language kotlin ...`
//! (chamado por `android/scripts/build-native.sh`).
fn main() {
    uniffi::uniffi_bindgen_main()
}
