//! Idiomas da interface, sobre o `craft-i18n` (vendorizado em `libs/craft-libs/crates/i18n`), no mesmo desenho
//! do Local Mind.
//!
//! O texto no código fica em inglês e é a chave de busca; os catálogos em `i18n/locales/<code>/localkeys.tsv`
//! dão o texto mostrado. Sem tradução, sai o inglês: uma tradução que falta nunca quebra build, teste nem
//! release (o teste `every_text_is_translated` aponta o que falta). O `build.rs` embute os catálogos
//! (`CRAFT_I18N_DIR` troca a pasta; as releases ligam `CRAFT_I18N_REQUIRED=1`).
//!
//! Idioma novo: o `localkeys.tsv` em `i18n/locales/<code>/` e uma linha em [`LANGUAGES`].

use std::cell::Cell;
use std::sync::LazyLock;

use craft_i18n::I18n;

include!(concat!(env!("OUT_DIR"), "/craft_i18n.rs"));

/// O valor da preferência de idioma que segue o idioma do sistema.
pub const AUTO: &str = "auto";

/// Os idiomas oferecidos (código BCP 47 em minúsculas, que é também o valor salvo). Inglês primeiro: é a fonte e
/// o recurso final.
pub static LANGUAGES: [&str; 3] = ["en", "pt-br", "es"];

/// Os catálogos embutidos, com exatamente os idiomas de [`LANGUAGES`].
static I18N: LazyLock<I18n> = LazyLock::new(|| {
    let (i18n, problems) = I18n::new(CRAFT_I18N, &LANGUAGES);
    for problem in problems {
        eprintln!("catálogos da interface: {problem}");
    }
    i18n
});

thread_local! {
    /// O idioma do quadro atual. A interface roda na thread principal, então cada busca dispensa argumento.
    static CURRENT: Cell<craft_i18n::Lang> = const { Cell::new(craft_i18n::Lang::EN) };
}

/// O idioma que a preferência pede: `auto` (o do sistema; inglês se não houver catálogo) ou um código de
/// [`LANGUAGES`]. Valor desconhecido cai no inglês.
pub fn resolve(setting: &str) -> craft_i18n::Lang {
    if setting == AUTO {
        return I18N.system_lang();
    }
    I18N.match_tag(setting).or_else(|| I18N.from_code(setting)).unwrap_or(craft_i18n::Lang::EN)
}

/// Fixa o idioma dos próximos quadros (no início e a cada troca da preferência).
pub fn set_language(setting: &str) {
    let lang = resolve(setting);
    CURRENT.with(|current| current.set(lang));
}

fn current() -> craft_i18n::Lang {
    CURRENT.with(Cell::get)
}

/// `source` (inglês) no idioma atual; sem tradução, o inglês.
pub fn tr(source: &'static str) -> &'static str {
    I18N.t(current(), source)
}

/// `tr_fmt` com os `{nome}` preenchidos (`tr_fmt("version {version}", &[("version", "1.0")])`).
pub fn tr_fmt(source: &'static str, args: &[(&str, &str)]) -> String {
    craft_i18n::fmt(tr(source), args)
}

/// Contagem no plural: escolhe a forma do idioma atual (linhas `@plural` do TSV) e preenche `{n}`
/// (`tr_n(2, "{n} item", "{n} items")` → "2 itens" em pt-br).
pub fn tr_n(n: u64, one: &'static str, other: &'static str) -> String {
    I18N.trn(current(), n, one, other)
}

/// [`tr`] com contexto (a primeira coluna do TSV) — o mesmo texto com tradução diferente por lugar
/// (`trc("filter", "All")` é "Todos" no popup de tipos, enquanto `tr("All")` é "Todas" nas pastas).
pub fn trc(context: &'static str, source: &'static str) -> &'static str {
    I18N.tc(current(), context, source)
}

/// O nome de um idioma no próprio idioma (a linha `@id language.name` do catálogo; senão, o código).
pub fn name_of(code: &str) -> &'static str {
    I18N.from_code(code).map(|lang| I18N.name(lang)).unwrap_or("English")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_catalog_parses() {
        let problems = I18N.check_all();
        assert!(problems.is_empty(), "{problems:#?}");
    }

    #[test]
    fn english_is_the_fallback() {
        set_language("pt-br");
        assert_eq!(tr("Vault"), "Cofre");
        assert_eq!(tr("a string no catalog has"), "a string no catalog has");
        set_language("es");
        assert_eq!(tr("Vault"), "Bóveda");
        set_language("en");
        assert_eq!(tr("Vault"), "Vault");
        assert_eq!(resolve("tlh"), craft_i18n::Lang::EN);
    }

    #[test]
    fn every_language_has_its_own_name() {
        assert_eq!(name_of("pt-br"), "Português (Brasil)");
        assert_eq!(name_of("es"), "Español");
        assert_eq!(name_of("en"), "English");
    }

    /// O plural escolhe a forma do idioma e preenche `{n}` — as linhas `@plural` do TSV.
    #[test]
    fn plural_escolhe_a_forma_do_idioma() {
        set_language("en");
        assert_eq!(tr_n(1, "{n} item added from the other copy", "{n} items added from the other copy"), "1 item added from the other copy");
        assert_eq!(tr_n(2, "{n} item added from the other copy", "{n} items added from the other copy"), "2 items added from the other copy");
        set_language("pt-br");
        assert_eq!(tr_n(1, "{n} item added from the other copy", "{n} items added from the other copy"), "1 item adicionado da outra cópia");
        assert_eq!(tr_n(2, "{n} item added from the other copy", "{n} items added from the other copy"), "2 itens adicionados da outra cópia");
        set_language("es");
        assert_eq!(tr_n(1, "{n} item added from the other copy", "{n} items added from the other copy"), "1 elemento añadido de la otra copia");
        assert_eq!(tr_n(2, "{n} item added from the other copy", "{n} items added from the other copy"), "2 elementos añadidos de la otra copia");
        set_language("en");
    }

    /// O contexto separa traduções do mesmo texto: "All" é "Todas" nas pastas e "Todos" nos tipos.
    #[test]
    fn contexto_separa_o_all_das_pastas_e_dos_tipos() {
        set_language("pt-br");
        assert_eq!(trc("filter", "All"), "Todos");
        assert_eq!(tr("All"), "Todas", "o contexto não vaza para as pastas");
        set_language("en");
    }

    /// Todo `tr("…")`/`tr_fmt("…"` do código tem tradução em pt-br e es (o lugar do antigo enum `Key`, que
    /// garantia a completude pelo compilador); `tr_n` precisa da linha `@plural` e `trc` da linha de
    /// contexto no idioma. Lê as fontes do app, então texto novo sem tradução quebra aqui.
    #[test]
    fn every_text_is_translated() {
        let sources = [include_str!("main.rs"), include_str!("shell.rs")];
        let mut missing = Vec::new();
        for code in ["pt-br", "es"] {
            set_language(code);
            for text in sources.iter().flat_map(|source| literals(source)) {
                if I18N.t(current(), text) == text && !untranslated_on_purpose(text) {
                    missing.push(format!("{code}: {text}"));
                }
            }
            // `tr_n`: o par (um|vários) tem de ter a linha `@plural` no idioma (o fallback é o inglês).
            for (um, varios) in sources.iter().flat_map(|source| plural_literals(source)) {
                let chave = format!("{um}|{varios}");
                if I18N.get(current(), "@plural", &chave).is_none() {
                    missing.push(format!("{code}: @plural {chave}"));
                }
            }
            // `trc`: a linha do contexto precisa existir — cair no texto simples esconderia a falta.
            for (contexto, texto) in sources.iter().flat_map(|source| context_literals(source)) {
                match I18N.get(current(), contexto, texto) {
                    None => missing.push(format!("{code}: {contexto}/{texto}")),
                    Some(traducao) if traducao == texto && !untranslated_on_purpose(texto) => {
                        missing.push(format!("{code}: {contexto}/{texto}"));
                    }
                    Some(_) => {}
                }
            }
        }
        set_language("en");
        missing.sort();
        missing.dedup();
        assert!(missing.is_empty(), "sem tradução:\n{}", missing.join("\n"));
    }

    /// Os textos iguais em todo idioma (siglas, nomes de formato) ou que o português adota como estão
    /// (o teste de completude compararia a tradução com a chave e acharia que falta).
    fn untranslated_on_purpose(text: &str) -> bool {
        matches!(
            text,
            "TOTP"
                | "URL"
                | "s"
                | "Error"
                | "Passphrase"
                | "CSV (Chrome/Edge/LastPass/1Password)"
                | "KeePass (.kdbx)"
                | "Local Keys"
                | "Login"
                | "E-mail"
                | "CPF"
                | "RG"
                | "CNH"
                | "Logins"
        )
    }

    /// Os literais passados a `tr(` e `tr_fmt(` num arquivo-fonte.
    fn literals(source: &'static str) -> Vec<&'static str> {
        let mut found = Vec::new();
        for opener in ["tr(\"", "tr_fmt(\""] {
            let mut rest = source;
            while let Some(at) = rest.find(opener) {
                // `from_str("` também contém `tr("`: só vale com o nome inteiro.
                let whole = rest[..at].chars().next_back().is_none_or(|c| !(c.is_alphanumeric() || c == '_'));
                let after = &rest[at + opener.len()..];
                let Some(end) = after.find('"') else { break };
                if whole {
                    found.push(&after[..end]);
                }
                rest = &after[end..];
            }
        }
        found
    }

    /// Os `count` primeiros literais de texto de cada chamada a `opener` (para em `)`: os argumentos
    /// que não são literais, como o `n` do `tr_n`, são pulados).
    fn arguments(opener: &str, source: &'static str, count: usize) -> Vec<Vec<&'static str>> {
        let mut calls = Vec::new();
        let mut rest = source;
        while let Some(at) = rest.find(opener) {
            let after = &rest[at + opener.len()..];
            let mut literals = Vec::new();
            let mut cursor = after;
            while literals.len() < count {
                let Some(quote) = cursor.find('"') else { break };
                if cursor[..quote].contains(')') {
                    break;
                }
                let text = &cursor[quote + 1..];
                let Some(end) = text.find('"') else { break };
                literals.push(&text[..end]);
                cursor = &text[end + 1..];
            }
            if !literals.is_empty() {
                calls.push(literals);
            }
            rest = after;
        }
        calls
    }

    /// Os pares `(um, vários)` passados a `tr_n(`.
    fn plural_literals(source: &'static str) -> Vec<(&'static str, &'static str)> {
        arguments("tr_n(", source, 2)
            .into_iter()
            .filter_map(|par| match par.as_slice() {
                [um, varios] => Some((*um, *varios)),
                _ => None,
            })
            .collect()
    }

    /// Os pares `(contexto, texto)` passados a `trc(`.
    fn context_literals(source: &'static str) -> Vec<(&'static str, &'static str)> {
        arguments("trc(", source, 2)
            .into_iter()
            .filter_map(|par| match par.as_slice() {
                [contexto, texto] => Some((*contexto, *texto)),
                _ => None,
            })
            .collect()
    }
}
