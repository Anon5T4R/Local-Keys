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

// ── fase C (pedido 022): bandeira, blocos e Luhn — espelho do CardFormat.kt ──

/// Bandeira detectada pelo começo do número. O `label` é o que aparece no campo (e o que o cofre
/// guarda em `card.brand`), igual ao Android.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Brand {
    Visa,
    Mastercard,
    Amex,
    Elo,
    Hipercard,
    Diners,
    Discover,
    Jcb,
}

impl Brand {
    pub fn label(self) -> &'static str {
        match self {
            Self::Visa => "Visa",
            Self::Mastercard => "Mastercard",
            Self::Amex => "Amex",
            Self::Elo => "Elo",
            Self::Hipercard => "Hipercard",
            Self::Diners => "Diners",
            Self::Discover => "Discover",
            Self::Jcb => "JCB",
        }
    }
}

/// Faixas de BIN da Elo — conferidas ANTES das bandeiras grandes (vários BINs Elo começam igual a
/// Visa/Discover/Diners). As 23 faixas do `CardFormat.kt` do Android.
const ELO_RANGES: [(i32, i32); 23] = [
    (401178, 401179),
    (431274, 431274),
    (438935, 438935),
    (451416, 451416),
    (457393, 457393),
    (457631, 457632),
    (504175, 504175),
    (506699, 506778),
    (509000, 509999),
    (627780, 627780),
    (636297, 636297),
    (636368, 636368),
    (650031, 650033),
    (650035, 650051),
    (650405, 650439),
    (650485, 650538),
    (650541, 650598),
    (650700, 650718),
    (650720, 650727),
    (650901, 650920),
    (651652, 651679),
    (655000, 655019),
    (655021, 655058),
];

/// Bandeira pelo começo do número; `None` = não dá para saber (ainda). Ordem e faixas do
/// `CardFormat.brandOf` do Android.
pub fn brand_of(number: &str) -> Option<Brand> {
    let d = digits(number);
    if d.is_empty() {
        return None;
    }
    let prefix = |n: usize| -> Option<i32> { d.get(..n).and_then(|s| s.parse().ok()) };
    let starts = |p: &str| d.starts_with(p);
    let p6 = prefix(6);
    if p6.is_some_and(|bin| ELO_RANGES.iter().any(|&(lo, hi)| bin >= lo && bin <= hi)) {
        return Some(Brand::Elo);
    }
    if p6 == Some(606_282) || starts("384100") || starts("384140") || starts("384160") {
        return Some(Brand::Hipercard);
    }
    let p2 = prefix(2);
    let p3 = prefix(3);
    let p4 = prefix(4);
    if p2 == Some(34) || p2 == Some(37) {
        return Some(Brand::Amex);
    }
    if p4.is_some_and(|p| (3528..=3589).contains(&p)) {
        return Some(Brand::Jcb);
    }
    if p3.is_some_and(|p| (300..=305).contains(&p)) {
        return Some(Brand::Diners);
    }
    if p2 == Some(36) || p2 == Some(38) || p2 == Some(39) {
        return Some(Brand::Diners);
    }
    if starts("4") {
        return Some(Brand::Visa);
    }
    if p2.is_some_and(|p| (51..=55).contains(&p)) {
        return Some(Brand::Mastercard);
    }
    if p4.is_some_and(|p| (2221..=2720).contains(&p)) {
        return Some(Brand::Mastercard);
    }
    if starts("6011") || starts("65") {
        return Some(Brand::Discover);
    }
    if p3.is_some_and(|p| (644..=649).contains(&p)) {
        return Some(Brand::Discover);
    }
    if p6.is_some_and(|bin| (622_126..=622_925).contains(&bin)) {
        return Some(Brand::Discover);
    }
    None
}

/// Tamanhos dos grupos na TELA: Amex 4-6-5; o resto de 4 em 4.
fn groups(number: &str) -> &'static [usize] {
    if brand_of(number) == Some(Brand::Amex) { &[4, 6, 5] } else { &[4, 4, 4, 4, 4] }
}

/// O número em blocos de 4 (Amex 4-6-5), só para EXIBIÇÃO — o cofre continua guardando só os dígitos
/// (`number()` na gravação). "4111111111111111" → "4111 1111 1111 1111".
pub fn group(number: &str) -> String {
    let d = digits(number);
    let mut out = String::new();
    let mut at = 0;
    for size in groups(&d) {
        if at >= d.len() {
            break;
        }
        if !out.is_empty() {
            out.push(' ');
        }
        let end = (at + size).min(d.len());
        out.push_str(&d[at..end]);
        at = end;
    }
    if at < d.len() {
        out.push(' ');
        out.push_str(&d[at..]);
    }
    out
}

/// Luhn: só diz se fecha (≥12 dígitos para ter cara de número).
pub fn luhn_ok(number: &str) -> bool {
    let d = digits(number);
    if d.len() < 12 {
        return false;
    }
    let sum: u32 = d
        .bytes()
        .rev()
        .enumerate()
        .map(|(i, b)| {
            let mut n = u32::from(b - b'0');
            if i % 2 == 1 {
                n *= 2;
                if n > 9 {
                    n -= 9;
                }
            }
            n
        })
        .sum();
    sum.is_multiple_of(10)
}

/// Avisar do Luhn? Só com o número completo (13+ dígitos) que não fecha — nunca bloqueia.
pub fn luhn_warning(number: &str) -> bool {
    digits(number).chars().count() >= 13 && !luhn_ok(number)
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

    /// As faixas de bandeira do `FormsTest.kt` do Android (fase C).
    #[test]
    fn bandeira_pelo_comeco_do_numero() {
        let casos = [
            ("4111111111111111", Brand::Visa),
            ("5555555555554444", Brand::Mastercard),
            ("2221000000000009", Brand::Mastercard),
            ("378282246310005", Brand::Amex),
            ("6362970000457013", Brand::Elo),
            ("5067000000000000", Brand::Elo),
            ("4389350000000000", Brand::Elo),
            ("6062825624254001", Brand::Hipercard),
            ("30569309025904", Brand::Diners),
            ("6011111111111117", Brand::Discover),
            ("3530111333300000", Brand::Jcb),
        ];
        for (numero, bandeira) in casos {
            assert_eq!(brand_of(numero), Some(bandeira), "{numero}");
        }
        assert_eq!(brand_of(""), None);
        assert_eq!(brand_of("9999"), None);
        // com sujeira: o detector vê só os dígitos
        assert_eq!(brand_of("4111 1111-1111 1111"), Some(Brand::Visa));
    }

    #[test]
    fn blocos_de_quatro_e_amex_4_6_5() {
        assert_eq!(group("4111111111111111"), "4111 1111 1111 1111");
        assert_eq!(group("411111"), "4111 11");
        assert_eq!(group("378282246310005"), "3782 822463 10005");
        assert_eq!(group("4111111111111111"), "4111 1111 1111 1111");
        assert_eq!(digits("4111 1111-1111 1111"), "4111111111111111");
        // excedente depois do último grupo inteiro
        assert_eq!(group("1234567890123456789"), "1234 5678 9012 3456 789");
    }

    #[test]
    fn luhn_avisa_sozinho_em_numero_completo_errado() {
        assert!(luhn_ok("4111 1111 1111 1111"));
        assert!(!luhn_warning("4111111111111111"));
        assert!(luhn_warning("4111111111111112"));
        assert!(!luhn_warning("411111111111"), "incompleto: sem aviso ainda");
    }
}
