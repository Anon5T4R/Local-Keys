//! Tamanho do cofre ao anexar (unidade 8): o aviso NÃO bloqueante antes de ler o anexo.
//!
//! O anexo viaja em base64 dentro do JSON e o cofre INTEIRO é regravado (e recifrado) a cada gravação: o custo de
//! anexar cresce com o **cofre**, não com o anexo. A medição (release) deu: cofre de 50 MB abre 212 ms / salva 290 ms;
//! 100 MB: 285 / 521 ms; 200 MB: 443 / 1.043 ms, pico ~960 MiB — e o salvar é síncrono na thread da UI. A partir de
//! ~100 MB o salvar congela a janela por mais de meio segundo e o pico de memória passa de ~500 MiB. O limite por
//! anexo (10 MiB, em `jobs.rs`) continua; este aviso é sobre o TOTAL.

/// Tamanho previsto do `.tkeys` a partir do qual anexar pede confirmação: 100 MiB (ver a medição no doc do módulo).
pub const VAULT_WARN_BYTES: u64 = 100 * 1024 * 1024;

/// O tamanho que `bytes` ocupa em base64 (4 bytes por 3 de entrada, arredondado para cima) — é o que o anexo
/// acrescenta ao JSON do cofre.
pub fn base64_bytes(bytes: u64) -> u64 {
    bytes.div_ceil(3) * 4
}

/// O tamanho previsto do cofre depois dos anexos: o arquivo aberto + o que cada anexo ocupa **já em base64**. Os
/// anexos pendentes do formulário trazem o texto pronto (`data_b64`); os arquivos escolhidos passam por
/// [`base64_bytes`]. Cofre novo/sem arquivo: `vault_bytes` = 0.
pub fn predicted_vault_bytes(vault_bytes: u64, extra_b64_bytes: impl IntoIterator<Item = u64>) -> u64 {
    extra_b64_bytes.into_iter().fold(vault_bytes, u64::saturating_add)
}

/// O aviso aparece? Exatamente no teto ainda não; um byte acima sim.
pub fn exceeds_warn_limit(predicted_bytes: u64) -> bool {
    predicted_bytes > VAULT_WARN_BYTES
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base64_cresce_de_tres_em_tres_bytes() {
        assert_eq!(base64_bytes(0), 0);
        assert_eq!(base64_bytes(1), 4);
        assert_eq!(base64_bytes(3), 4);
        assert_eq!(base64_bytes(4), 8);
        assert_eq!(base64_bytes(10 * 1024 * 1024), 13_981_016);
    }

    #[test]
    fn cofre_novo_so_conta_os_anexos() {
        assert_eq!(predicted_vault_bytes(0, []), 0);
        assert_eq!(predicted_vault_bytes(0, [base64_bytes(3 * 1024 * 1024)]), 4 * 1024 * 1024);
    }

    #[test]
    fn o_previsto_soma_o_cofre_e_os_pendentes() {
        // O cofre aberto + um pendente já em base64 + um arquivo escolhido (cru → base64).
        let pending_b64 = 1_000_u64;
        let new_file = 3_u64;
        assert_eq!(predicted_vault_bytes(500, [pending_b64, base64_bytes(new_file)]), 500 + 1_000 + 4);
    }

    #[test]
    fn o_aviso_so_aparece_acima_do_teto() {
        assert!(!exceeds_warn_limit(VAULT_WARN_BYTES), "exatamente no teto ainda não avisa");
        assert!(!exceeds_warn_limit(predicted_vault_bytes(VAULT_WARN_BYTES - 4, [4])), "a soma exata no teto ainda não avisa");
        assert!(exceeds_warn_limit(predicted_vault_bytes(VAULT_WARN_BYTES - 4, [5])), "um byte acima avisa");
        assert!(exceeds_warn_limit(predicted_vault_bytes(0, [VAULT_WARN_BYTES + 1])), "um único anexo acima do teto avisa");
        assert!(exceeds_warn_limit(predicted_vault_bytes(VAULT_WARN_BYTES / 2, [VAULT_WARN_BYTES / 2 + 1])), "a soma dos dois avisa");
    }
}
