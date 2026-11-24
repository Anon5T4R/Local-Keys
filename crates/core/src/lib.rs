//! Núcleo do Local Keys, sem UI: o formato `.tkeys` (Argon2id + XChaCha20-Poly1305), o cofre em JSON
//! bruto (escrita conservadora: o que o app não conhece atravessa intacto), os importadores (Bitwarden,
//! KeePass, CSV), a exportação, o TOTP e o gerador de senhas.
//!
//! O app desktop usa esta crate direto; o Android vai usá-la por UniFFI, para o formato e as regras serem o
//! mesmo código nos dois lados.

pub mod batch;
pub mod bwdecrypt;
pub mod card;
pub mod change_kind;
pub mod cpf;
pub mod crypto;
pub mod export;
pub mod filters;
pub mod generator;
pub mod import;
pub mod kdbx;
pub mod merge;
pub mod merge_other;
pub mod otpauth;
pub mod totp;
pub mod vault;
pub mod vault_export;
