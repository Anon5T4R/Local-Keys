//! Ponte UniFFI do `localkeys-core` para o Keys Android: o formato `.tkeys`, a criptografia (Argon2id +
//! XChaCha20-Poly1305), a sessão de gravação e a conversão do tipo de um item são o MESMO código do desktop. Aqui
//! só se traduz tipos: nenhuma regra nova e nenhuma primitiva própria — a conversão do `kind` é a regra do núcleo
//! (`change_kind`), exposta com os rótulos e o erro traduzidos. Os bindings Kotlin saem de `uniffi-bindgen` (ver
//! `uniffi.toml`).
//!
//! `panic = "abort"` no release: um panic dentro do `.so` derrubaria o processo de qualquer jeito, e abortar
//! evita unwinding através do FFI. Os erros previstos viram [`FfiError`].

use std::sync::Arc;

use localkeys_core::batch;
use localkeys_core::change_kind::{self, FieldLabels};
use localkeys_core::crypto::{self, CryptoError, KdfParams, SessionKey};

uniffi::setup_scaffolding!();

/// Os erros do `.tkeys`, um a um com o `CryptoError` do núcleo (no Kotlin viram `FfiException`, que o app
/// traduz para o `TkeysError` que as telas já conhecem).
#[derive(Debug, PartialEq, Eq, uniffi::Error)]
pub enum FfiError {
    /// Não é um arquivo `.tkeys` (magic errado ou truncado).
    BadFormat,
    /// Versão de formato que este build não conhece.
    UnsupportedVersion { version: u8 },
    /// Falha ao derivar a chave (parâmetros de Argon2 inválidos).
    Kdf,
    /// Senha errada OU arquivo adulterado (propositalmente indistinguíveis).
    Decrypt,
    /// Estrutura inválida: a chave bruta não tem 32 bytes.
    Corrupted,
    /// O item não pode ser convertido para o tipo pedido (kind desconhecido, JSON inválido, uma chave que o núcleo
    /// não sabe converter…). A `reason` é o `Display` do erro do núcleo — o objeto/campo quando ele sabe dizer.
    NotConvertible { reason: String },
    /// O lote (lixeira/pasta) não pode ser aplicado: um id ausente ou inexistente, um item que já está na lixeira,
    /// JSON que não é uma lista… A `reason` é o `Display` do erro do núcleo.
    InvalidItems { reason: String },
}

impl std::fmt::Display for FfiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            FfiError::BadFormat => write!(f, "bad .tkeys format"),
            FfiError::UnsupportedVersion { version } => write!(f, "unsupported .tkeys version {version}"),
            FfiError::Kdf => write!(f, "key derivation failed"),
            FfiError::Decrypt => write!(f, "wrong password or corrupted file"),
            FfiError::Corrupted => write!(f, "corrupted file"),
            FfiError::NotConvertible { reason } => write!(f, "item cannot be converted to the requested kind: {reason}"),
            FfiError::InvalidItems { reason } => write!(f, "items cannot be changed: {reason}"),
        }
    }
}

impl std::error::Error for FfiError {}

impl From<CryptoError> for FfiError {
    fn from(e: CryptoError) -> Self {
        match e {
            CryptoError::BadFormat => FfiError::BadFormat,
            CryptoError::UnsupportedVersion(version) => FfiError::UnsupportedVersion { version },
            CryptoError::Kdf => FfiError::Kdf,
            CryptoError::Decrypt => FfiError::Decrypt,
        }
    }
}

/// Parâmetros do Argon2id (ficam em claro no header do arquivo). `m_cost` em KiB.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct KdfParamsFfi {
    pub m_cost: u32,
    pub t_cost: u32,
    pub p_cost: u32,
}

impl From<KdfParams> for KdfParamsFfi {
    fn from(p: KdfParams) -> Self {
        KdfParamsFfi { m_cost: p.m_cost, t_cost: p.t_cost, p_cost: p.p_cost }
    }
}

impl From<KdfParamsFfi> for KdfParams {
    fn from(p: KdfParamsFfi) -> Self {
        KdfParams { m_cost: p.m_cost, t_cost: p.t_cost, p_cost: p.p_cost }
    }
}

/// Os rótulos dos campos gerados ao mudar o tipo de um item, no idioma do usuário (o núcleo não tem i18n). Vazio
/// cai no padrão em inglês.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FieldLabelsFfi {
    pub username: String,
    pub password: String,
    pub website: String,
    pub totp: String,
    pub cardholder: String,
    pub brand: String,
    pub card_number: String,
    pub expiry: String,
    pub security_code: String,
    pub first_name: String,
    pub last_name: String,
    pub email: String,
    pub phone: String,
    pub address: String,
}

impl From<FieldLabelsFfi> for FieldLabels {
    fn from(labels: FieldLabelsFfi) -> Self {
        FieldLabels {
            username: labels.username,
            password: labels.password,
            website: labels.website,
            totp: labels.totp,
            cardholder: labels.cardholder,
            brand: labels.brand,
            card_number: labels.card_number,
            expiry: labels.expiry,
            security_code: labels.security_code,
            first_name: labels.first_name,
            last_name: labels.last_name,
            email: labels.email,
            phone: labels.phone,
            address: labels.address,
        }
    }
}

/// Converte um item do cofre (JSON bruto) para outro `kind` pela regra do núcleo (`change_kind`): devolve o item
/// convertido em JSON, com o que não existe no tipo novo virando campos extras.
#[uniffi::export]
pub fn change_item_kind(item_json: String, new_kind: String, labels: FieldLabelsFfi) -> Result<String, FfiError> {
    change_kind::change_kind_json(&item_json, &new_kind, &labels.into()).map_err(|error| FfiError::NotConvertible { reason: error.to_string() })
}

/// Manda para a lixeira os itens de um array JSON (só os SELECIONADOS — o cofre inteiro não atravessa a ponte) e
/// devolve o array atualizado, com o mesmo `now_ms` em todos. Lote é tudo ou nada: um item inválido e nada muda.
#[uniffi::export]
pub fn trash_items(items_json: String, now_ms: u64) -> Result<String, FfiError> {
    batch::trash_items_json(&items_json, now_ms).map_err(|error| FfiError::InvalidItems { reason: error.to_string() })
}

/// Põe na pasta `folder_id` (`None` = sem pasta) os itens de um array JSON (só os SELECIONADOS) e devolve o array
/// atualizado. Lote é tudo ou nada: um item inválido e nada muda.
#[uniffi::export]
pub fn move_items(items_json: String, folder_id: Option<String>, now_ms: u64) -> Result<String, FfiError> {
    batch::move_items_json(&items_json, folder_id.as_deref(), now_ms).map_err(|error| FfiError::InvalidItems { reason: error.to_string() })
}

/// A sessão de um cofre aberto: a chave derivada + salt/params. Regrava sem re-rodar o Argon2 nem guardar a
/// senha (nonce novo a cada `seal`).
#[derive(uniffi::Object)]
pub struct Session {
    inner: SessionKey,
}

#[uniffi::export]
impl Session {
    /// Recifra o `plaintext` com a chave da sessão e um nonce novo: devolve o arquivo `.tkeys` inteiro.
    pub fn seal(&self, plaintext: Vec<u8>) -> Result<Vec<u8>, FfiError> {
        Ok(self.inner.seal(&plaintext)?)
    }

    /// Cópia da chave bruta (32 bytes), só para o cofre do SO no desbloqueio por biometria. Trate como segredo.
    pub fn key_bytes(&self) -> Vec<u8> {
        self.inner.key_bytes().to_vec()
    }
}

/// Um cofre aberto: o JSON decifrado + a sessão para regravar.
#[derive(uniffi::Record)]
pub struct Opened {
    pub plaintext: Vec<u8>,
    pub session: Arc<Session>,
}

/// Um cofre recém-criado: o arquivo `.tkeys` + a sessão (já fica "destrancado").
#[derive(uniffi::Record)]
pub struct Created {
    pub file: Vec<u8>,
    pub session: Arc<Session>,
}

/// Header (60 bytes) + a tag do AEAD (16): abaixo disso o arquivo está truncado, não é senha errada. O Kotlin sempre
/// distinguiu os dois (`Corrupted` x `Decrypt`) e as telas dependem disso; o núcleo só diz `Decrypt`.
const MIN_FILE_LEN: usize = 60 + 16;

/// Um header válido seguido de menos que uma tag é arquivo cortado: `Corrupted`. O resto passa como veio.
fn truncated_is_corrupted(file: &[u8], e: CryptoError) -> FfiError {
    match e {
        CryptoError::Decrypt if file.len() < MIN_FILE_LEN => FfiError::Corrupted,
        other => other.into(),
    }
}

fn opened(plaintext: &[u8], inner: SessionKey) -> Opened {
    Opened { plaintext: plaintext.to_vec(), session: Arc::new(Session { inner }) }
}

/// Os parâmetros padrão do Argon2id (64 MiB, 3 iterações, 1 lane): os mesmos do desktop.
#[uniffi::export]
pub fn default_kdf_params() -> KdfParamsFfi {
    KdfParams::default().into()
}

/// Cria um cofre novo com a `password` e o `plaintext` inicial.
#[uniffi::export]
pub fn create_vault(password: String, plaintext: Vec<u8>, params: KdfParamsFfi) -> Result<Created, FfiError> {
    let (file, inner) = crypto::create_vault_with(&password, &plaintext, params.into())?;
    Ok(Created { file, session: Arc::new(Session { inner }) })
}

/// Abre um `.tkeys` com a senha: valida o header, deriva a chave (Argon2id) e decifra.
#[uniffi::export]
pub fn open_vault(password: String, file: Vec<u8>) -> Result<Opened, FfiError> {
    let (plaintext, inner) = crypto::open_vault(&password, &file).map_err(|e| truncated_is_corrupted(&file, e))?;
    Ok(opened(&plaintext, inner))
}

/// Abre um `.tkeys` com a **chave bruta** (desbloqueio por biometria; não roda o Argon2).
#[uniffi::export]
pub fn open_vault_with_key(key: Vec<u8>, file: Vec<u8>) -> Result<Opened, FfiError> {
    let raw: [u8; 32] = key.as_slice().try_into().map_err(|_| FfiError::Corrupted)?;
    let (plaintext, inner) = crypto::open_with_key(&raw, &file).map_err(|e| truncated_is_corrupted(&file, e))?;
    Ok(opened(&plaintext, inner))
}

/// Lê os parâmetros do Argon2id de um `.tkeys` sem decifrar.
#[uniffi::export]
pub fn read_kdf_params(file: Vec<u8>) -> Result<KdfParamsFfi, FfiError> {
    Ok(crypto::read_kdf_params(&file)?.into())
}

#[cfg(test)]
mod tests {
    use super::*;

    const LIGHT: KdfParamsFfi = KdfParamsFfi { m_cost: 512, t_cost: 1, p_cost: 1 };

    #[test]
    fn criar_abrir_e_regravar_pela_ponte() {
        let created = create_vault("senha".into(), b"{\"items\":[]}".to_vec(), LIGHT).unwrap();
        assert_eq!(read_kdf_params(created.file.clone()).unwrap(), LIGHT);
        let opened = open_vault("senha".into(), created.file.clone()).unwrap();
        assert_eq!(opened.plaintext, b"{\"items\":[]}");

        // Regravar com a sessão: nonce novo, mas abre com a mesma senha e com a chave bruta.
        let again = opened.session.seal(b"{\"items\":[1]}".to_vec()).unwrap();
        assert_ne!(again, created.file);
        let key = opened.session.key_bytes();
        assert_eq!(key.len(), 32);
        assert_eq!(open_vault_with_key(key, again.clone()).unwrap().plaintext, b"{\"items\":[1]}");
        assert_eq!(open_vault("senha".into(), again).unwrap().plaintext, b"{\"items\":[1]}");
    }

    #[test]
    fn erros_chegam_tipados() {
        let created = create_vault("senha".into(), b"{}".to_vec(), LIGHT).unwrap();
        assert_eq!(open_vault("errada".into(), created.file.clone()).err(), Some(FfiError::Decrypt));
        assert_eq!(open_vault("senha".into(), vec![0; 10]).err(), Some(FfiError::BadFormat));
        let mut v2 = created.file.clone();
        v2[6] = 2;
        assert_eq!(open_vault("senha".into(), v2).err(), Some(FfiError::UnsupportedVersion { version: 2 }));
        assert_eq!(open_vault_with_key(vec![0; 5], created.file.clone()).err(), Some(FfiError::Corrupted));
        assert_eq!(open_vault_with_key(vec![0; 32], created.file.clone()).err(), Some(FfiError::Decrypt));
        // Header válido e menos que uma tag depois: arquivo cortado, não senha errada.
        let cut = created.file[..60 + 8].to_vec();
        assert_eq!(open_vault("senha".into(), cut.clone()).err(), Some(FfiError::Corrupted));
        assert_eq!(open_vault_with_key(vec![0; 32], cut).err(), Some(FfiError::Corrupted));
    }

    #[test]
    fn os_parametros_padrao_sao_os_do_desktop() {
        assert_eq!(default_kdf_params(), KdfParamsFfi { m_cost: 65_536, t_cost: 3, p_cost: 1 });
    }

    /// Rótulos vazios: o núcleo cai nos padrões em inglês.
    fn labels() -> FieldLabelsFfi {
        FieldLabelsFfi {
            username: String::new(),
            password: String::new(),
            website: String::new(),
            totp: String::new(),
            cardholder: String::new(),
            brand: String::new(),
            card_number: String::new(),
            expiry: String::new(),
            security_code: String::new(),
            first_name: String::new(),
            last_name: String::new(),
            email: String::new(),
            phone: String::new(),
            address: String::new(),
        }
    }

    #[test]
    fn mudar_o_tipo_de_login_para_nota_devolve_os_campos_extras() {
        let item = r#"{"id":"x","kind":"login","name":"Gmail","favorite":true,"folderId":"f1","notes":"n","createdAt":1,"updatedAt":2,"deletedAt":null,"login":{"username":"joao","password":"segredo","uris":["https://a","https://b"],"totp":"JBSWY3DPEHPK3PXP"},"customFields":[{"id":"c1","name":"Extra","value":"v","hidden":false}],"unknownKey":42}"#;
        let out = change_item_kind(item.into(), "note".into(), labels()).unwrap();

        assert!(out.contains("\"kind\":\"note\""), "{out}");
        assert!(!out.contains("\"login\""), "objeto antigo ficou: {out}");
        for name in ["Username", "Password", "Website", "Website 2", "TOTP", "Extra"] {
            assert!(out.contains(&format!("\"name\":\"{name}\"")), "campo '{name}' sumiu: {out}");
        }
        assert!(out.contains("\"value\":\"joao\""), "{out}");
        // Dois ocultos gerados (senha e TOTP) e quatro visíveis (o extra antigo + usuário + as duas URLs).
        assert_eq!(out.matches("\"hidden\":true").count(), 2, "{out}");
        assert_eq!(out.matches("\"hidden\":false").count(), 4, "{out}");
        assert!(out.contains("\"unknownKey\":42"), "{out}");
    }

    #[test]
    fn erro_de_conversao_chega_tipado_com_a_razao() {
        let reason = |result: Result<String, FfiError>| match result {
            Err(FfiError::NotConvertible { reason }) => reason,
            other => panic!("esperava NotConvertible, veio {other:?}"),
        };
        assert_eq!(reason(change_item_kind("{}".into(), "note".into(), labels())), "unknown item kind");
        assert_eq!(reason(change_item_kind("[1]".into(), "note".into(), labels())), "item is not a JSON object");
        assert!(reason(change_item_kind("não é json".into(), "note".into(), labels())).starts_with("invalid item JSON: "), "JSON inválido");
        assert_eq!(reason(change_item_kind(r#"{"id":"x","kind":"login"}"#.into(), "otp".into(), labels())), "unknown item kind");
        // A razão diz o objeto/campo quando o erro do núcleo sabe. O Android mostra ao usuário só a mensagem
        // traduzida; a razão fica no diagnóstico.
        let item = r#"{"id":"x","kind":"login","name":"n","login":{"username":"u","extra":"fica"}}"#;
        assert_eq!(reason(change_item_kind(item.into(), "note".into(), labels())), "cannot convert `login.extra`: unknown field");
    }

    /// Um array do cofre só com os itens selecionados — o que a ponte recebe do Android.
    const SELECTED: &str = r#"[{"id":"a","kind":"note","name":"A","folderId":null,"createdAt":1,"updatedAt":2,"deletedAt":null},{"id":"b","kind":"login","name":"B","folderId":"work","createdAt":1,"updatedAt":2,"deletedAt":null,"login":{"username":"b","password":"p"}}]"#;

    #[test]
    fn lote_manda_os_itens_para_a_lixeira_com_o_mesmo_agora() {
        let out = trash_items(SELECTED.into(), 1_700).unwrap();
        assert_eq!(out.matches("\"deletedAt\":1700").count(), 2, "{out}");
        assert_eq!(out.matches("\"updatedAt\":1700").count(), 2, "{out}");
        assert!(out.contains("\"id\":\"a\"") && out.contains("\"id\":\"b\""), "{out}");
        assert!(out.contains("\"username\":\"b\""), "o resto do item atravessa: {out}");
    }

    #[test]
    fn lote_move_para_uma_pasta_e_para_sem_pasta() {
        let out = move_items(SELECTED.into(), Some("home".into()), 1_700).unwrap();
        assert_eq!(out.matches("\"folderId\":\"home\"").count(), 2, "{out}");
        assert_eq!(out.matches("\"updatedAt\":1700").count(), 2, "{out}");

        let out = move_items(SELECTED.into(), None, 1_700).unwrap();
        assert_eq!(out.matches("\"folderId\":null").count(), 2, "{out}");
    }

    #[test]
    fn erro_do_lote_chega_tipado_com_a_razao() {
        let reason = |result: Result<String, FfiError>| match result {
            Err(FfiError::InvalidItems { reason }) => reason,
            other => panic!("esperava InvalidItems, veio {other:?}"),
        };
        assert!(reason(trash_items("não é json".into(), 1)).starts_with("invalid items JSON: "), "JSON inválido");
        assert!(reason(move_items("{}".into(), None, 1)).starts_with("invalid items JSON: "), "JSON que não é array");
        assert_eq!(reason(trash_items(r#"[{"name":"sem id"}]"#.into(), 1)), "item has no `id`");
        assert_eq!(reason(trash_items(r#"[{"id":"a","deletedAt":9}]"#.into(), 1)), "item `a` is already in the trash");
        assert_eq!(FfiError::InvalidItems { reason: "x".into() }.to_string(), "items cannot be changed: x");
    }
}
