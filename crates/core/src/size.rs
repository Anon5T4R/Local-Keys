//! A fórmula do tamanho previsto do cofre ao anexar (unidade 8), a MESMA para o desktop e o Android.
//!
//! O anexo viaja em base64 dentro do JSON do cofre, então cada byte do arquivo ocupa 4/3 de byte no cofre. O teto de
//! aviso é de cada plataforma (capacidade de memória, ver `size_limit.rs` no desktop e `VaultSizeLimit.kt` no
//! Android) — o que mora aqui é só a conta, para o Kotlin não reescrevê-la.

/// O tamanho que `bytes` ocupa em base64 (4 bytes por 3, arredondado para cima).
pub fn base64_len(bytes: u64) -> u64 {
    bytes.div_ceil(3).saturating_mul(4)
}

/// Tamanho previsto do `.tkeys` depois dos anexos: o arquivo aberto + o base64 de cada anexo. Cofre novo/sem arquivo:
/// `vault_bytes = 0`.
pub fn predicted_vault_bytes(vault_bytes: u64, attachment_bytes: impl IntoIterator<Item = u64>) -> u64 {
    attachment_bytes.into_iter().map(base64_len).fold(vault_bytes, u64::saturating_add)
}

/// O previsto passou do teto da plataforma? Exatamente no teto ainda não; um byte acima sim.
pub fn exceeds(predicted_bytes: u64, limit_bytes: u64) -> bool {
    predicted_bytes > limit_bytes
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base64_cresce_de_tres_em_tres_bytes() {
        assert_eq!(base64_len(0), 0);
        assert_eq!(base64_len(1), 4);
        assert_eq!(base64_len(3), 4);
        assert_eq!(base64_len(4), 8);
        assert_eq!(base64_len(10 * 1024 * 1024), 13_981_016);
        assert_eq!(base64_len(u64::MAX), u64::MAX, "satura em vez de estourar");
    }

    #[test]
    fn o_previsto_soma_o_cofre_e_os_anexos_em_base64() {
        assert_eq!(predicted_vault_bytes(0, []), 0);
        assert_eq!(predicted_vault_bytes(500, [3, 3]), 500 + 2 * 4);
        assert_eq!(predicted_vault_bytes(1_000, [3 * 1024 * 1024]), 1_000 + 4 * 1024 * 1024);
        assert_eq!(predicted_vault_bytes(u64::MAX, [1]), u64::MAX, "satura em vez de estourar");
    }

    #[test]
    fn o_excesso_e_so_acima_do_teto() {
        assert!(!exceeds(100, 100));
        assert!(exceeds(101, 100));
    }
}
