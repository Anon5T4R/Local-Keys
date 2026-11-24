//! O teto de tamanho do cofre ao anexar no desktop (unidade 8) e os apelidos da fórmula do núcleo.
//!
//! O anexo viaja em base64 dentro do JSON e o cofre INTEIRO é regravado (e recifrado) a cada gravação: o custo de
//! anexar cresce com o **cofre**, não com o anexo. A medição (release) deu: cofre de 50 MB abre 212 ms / salva 290 ms;
//! 100 MB: 285 / 521 ms; 200 MB: 443 / 1.043 ms, com pico de memória de ~320 / ~480 / ~960 MiB — e o salvar é
//! síncrono na thread da UI. A partir de ~100 MB o salvar congela a janela por mais de meio segundo e o pico chega a
//! ~480 MiB. O limite por anexo (10 MiB, em `jobs.rs`) continua; este aviso é sobre o TOTAL.
//!
//! A conta do previsto (base64 + soma + comparação) vive em `localkeys_core::size`, a mesma que o Android usa pela
//! ponte; aqui ficam só o teto do desktop e os nomes que o app chama.

/// Tamanho previsto do `.tkeys` a partir do qual anexar pede confirmação: 100 MiB (ver a medição no doc do módulo).
pub const VAULT_WARN_BYTES: u64 = 100 * 1024 * 1024;

/// O tamanho previsto do cofre depois dos anexos: o arquivo aberto + o que cada anexo ocupa **em base64** (a fórmula
/// do núcleo). Os anexos pendentes do formulário e o recém-lido entram em bytes crus. Cofre novo/sem arquivo:
/// `vault_bytes` = 0.
pub fn predicted_vault_bytes(vault_bytes: u64, attachment_bytes: impl IntoIterator<Item = u64>) -> u64 {
    localkeys_core::size::predicted_vault_bytes(vault_bytes, attachment_bytes)
}

/// O aviso aparece? Exatamente no teto ainda não; um byte acima sim.
pub fn exceeds_warn_limit(predicted_bytes: u64) -> bool {
    localkeys_core::size::exceeds(predicted_bytes, VAULT_WARN_BYTES)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cofre_novo_so_conta_os_anexos() {
        assert_eq!(predicted_vault_bytes(0, []), 0);
        assert_eq!(predicted_vault_bytes(0, [3 * 1024 * 1024]), 4 * 1024 * 1024);
    }

    #[test]
    fn o_previsto_soma_o_cofre_e_os_anexos_crus() {
        // O cofre aberto + um pendente de 3 bytes e um arquivo de 3 bytes (cada um vira 4 em base64).
        assert_eq!(predicted_vault_bytes(500, [3, 3]), 500 + 4 + 4);
    }

    #[test]
    fn o_aviso_so_aparece_acima_do_teto() {
        assert!(!exceeds_warn_limit(predicted_vault_bytes(VAULT_WARN_BYTES, [])), "exatamente no teto ainda não avisa");
        assert!(!exceeds_warn_limit(predicted_vault_bytes(VAULT_WARN_BYTES - 4, [3])), "a soma exata no teto ainda não avisa");
        assert!(exceeds_warn_limit(predicted_vault_bytes(VAULT_WARN_BYTES - 3, [3])), "um byte acima avisa");
        assert!(exceeds_warn_limit(predicted_vault_bytes(0, [VAULT_WARN_BYTES + 1])), "um único anexo acima do teto avisa");
        assert!(exceeds_warn_limit(predicted_vault_bytes(VAULT_WARN_BYTES / 2, [VAULT_WARN_BYTES / 2 + 1])), "a soma dos dois avisa");
    }
}
