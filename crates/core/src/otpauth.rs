//! Lê o que vem de um QR de 2FA ou da área de transferência — espelho do `OtpAuth.kt` do Android, que é
//! a referência: um `otpauth://totp/Emissor:conta?secret=…` ou só a chave base32. O que vai para o cofre
//! é **só a chave base32 limpa** ([`Parsed::secret`]) — é o que o campo `login.totp` guarda e o `totp.rs`
//! usa para gerar o código (a URI com parâmetros não é entendida na geração).
//!
//! Casos de borda idênticos aos do `FormsTest.kt` do Android (emissor pelo rótulo ou pelo parâmetro,
//! decodificação de `%XX`, chave crua com espaços/hífens/minúsculas/`=`, HOTP, `secret` ausente, chave
//! que não é base32 e parâmetros fora do padrão).

use totp_rs::Secret;

const PERIOD: u64 = 30;
const DIGITS: u32 = 6;

/// O que um QR/link de 2FA entrega.
#[derive(Debug)]
pub struct Parsed {
    /// Chave base32 limpa (maiúsculas, sem espaços, hífens nem `=`).
    pub secret: String,
    /// Emissor (`issuer=` ou o pedaço antes do `:` no rótulo), se veio.
    pub issuer: Option<String>,
    /// Conta (o pedaço depois do `:` no rótulo), se veio.
    pub account: Option<String>,
    /// O QR pede algo que o Local Keys não gera (só 6 dígitos, 30 s e SHA-1): a chave é guardada mesmo
    /// assim, mas os códigos podem não bater. `None` = padrão.
    pub unsupported: Option<Unsupported>,
}

/// O que o link pede fora do padrão (6 dígitos, 30 s, SHA-1).
#[derive(Debug, PartialEq)]
pub struct Unsupported {
    pub digits: Option<u32>,
    pub period: Option<u64>,
    pub algorithm: Option<String>,
}

/// Por que o texto não serviu.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Reason {
    /// Vazio.
    Empty,
    /// `otpauth://hotp` (por contador) ou outro tipo — só TOTP é suportado.
    NotTotp,
    /// A URI não traz `secret`.
    NoSecret,
    /// O texto (ou o `secret`) não decodifica como base32.
    BadKey,
}

/// [`parse`] deu certo ou não.
#[derive(Debug)]
pub enum ParseResult {
    Ok(Parsed),
    Invalid(Reason),
}

/// Lê um `otpauth://totp/…` ou uma chave base32 crua.
pub fn parse(raw: &str) -> ParseResult {
    let text = raw.trim();
    if text.is_empty() {
        return ParseResult::Invalid(Reason::Empty);
    }
    let Some(after_scheme) = strip_scheme(text) else {
        // chave crua: limpa se decodifica como base32
        return match bare_key(text) {
            Some(secret) => ParseResult::Ok(Parsed { secret, issuer: None, account: None, unsupported: None }),
            None => ParseResult::Invalid(Reason::BadKey),
        };
    };
    let tipo = after_scheme.split('/').next().unwrap_or_default().to_lowercase();
    if tipo != "totp" {
        return ParseResult::Invalid(Reason::NotTotp);
    }
    // O que vem depois do primeiro '/' (sem a query) é o rótulo "Emissor:conta" (percent-encoded).
    let rest = after_scheme.split_once('/').map(|(_, r)| r).unwrap_or_default();
    let label_raw = rest.split('?').next().unwrap_or_default();
    let label = decode(label_raw);

    let query = rest.split_once('?').map(|(_, q)| q).unwrap_or_default();
    let mut params: Vec<(String, String)> = Vec::new();
    for pair in query.split('&').filter(|p| !p.is_empty()) {
        let (key, value) = pair.split_once('=').map_or((pair, ""), |(k, v)| (k, v));
        params.push((decode(key).to_lowercase(), decode(value)));
    }
    let param = |name: &str| params.iter().find(|(k, _)| k == name).map(|(_, v)| v.as_str());

    let Some(secret_raw) = param("secret").filter(|s| !s.trim().is_empty()) else {
        return ParseResult::Invalid(Reason::NoSecret);
    };
    let Some(secret) = bare_key(secret_raw) else {
        return ParseResult::Invalid(Reason::BadKey);
    };

    let has_colon = label.contains(':');
    let label_issuer = if has_colon { label.split(':').next().unwrap_or_default().trim() } else { "" };
    let label_issuer = (!label_issuer.is_empty()).then(|| label_issuer.to_string());
    let account_raw = if has_colon { label.split_once(':').map(|(_, a)| a).unwrap_or_default() } else { label.as_str() };
    let account = account_raw.trim();
    let account = (!account.is_empty()).then(|| account.to_string());
    let issuer_param = param("issuer").map(str::trim).filter(|s| !s.is_empty());
    let issuer = issuer_param.map(str::to_string).or(label_issuer);

    // Fora do padrão? `parse` que falha não sinaliza (o `toIntOrNull` do Android vira null).
    let digits = param("digits").and_then(|d| d.parse::<u32>().ok()).filter(|d| *d != DIGITS);
    let period = param("period").and_then(|p| p.parse::<u64>().ok()).filter(|p| *p != PERIOD);
    let algorithm = param("algorithm").map(|a| a.trim().to_uppercase()).filter(|a| !a.is_empty() && a != "SHA1");
    let unsupported =
        (digits.is_some() || period.is_some() || algorithm.is_some()).then(|| Unsupported { digits, period, algorithm: algorithm.clone() });

    ParseResult::Ok(Parsed { secret, issuer, account, unsupported })
}

/// A chave limpa se for base32 que decodifica, senão `None`. Aceita espaços, hífens, minúsculas e `=`.
pub fn bare_key(raw: &str) -> Option<String> {
    // A mesma limpeza do `totp.rs` (o `Totp.sanitize` do Android).
    let cleaned: String = raw.trim().replace([' ', '-'], "").to_uppercase();
    let cleaned = cleaned.trim_end_matches('=');
    if cleaned.is_empty() || !cleaned.chars().all(|c| c.is_ascii_uppercase() || ('2'..='7').contains(&c)) {
        return None;
    }
    // Precisa decodificar de verdade (e não ser vazia): é o que vai gerar o código.
    let bytes = Secret::Encoded(cleaned.to_string()).to_bytes().ok()?;
    (!bytes.is_empty()).then(|| cleaned.to_string())
}

/// `otpauth://` sem diferenciar maiúsculas; devolve o que vem depois do esquema.
fn strip_scheme(text: &str) -> Option<&str> {
    let (scheme, rest) = text.split_at(10.min(text.len()));
    scheme.eq_ignore_ascii_case("otpauth://").then_some(rest)
}

/// Percent-decoding `%XX` (UTF-8). Sequência inválida ou UTF-8 quebrado devolve o texto ORIGINAL — o
/// `URLDecoder` do Android lança e o `getOrDefault` devolve o que veio. O `+` fica `+`: o Android troca
/// por `%2B` antes de decodificar (rótulos não são `application/x-www-form-urlencoded`).
fn decode(s: &str) -> String {
    if !s.contains('%') {
        return s.to_string();
    }
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let hex = bytes.get(i + 1..i + 3).and_then(|h| {
                let hi = (h[0] as char).to_digit(16);
                let lo = (h[1] as char).to_digit(16);
                hi.zip(lo).map(|(hi, lo)| (hi * 16 + lo) as u8)
            });
            match hex {
                Some(byte) => {
                    out.push(byte);
                    i += 3;
                }
                // `%` solto ou hex incompleto: desiste e devolve o original.
                None => return s.to_string(),
            }
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).unwrap_or_else(|_| s.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `(OtpAuth.parse(raw) as Ok).value` — o helper `ok` do `FormsTest.kt`.
    fn ok(raw: &str) -> Parsed {
        match parse(raw) {
            ParseResult::Ok(p) => p,
            ParseResult::Invalid(r) => panic!("esperava Ok, veio {r:?} de {raw:?}"),
        }
    }

    /// Os mesmos casos do `FormsTest.kt` do Android.
    #[test]
    fn otpauth_com_emissor_e_conta() {
        let p = ok("otpauth://totp/Binance:joao%40example.com?secret=jbswy3dpehpk3pxp&issuer=Binance");
        assert_eq!(p.secret, "JBSWY3DPEHPK3PXP");
        assert_eq!(p.issuer.as_deref(), Some("Binance"));
        assert_eq!(p.account.as_deref(), Some("joao@example.com"), "%40 decodifica em @");
        assert!(p.unsupported.is_none());
    }

    #[test]
    fn emissor_do_rotulo_quando_falta_o_parametro() {
        let p = ok("otpauth://totp/GitHub:joao?secret=JBSWY3DPEHPK3PXP");
        assert_eq!(p.issuer.as_deref(), Some("GitHub"));
        assert_eq!(p.account.as_deref(), Some("joao"));
        let sem_emissor = ok("otpauth://totp/joao?secret=JBSWY3DPEHPK3PXP");
        assert!(sem_emissor.issuer.is_none());
        assert_eq!(sem_emissor.account.as_deref(), Some("joao"));
    }

    #[test]
    fn chave_crua_com_espacos_minusculas_e_padding() {
        assert_eq!(ok("jbsw y3dp ehpk 3pxp").secret, "JBSWY3DPEHPK3PXP");
        assert_eq!(ok("JBSWY3DP-EHPK3PXP==").secret, "JBSWY3DPEHPK3PXP");
        // O que vai para o cofre gera código (a chave limpa serve para o `totp.rs`).
        let p = ok("otpauth://totp/X:y?secret=jbsw%20y3dp%20ehpk%203pxp");
        assert!(crate::totp::now(&p.secret).is_ok());
    }

    #[test]
    fn parametros_fora_do_padrao_sao_sinalizados() {
        let p = ok("otpauth://totp/X:y?secret=JBSWY3DPEHPK3PXP&digits=8&period=60&algorithm=SHA256");
        let u = p.unsupported.expect("sinaliza");
        assert_eq!(u.digits, Some(8));
        assert_eq!(u.period, Some(60));
        assert_eq!(u.algorithm.as_deref(), Some("SHA256"));
        // Padrão explícito: sem aviso.
        assert!(ok("otpauth://totp/X:y?secret=JBSWY3DPEHPK3PXP&digits=6&period=30&algorithm=SHA1").unsupported.is_none());
        // Valor que não parseia: sem sinalização (o toIntOrNull do Android vira null).
        assert!(ok("otpauth://totp/X:y?secret=JBSWY3DPEHPK3PXP&digits=8x").unsupported.is_none());
    }

    #[test]
    fn entradas_invalidas_dizem_o_porque() {
        assert!(matches!(parse("  "), ParseResult::Invalid(Reason::Empty)));
        assert!(matches!(parse("otpauth://hotp/X?secret=JBSWY3DP&counter=1"), ParseResult::Invalid(Reason::NotTotp)));
        assert!(matches!(parse("otpauth://totp/X?issuer=X"), ParseResult::Invalid(Reason::NoSecret)));
        assert!(matches!(parse("senha1!"), ParseResult::Invalid(Reason::BadKey)));
        assert!(matches!(parse("otpauth://totp/X?secret=0189"), ParseResult::Invalid(Reason::BadKey)));
        // secret em branco conta como ausente
        assert!(matches!(parse("otpauth://totp/X?secret=%20%20"), ParseResult::Invalid(Reason::NoSecret)));
    }

    #[test]
    fn issuer_vazio_no_parametro_cai_no_do_rotulo() {
        // issuer= vazio não vira "" (o trim/ifEmpty do Android): vale o do rótulo.
        let p = ok("otpauth://totp/GitHub:joao?secret=JBSWY3DPEHPK3PXP&issuer=%20");
        assert_eq!(p.issuer.as_deref(), Some("GitHub"));
    }

    #[test]
    fn rotulo_preserva_o_mais_e_decodifica_percent() {
        // '+' não é espaço (o Android escapa para %2B antes de decodificar).
        let p = ok("otpauth://totp/A+B%20C?secret=JBSWY3DPEHPK3PXP");
        assert_eq!(p.account.as_deref(), Some("A+B C"));
        // sequência % quebrada: o rótulo fica como veio
        let p = ok("otpauth://totp/100%z?secret=JBSWY3DPEHPK3PXP");
        assert_eq!(p.account.as_deref(), Some("100%z"));
    }

    #[test]
    fn base32_de_um_caractere_nao_gera_chave() {
        // "A" está no alfabeto mas decodifica em nada — o Android exige byte não vazio.
        assert!(bare_key("A").is_none());
        assert!(bare_key("======").is_none());
    }
}
