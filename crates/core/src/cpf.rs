//! Ajuda para o campo personalizado "CPF" (pedido 022, fase C) — espelho do `Cpf.kt` do Android, que é
//! a referência: formata enquanto digita e avisa (sem bloquear) se o dígito verificador não fecha. O
//! valor continua texto livre: o que foi colado em outro formato fica como está.

/// O campo chama "CPF"? (o atalho cria com esse nome; quem digitar o nome à mão também ganha a ajuda)
pub fn is_cpf_field(name: &str) -> bool {
    name.trim().eq_ignore_ascii_case("CPF")
}

/// Formata se o texto for só dígitos (e a pontuação do próprio CPF): "12345678909" → "123.456.789-09",
/// parcial também ("1234" → "123.4"). Outro formato (letras, barras…) volta igual.
pub fn format(raw: &str) -> String {
    if raw.chars().any(|c| !c.is_ascii_digit() && c != '.' && c != '-' && c != ' ') {
        return raw.to_string();
    }
    let d: String = raw.chars().filter(char::is_ascii_digit).collect();
    if d.chars().count() > 11 {
        return raw.to_string();
    }
    let mut out = String::new();
    for (i, c) in d.chars().enumerate() {
        match i {
            3 | 6 => out.push('.'),
            9 => out.push('-'),
            _ => {}
        }
        out.push(c);
    }
    out
}

/// CPF completo (11 dígitos) com os dois verificadores certos. Sequências repetidas não valem.
pub fn is_valid(raw: &str) -> bool {
    let d: Vec<u32> = raw.chars().filter(char::is_ascii_digit).map(|c| u32::from(c as u8 - b'0')).collect();
    if d.len() != 11 || d.iter().all(|&n| n == d[0]) {
        return false;
    }
    let check = |len: usize| -> u32 {
        let sum: u32 = (0..len).map(|i| d[i] * (len as u32 + 1 - i as u32)).sum();
        let r = (sum * 10) % 11;
        if r == 10 { 0 } else { r }
    };
    check(9) == d[9] && check(10) == d[10]
}

/// Avisar? Só com os 11 dígitos digitados e o verificador errado.
pub fn warning(raw: &str) -> bool {
    raw.chars().filter(char::is_ascii_digit).count() == 11 && !is_valid(raw)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Os mesmos casos do `FormsTest.kt` do Android.
    #[test]
    fn cpf_formata_digitos_e_mantem_outros_formatos() {
        assert_eq!(format("52998224725"), "529.982.247-25");
        assert_eq!(format("5299822"), "529.982.2");
        assert_eq!(format("529.982.247-25"), "529.982.247-25");
        assert_eq!(format("529982247/25"), "529982247/25", "barras ficam como vieram");
        assert_eq!(format("CPF 123"), "CPF 123", "letras ficam como vieram");
    }

    #[test]
    fn cpf_digito_verificador() {
        assert!(is_valid("529.982.247-25"));
        assert!(!is_valid("529.982.247-26"));
        assert!(!is_valid("111.111.111-11"), "sequência repetida não vale");
        assert!(warning("52998224726"));
        assert!(!warning("5299822472"), "incompleto: sem aviso");
        assert!(!warning("52998224725"));
        assert!(is_cpf_field(" cpf "));
        assert!(!is_cpf_field("CPF do pai"));
    }
}
