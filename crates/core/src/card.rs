//! Regras de armazenamento do cartão (pedido 022, fase A) — espelho do `CardFormat.kt` do Android, que é a
//! referência: o cofre guarda o número **só com dígitos** (os blocos de 4 são só exibição, fase C), a validade
//! como `MM/AA` (o `MM/AAAA` do Bitwarden continua aceito) e o código de segurança só com dígitos.
//!
//! Quem grava é o `vault.rs` (`add_card`/`edit_card`): a normalização mora aqui para que a regra valha
//! qualquer que seja o chamador — a UI aplica as mesmas funções ao vivo só para o campo ecoar o que vai
//! ser guardado.

/// Máximo de dígitos de um número de cartão (o padrão dos emissores atuais).
pub const MAX_DIGITS: usize = 19;

/// Só os dígitos ASCII ("4111 1111-1111 1111" → "4111111111111111").
pub fn digits(raw: &str) -> String {
    raw.chars().filter(char::is_ascii_digit).collect()
}

/// O número que vai para o cofre: só dígitos, no máximo [`MAX_DIGITS`].
pub fn number(raw: &str) -> String {
    digits(raw).chars().take(MAX_DIGITS).collect()
}

/// Validade enquanto digita: "1" → "1", "12" → "12", "123" → "12/3", "01/2027" → "01/2027".
pub fn expiry(raw: &str) -> String {
    let d: String = digits(raw).chars().take(6).collect();
    if d.chars().count() <= 2 { d } else { format!("{}/{}", &d[..2], &d[2..]) }
}

/// O código de segurança: só dígitos, até 4.
pub fn cvv(raw: &str) -> String {
    digits(raw).chars().take(4).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Os mesmos casos do `FormsTest.kt` do Android (fase A: máscaras; bandeira e Luhn ficam na fase C).
    #[test]
    fn numero_guarda_so_digitos() {
        assert_eq!(number("4111 1111-1111 1111"), "4111111111111111");
        assert_eq!(number(""), "");
        // O teto de 19 dígitos corta o excesso.
        assert_eq!(number("123456789012345678901234"), "1234567890123456789");
    }

    #[test]
    fn validade_insere_a_barra() {
        assert_eq!(expiry("1"), "1");
        assert_eq!(expiry("12"), "12");
        assert_eq!(expiry("123"), "12/3");
        assert_eq!(expiry("12/30"), "12/30");
        // MM/AAAA (o que vem do Bitwarden) continua aceito.
        assert_eq!(expiry("01/2027"), "01/2027");
        assert_eq!(expiry("012027"), "01/2027");
        // Colado com sujeira: só os dígitos contam.
        assert_eq!(expiry("12-27"), "12/27");
    }

    #[test]
    fn codigo_so_digitos_ate_quatro() {
        assert_eq!(cvv("1a2b3"), "123");
        assert_eq!(cvv("123456"), "1234");
        assert_eq!(cvv(""), "");
    }
}
