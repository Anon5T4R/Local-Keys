//! Schema do vault `.tkeys` (o JSON dentro do blob cifrado), compatível com o
//! LocalKeys oficial — lá o schema vive no front TS (`src/types.ts`); aqui só
//! o subconjunto que o app usa.
//!
//! ESCRITA CONSERVADORA: o app acrescenta itens (login/nota) e anexos a um
//! item. Pastas, campos custom, histórico de senha e o que mais existir num
//! cofre REAL atravessam intactos — manipulamos o JSON bruto
//! (`serde_json::Value`), nunca um struct que re-serializaria só os campos
//! conhecidos.

use crate::card;
use serde_json::{Value, json};

/// O tipo de um item — os quatro `kind` que o cofre conhece (o mesmo wire do oficial e do Android).
/// Valor desconhecido no arquivo cai em [`ItemKind::Note`]: nota é o tipo sem campos próprios, então um
/// `kind` novo (gravado por uma versão futura) abre e edita sem perder nada.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum ItemKind {
    Login,
    Note,
    Card,
    Identity,
}

impl ItemKind {
    /// A string que vai no JSON.
    pub fn wire(self) -> &'static str {
        match self {
            Self::Login => "login",
            Self::Note => "note",
            Self::Card => "card",
            Self::Identity => "identity",
        }
    }

    /// O `kind` do JSON; desconhecido → `None`.
    pub fn from_wire(wire: &str) -> Option<Self> {
        match wire {
            "login" => Some(Self::Login),
            "note" => Some(Self::Note),
            "card" => Some(Self::Card),
            "identity" => Some(Self::Identity),
            _ => None,
        }
    }
}

/// Vault recém-criado — mesma semente do oficial (`lib.rs` do LocalKeys).
pub const EMPTY_VAULT: &str = r#"{"version":1,"folders":[],"items":[]}"#;

/// Gravação atômica — copiada verbatim do LocalKeys (`lib.rs`): tmp + rename
/// por cima (atómico em Windows e Unix), com `.bak` do estado anterior. Uma
/// gravação interrompida não corrompe o vault.
pub fn atomic_write(path: &std::path::Path, bytes: &[u8]) -> Result<(), String> {
    if path.exists() {
        let _ = std::fs::copy(path, format!("{}.bak", path.display()));
    }
    let tmp = format!("{}.tmp", path.display());
    std::fs::write(&tmp, bytes).map_err(|e| format!("falha ao gravar '{tmp}': {e}"))?;
    std::fs::rename(&tmp, path).map_err(|e| {
        let _ = std::fs::remove_file(&tmp);
        format!("falha ao substituir '{}': {}", path.display(), e)
    })
}

/// Linha da lista (visão só do que a UI mostra).
pub struct ItemView {
    pub id: String,
    pub name: String,
    pub kind: ItemKind,
    /// Segunda linha da lista: o resumo do tipo (usuário, bandeira e final do cartão, nome da pessoa,
    /// começo da nota). `None` = só o nome.
    pub summary: Option<String>,
    /// Nome de usuário do login — só para as ações de copiar (o resumo da lista é o [`Self::summary`]).
    pub username: Option<String>,
    /// Segredo TOTP (base32) se o item tiver — a UI gera o código ao vivo.
    pub totp: Option<String>,
    pub favorite: bool,
    /// Id da pasta (None = sem pasta).
    pub folder_id: Option<String>,
    /// Se o item tem anexos.
    pub has_attachments: bool,
}

#[derive(serde::Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
struct RawItem {
    id: String,
    name: String,
    favorite: bool,
    folder_id: Option<String>,
    login: Option<RawLogin>,
    attachments: Option<Vec<serde_json::Value>>,
}

#[derive(serde::Deserialize, Default)]
#[serde(default)]
struct RawLogin {
    username: String,
    totp: String,
}

/// Na lixeira? `deletedAt` presente, não nulo e diferente de `0` — a regra ÚNICA do app (o `0` do epoch é falsy no
/// JS do oficial): a lista, a lixeira e o lote usam esta função, para o item nunca sumir dos dois lados.
pub fn is_trashed(item: &Value) -> bool {
    item.get("deletedAt").is_some_and(|deleted_at| !deleted_at.is_null() && deleted_at.as_i64() != Some(0))
}

/// Visão de lista do vault (lixeira — `deletedAt` — fica fora, como no oficial).
pub fn items_view(raw: &Value) -> Vec<ItemView> {
    raw.get("items")
        .and_then(Value::as_array)
        .map(|arr| {
            arr.iter()
                .filter_map(|it| {
                    if is_trashed(it) {
                        return None;
                    }
                    let ri: RawItem = serde_json::from_value(it.clone()).ok()?;
                    Some(ItemView {
                        id: ri.id,
                        name: ri.name,
                        kind: it.get("kind").and_then(Value::as_str).and_then(ItemKind::from_wire).unwrap_or(ItemKind::Note),
                        summary: list_summary(it),
                        username: ri.login.as_ref().map(|l| l.username.clone()),
                        totp: ri.login.and_then(|l| if l.totp.is_empty() { None } else { Some(l.totp) }),
                        favorite: ri.favorite,
                        folder_id: ri.folder_id,
                        has_attachments: ri
                            .attachments
                            .as_ref()
                            .is_some_and(|a| a.iter().any(|att| att.get("deletedAt").is_none() || att.get("deletedAt") == Some(&Value::Null))),
                    })
                })
                .collect()
        })
        .unwrap_or_default()
}

/// A segunda linha da lista por tipo — o `subtitle` do `VaultFilters.kt` do Android, que é a referência:
/// login → usuário; cartão → "bandeira  •••• 1234" (sem número suficiente, o titular); identidade → nome
/// completo (sem ele, o e-mail); nota → a primeira linha não vazia.
fn list_summary(item: &Value) -> Option<String> {
    let text = |key: &str| item.get(key).and_then(Value::as_str).map(str::trim).unwrap_or_default();
    match item.get("kind").and_then(Value::as_str).and_then(ItemKind::from_wire).unwrap_or(ItemKind::Note) {
        ItemKind::Login => {
            item.get("login").and_then(|l| l.get("username")).and_then(Value::as_str).map(str::trim).filter(|u| !u.is_empty()).map(str::to_string)
        }
        ItemKind::Card => {
            let card = item.get("card")?;
            let brand = card.get("brand").and_then(Value::as_str).map(str::trim).unwrap_or_default();
            let digits: String = card.get("number").and_then(Value::as_str).unwrap_or_default().chars().filter(char::is_ascii_digit).collect();
            let last4 = if digits.chars().count() >= 4 {
                let start = digits.char_indices().nth_back(3).map_or(0, |(i, _)| i);
                format!("•••• {}", &digits[start..])
            } else {
                String::new()
            };
            let joined = [brand, last4.as_str()].iter().filter(|p| !p.is_empty()).copied().collect::<Vec<_>>().join("  ");
            let cardholder = card.get("cardholder").and_then(Value::as_str).map(str::trim).unwrap_or_default();
            Some(if joined.is_empty() { cardholder.to_string() } else { joined }).filter(|s| !s.is_empty())
        }
        ItemKind::Identity => {
            let id = item.get("identity")?;
            let first = id.get("firstName").and_then(Value::as_str).map(str::trim).unwrap_or_default();
            let last = id.get("lastName").and_then(Value::as_str).map(str::trim).unwrap_or_default();
            let name = [first, last].iter().filter(|p| !p.is_empty()).copied().collect::<Vec<_>>().join(" ");
            let email = id.get("email").and_then(Value::as_str).map(str::trim).unwrap_or_default();
            Some(if name.is_empty() { email.to_string() } else { name }).filter(|s| !s.is_empty())
        }
        ItemKind::Note => text("notes").lines().map(str::trim).find(|l| !l.is_empty()).map(str::to_string),
    }
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// Id no MESMO formato do oficial (`crypto.randomUUID()` do TS): UUID v4
/// com traços (8-4-4-4-12). Era 32-hex sem traços — funcional (id é opaco),
/// mas um parser estrito de UUID reclamaria; agora é indistinguível.
///
/// Público para as regras que criam id (a junção de outro cofre em [`crate::merge_other`]).
pub fn gen_id() -> String {
    let mut b = rand::random::<u128>().to_le_bytes();
    b[6] = (b[6] & 0x0f) | 0x40; // version 4
    b[8] = (b[8] & 0x3f) | 0x80; // variant 10xx
    let h = |x: &[u8]| x.iter().map(|c| format!("{c:02x}")).collect::<String>();
    format!("{}-{}-{}-{}-{}", h(&b[0..4]), h(&b[4..6]), h(&b[6..8]), h(&b[8..10]), h(&b[10..16]))
}

/// Campos do login de um item novo (só faz sentido para `kind = "login"`).
/// Strings próprias: os importadores montam o TOTP já extraído (dono) e não
/// ganham nada emprestando o buffer da linha do CSV.
pub struct LoginFields {
    pub username: String,
    pub password: String,
    pub uris: Vec<String>,
    pub totp: String,
}

/// Item novo no shape do `emptyItem` do oficial — base comum do `add_login` e
/// dos importadores (`import.rs`). `login` só entra quando o item é de LOGIN:
/// note/card/identity não carregam o campo (mesmo comportamento do TS).
pub struct NewItem<'a> {
    pub kind: &'a str,
    pub name: &'a str,
    pub notes: &'a str,
    pub favorite: bool,
    pub login: Option<LoginFields>,
    pub custom_fields: Vec<Value>,
}

/// Monta o item novo (id/timestamps daqui). Não toca no vault — quem grava é
/// `push_item`/`push_items`.
pub fn make_item(n: NewItem<'_>) -> Value {
    let now = now_ms();
    let mut item = json!({
        "id": gen_id(),
        "kind": n.kind,
        "name": n.name,
        "favorite": n.favorite,
        "folderId": null,
        "notes": n.notes,
        "createdAt": now,
        "updatedAt": now,
        "deletedAt": null,
    });
    if let Some(login) = n.login {
        item["login"] = json!({
            "username": login.username,
            "password": login.password,
            "uris": login.uris,
            "totp": login.totp,
        });
    }
    if !n.custom_fields.is_empty() {
        item["customFields"] = Value::Array(n.custom_fields);
    }
    item
}

/// Campo personalizado no shape do oficial: `{id,name,value,hidden}`.
pub fn custom_field(name: &str, value: &str, hidden: bool) -> Value {
    json!({ "id": gen_id(), "name": name, "value": value, "hidden": hidden })
}

/// Um campo personalizado para a UI (o `id` vazio marca um campo NOVO — [`set_custom_fields`] gera o id ao
/// gravar).
pub struct CustomFieldView {
    pub id: String,
    pub name: String,
    pub value: String,
    pub hidden: bool,
}

/// Os `customFields` de um item (vazio quando não tem). Só as chaves do oficial; desconhecidas dentro de
/// um campo atravessam via [`set_custom_fields`], que grava por cima do campo existente.
fn custom_fields_of(item: &Value) -> Vec<CustomFieldView> {
    item.get("customFields")
        .and_then(Value::as_array)
        .map(|arr| {
            arr.iter()
                .map(|f| CustomFieldView {
                    id: f.get("id").and_then(Value::as_str).unwrap_or_default().to_string(),
                    name: f.get("name").and_then(Value::as_str).unwrap_or_default().to_string(),
                    value: f.get("value").and_then(Value::as_str).unwrap_or_default().to_string(),
                    hidden: f.get("hidden").and_then(Value::as_bool).unwrap_or(false),
                })
                .collect()
        })
        .unwrap_or_default()
}

/// Os campos personalizados de um item pelo id — o que o form de edição carrega.
pub fn custom_fields(raw: &Value, id: &str) -> Vec<CustomFieldView> {
    item(raw, id).map(custom_fields_of).unwrap_or_default()
}

/// SUBSTITUI os `customFields` de um item (mostrar/adicionar/editar/remover do form passa por aqui, para
/// qualquer tipo). Escrita conservadora: campo com `id` existente é regravado por cima (chaves desconhecidas
/// do campo ficam), campo novo ganha id; lista vazia num item que JÁ TINHA campos vira `[]` (remove todos),
/// num item que não tinha não cria a chave. Não toca em `updatedAt` — o add/edit do item já gravou.
pub fn set_custom_fields(raw: &mut Value, item_id: &str, fields: &[CustomFieldView]) -> bool {
    let Some(it) = item_mut(raw, item_id) else {
        return false;
    };
    let tinha = it.get("customFields").is_some_and(Value::is_array);
    if fields.is_empty() {
        if tinha {
            it["customFields"] = json!([]);
        }
        return true;
    }
    if !tinha {
        it["customFields"] = json!([]);
    }
    // O array velho ANTES de limpar: campo já gravado é regravado por cima (chaves desconhecidas ficam).
    let velho: Vec<Value> = it.get("customFields").and_then(Value::as_array).cloned().unwrap_or_default();
    let arr = it["customFields"].as_array_mut().expect("customFields é array (garantido acima)");
    arr.clear();
    for f in fields {
        let mut obj = velho
            .iter()
            .find(|old| old.get("id").and_then(Value::as_str) == Some(f.id.as_str()))
            .cloned()
            .unwrap_or_else(|| json!({ "id": if f.id.is_empty() { gen_id() } else { f.id.clone() } }));
        obj["name"] = json!(f.name);
        obj["value"] = json!(f.value);
        obj["hidden"] = json!(f.hidden);
        arr.push(obj);
    }
    true
}

/// Acrescenta um item já montado (`make_item`) ao vault bruto.
pub fn push_item(raw: &mut Value, item: Value) {
    if !raw["items"].is_array() {
        raw["items"] = json!([]);
    }
    raw["items"].as_array_mut().expect("items é array (garantido acima)").push(item);
}

/// Acrescenta um lote de itens — caminho dos importadores.
pub fn push_items(raw: &mut Value, items: Vec<Value>) {
    for item in items {
        push_item(raw, item);
    }
}

/// Campos do form de login — o que a UI coleta pra criar OU editar um item.
/// Strings emprestadas: o chamador é dono dos buffers do formulário.
#[derive(Clone, Copy)]
pub struct LoginForm<'a> {
    pub name: &'a str,
    pub username: &'a str,
    pub password: &'a str,
    pub totp: &'a str,
    /// Primeira URI (o form edita só ela; as demais atravessam intactas).
    pub url: &'a str,
    pub notes: &'a str,
}

/// Tudo que o form de edição mostra de um login (a URL é só a primeira, a que o form edita).
pub struct LoginFull {
    pub username: String,
    pub password: String,
    pub totp: String,
    pub url: String,
}

/// Campos do form de cartão — o que a UI coleta pra criar OU editar. `number`, `exp` e `code` chegam como
/// digitados; o armazenamento ([`add_card`]/[`edit_card`]) normaliza pela regra fixa do cofre
/// (`card.number`/`card.code` só dígitos, `card.exp` `MM/AA`).
#[derive(Clone, Copy)]
pub struct CardForm<'a> {
    pub name: &'a str,
    pub cardholder: &'a str,
    pub brand: &'a str,
    pub number: &'a str,
    pub exp: &'a str,
    pub code: &'a str,
    pub notes: &'a str,
}

/// Campos do form de identidade.
#[derive(Clone, Copy)]
pub struct IdentityForm<'a> {
    pub name: &'a str,
    pub first_name: &'a str,
    pub last_name: &'a str,
    pub email: &'a str,
    pub phone: &'a str,
    pub address: &'a str,
    pub notes: &'a str,
}

/// Campos do form de nota.
#[derive(Clone, Copy)]
pub struct NoteForm<'a> {
    pub name: &'a str,
    pub notes: &'a str,
}

/// O cartão de um item, para o form de edição carregar.
pub struct CardFull {
    pub cardholder: String,
    pub brand: String,
    pub number: String,
    pub exp: String,
    pub code: String,
}

/// A identidade de um item, para o form de edição carregar.
pub struct IdentityFull {
    pub first_name: String,
    pub last_name: String,
    pub email: String,
    pub phone: String,
    pub address: String,
}

/// Tudo o que o form de edição mostra de um item, de qualquer tipo: o tipo (fixo no form), os campos do
/// tipo e os campos personalizados. O que não existe para o tipo vem `None`/vazio.
pub struct ItemFull {
    pub kind: ItemKind,
    pub name: String,
    pub notes: String,
    pub login: Option<LoginFull>,
    pub card: Option<CardFull>,
    pub identity: Option<IdentityFull>,
    pub custom_fields: Vec<CustomFieldView>,
}

/// Carrega um item para o form de edição. O `kind` decide quais campos a UI mostra; `login` só existe em
/// item de login (nota/cartão/identidade não carregam), e o contrário vale para `card`/`identity`.
pub fn item_full(raw: &Value, id: &str) -> Option<ItemFull> {
    let it = item(raw, id)?;
    let notes = it.get("notes").and_then(Value::as_str).unwrap_or("").to_string();
    let login = it.get("login").filter(|l| l.is_object()).map(|login| LoginFull {
        username: login.get("username").and_then(Value::as_str).unwrap_or("").to_string(),
        password: login.get("password").and_then(Value::as_str).unwrap_or("").to_string(),
        totp: login.get("totp").and_then(Value::as_str).unwrap_or("").to_string(),
        url: login.get("uris").and_then(|u| u.get(0)).and_then(Value::as_str).unwrap_or("").to_string(),
    });
    let card = it.get("card").filter(|c| c.is_object()).map(|card| CardFull {
        cardholder: card.get("cardholder").and_then(Value::as_str).unwrap_or("").to_string(),
        brand: card.get("brand").and_then(Value::as_str).unwrap_or("").to_string(),
        number: card.get("number").and_then(Value::as_str).unwrap_or("").to_string(),
        exp: card.get("exp").and_then(Value::as_str).unwrap_or("").to_string(),
        code: card.get("code").and_then(Value::as_str).unwrap_or("").to_string(),
    });
    let identity = it.get("identity").filter(|i| i.is_object()).map(|identity| IdentityFull {
        first_name: identity.get("firstName").and_then(Value::as_str).unwrap_or("").to_string(),
        last_name: identity.get("lastName").and_then(Value::as_str).unwrap_or("").to_string(),
        email: identity.get("email").and_then(Value::as_str).unwrap_or("").to_string(),
        phone: identity.get("phone").and_then(Value::as_str).unwrap_or("").to_string(),
        address: identity.get("address").and_then(Value::as_str).unwrap_or("").to_string(),
    });
    Some(ItemFull {
        kind: it.get("kind").and_then(Value::as_str).and_then(ItemKind::from_wire).unwrap_or(ItemKind::Note),
        name: it.get("name").and_then(Value::as_str).unwrap_or("").to_string(),
        notes,
        login,
        card,
        identity,
        custom_fields: custom_fields_of(it),
    })
}

/// Linha da lixeira: o que a UI mostra e o id pra restaurar/excluir de vez.
pub struct TrashRow {
    pub id: String,
    pub name: String,
    /// `deletedAt` em ms desde o epoch (o oficial grava `Date.now()`).
    pub deleted_at: i64,
}

/// ACRESCENTA um item de login no vault bruto (mesma forma que `emptyItem`
/// do oficial gera pra kind=login — `uris` vazia vira `[""]`).
pub fn add_login(raw: &mut Value, form: LoginForm<'_>) {
    let item = make_item(NewItem {
        kind: "login",
        name: form.name,
        notes: form.notes,
        favorite: false,
        login: Some(LoginFields {
            username: form.username.to_string(),
            password: form.password.to_string(),
            uris: vec![form.url.to_string()],
            totp: form.totp.to_string(),
        }),
        custom_fields: Vec::new(),
    });
    push_item(raw, item);
}

/// O objeto `card` de um item: normalizado pela regra fixa do cofre (número e código só com dígitos,
/// validade `MM/AA` — o `MM/AAAA` continua aceito, ver `card.rs`).
fn card_object(form: &CardForm<'_>) -> Value {
    json!({
        "cardholder": form.cardholder,
        "brand": form.brand,
        "number": card::number(form.number),
        "exp": card::expiry(form.exp),
        "code": card::cvv(form.code),
    })
}

/// ACRESCENTA um item de cartão (kind=card, sem `login` — o `emptyItem("card")` do oficial não carrega).
pub fn add_card(raw: &mut Value, form: CardForm<'_>) {
    let mut item = make_item(NewItem { kind: "card", name: form.name, notes: form.notes, favorite: false, login: None, custom_fields: Vec::new() });
    item["card"] = card_object(&form);
    push_item(raw, item);
}

/// EDITA um item de cartão — mesma escrita conservadora do `edit_login`: só `name`/`notes`/`updatedAt` e as
/// cinco chaves de `card` (o objeto nasce se faltava; chaves desconhecidas DENTRO de `card` ficam).
pub fn edit_card(raw: &mut Value, id: &str, form: CardForm<'_>) -> bool {
    let Some(it) = item_mut(raw, id) else {
        return false;
    };
    it["name"] = json!(form.name);
    it["notes"] = json!(form.notes);
    it["updatedAt"] = json!(now_ms());
    let card = card_object(&form);
    if !it.get("card").is_some_and(Value::is_object) {
        it["card"] = json!({});
    }
    if let Some(obj) = it.get_mut("card").and_then(Value::as_object_mut) {
        for key in ["cardholder", "brand", "number", "exp", "code"] {
            obj.insert(key.into(), card[key].clone());
        }
    }
    true
}

/// O objeto `identity` de um item.
fn identity_object(form: &IdentityForm<'_>) -> Value {
    json!({
        "firstName": form.first_name,
        "lastName": form.last_name,
        "email": form.email,
        "phone": form.phone,
        "address": form.address,
    })
}

/// ACRESCENTA um item de identidade (kind=identity, sem `login`).
pub fn add_identity(raw: &mut Value, form: IdentityForm<'_>) {
    let mut item =
        make_item(NewItem { kind: "identity", name: form.name, notes: form.notes, favorite: false, login: None, custom_fields: Vec::new() });
    item["identity"] = identity_object(&form);
    push_item(raw, item);
}

/// EDITA um item de identidade — escrita conservadora como [`edit_card`].
pub fn edit_identity(raw: &mut Value, id: &str, form: IdentityForm<'_>) -> bool {
    let Some(it) = item_mut(raw, id) else {
        return false;
    };
    it["name"] = json!(form.name);
    it["notes"] = json!(form.notes);
    it["updatedAt"] = json!(now_ms());
    let identity = identity_object(&form);
    if !it.get("identity").is_some_and(Value::is_object) {
        it["identity"] = json!({});
    }
    if let Some(obj) = it.get_mut("identity").and_then(Value::as_object_mut) {
        for key in ["firstName", "lastName", "email", "phone", "address"] {
            obj.insert(key.into(), identity[key].clone());
        }
    }
    true
}

/// ACRESCENTA um item de nota (kind=note, sem `login`/`card`/`identity`).
pub fn add_note(raw: &mut Value, form: NoteForm<'_>) {
    let item = make_item(NewItem { kind: "note", name: form.name, notes: form.notes, favorite: false, login: None, custom_fields: Vec::new() });
    push_item(raw, item);
}

/// EDITA um item de nota — só `name`/`notes`/`updatedAt` (nota não tem campos próprios).
pub fn edit_note(raw: &mut Value, id: &str, form: NoteForm<'_>) -> bool {
    let Some(it) = item_mut(raw, id) else {
        return false;
    };
    it["name"] = json!(form.name);
    it["notes"] = json!(form.notes);
    it["updatedAt"] = json!(now_ms());
    true
}

/// `(username, password, totp)` do login pelo id — só leitura.
pub fn login_triple(raw: &Value, id: &str) -> Option<(String, String, String)> {
    raw.get("items")?.as_array()?.iter().find_map(|it| {
        let same = it.get("id").and_then(Value::as_str) == Some(id);
        if !same {
            return None;
        }
        let login = it.get("login")?;
        Some((
            login.get("username").and_then(Value::as_str)?.to_string(),
            login.get("password").and_then(Value::as_str)?.to_string(),
            login.get("totp").and_then(Value::as_str).unwrap_or("").to_string(),
        ))
    })
}

/// EDITA um item existente (por id) — mesma escrita conservadora do add: só
/// toca nos campos do próprio item, tudo ao redor atravessa intacto.
///
/// Espelha o `updateItem` do oficial (`store.ts`): se a senha de um LOGIN
/// mudou (e a anterior não era vazia), a anterior vai pro topo do
/// `passwordHistory` com cap de 20 entradas.
///
/// `uris`: só a PRIMEIRA posição entra pelo form — as outras (que o app não
/// mostra) continuam onde estão; lista vazia vira `[""]`, como o `emptyItem`.
pub fn edit_login(raw: &mut Value, id: &str, form: LoginForm<'_>) -> bool {
    let LoginForm { name, username, password, totp, url, notes } = form;
    let Some(items) = raw.get_mut("items").and_then(Value::as_array_mut) else {
        return false;
    };
    for it in items.iter_mut() {
        if it.get("id").and_then(Value::as_str) != Some(id) {
            continue;
        }
        // Histórico: guardar a senha anterior ANTES de sobrescrever
        // (condições idênticas às do oficial: kind login + senha antiga não
        // vazia + senha de fato mudou).
        let old_pass = it.get("login").and_then(|l| l.get("password")).and_then(Value::as_str).unwrap_or("").to_string();
        let is_login = it.get("kind").and_then(Value::as_str) == Some("login");

        it["name"] = json!(name);
        it["notes"] = json!(notes);
        it["updatedAt"] = json!(now_ms());
        // login pode não existir em item de outra classe → cria com a forma
        // completa (uris vazios, como o emptyItem do oficial).
        if !it.get("login").is_some_and(Value::is_object) {
            it["login"] = json!({ "username": "", "password": "", "uris": [""], "totp": "" });
        }
        if let Some(login) = it.get_mut("login").and_then(Value::as_object_mut) {
            login.insert("username".into(), json!(username));
            login.insert("password".into(), json!(password));
            login.insert("totp".into(), json!(totp));
            // uris: edita a primeira, preserva o resto da lista.
            let mut uris: Vec<Value> = login.get("uris").and_then(Value::as_array).cloned().unwrap_or_default();
            if uris.is_empty() {
                uris.push(json!(""));
            }
            uris[0] = json!(url);
            login.insert("uris".into(), Value::Array(uris));
        }

        if is_login && !old_pass.is_empty() && old_pass != password {
            let mut hist: Vec<Value> = it.get("passwordHistory").and_then(Value::as_array).cloned().unwrap_or_default();
            hist.insert(0, json!({ "password": old_pass, "at": now_ms() }));
            hist.truncate(20); // cap do oficial
            it["passwordHistory"] = Value::Array(hist);
        }
        return true;
    }
    false
}

/// Exclusão LÓGica (lixeira do oficial): marca `deletedAt` — e SÓ ele, como
/// o `trashItem` do oficial (não toca em `updatedAt`). Um `.tkeys` aberto no
/// LocalKeys continua vendo o item na lixeira.
pub fn delete_login(raw: &mut Value, id: &str) -> bool {
    let Some(items) = raw.get_mut("items").and_then(Value::as_array_mut) else {
        return false;
    };
    for it in items.iter_mut() {
        if it.get("id").and_then(Value::as_str) == Some(id) {
            it["deletedAt"] = json!(now_ms());
            return true;
        }
    }
    false
}

/// Favorita/desfavorita — escrita conservadora: só `favorite` do item alvo.
///
/// Espelha o oficial: o ★ do editor chama `updateItem`, que grava
/// `updatedAt: Date.now()` junto (o store não tem um `toggleFavorite` à
/// parte) — então favoritar MEXE em `updatedAt`, e o app faz igual.
pub fn set_favorite(raw: &mut Value, id: &str, favorite: bool) -> bool {
    let Some(it) = item_mut(raw, id) else {
        return false;
    };
    it["favorite"] = json!(favorite);
    it["updatedAt"] = json!(now_ms());
    true
}

/// Restaura da lixeira — igual ao `restoreItem` do oficial: zera `deletedAt`
/// e não toca em mais nada (nem `updatedAt`).
pub fn restore_item(raw: &mut Value, id: &str) -> bool {
    let Some(it) = item_mut(raw, id) else {
        return false;
    };
    it["deletedAt"] = Value::Null;
    true
}

/// Exclui DE VEZ (fora da lixeira) — igual ao `deleteForever` do oficial:
/// tira o item do array, sem deixar rastro.
pub fn delete_forever(raw: &mut Value, id: &str) -> bool {
    let Some(items) = raw.get_mut("items").and_then(Value::as_array_mut) else {
        return false;
    };
    let before = items.len();
    items.retain(|it| it.get("id").and_then(Value::as_str) != Some(id));
    items.len() != before
}

/// Itens na lixeira, na ordem do array (como o filtro "trash" do oficial).
pub fn trashed_items(raw: &Value) -> Vec<TrashRow> {
    raw.get("items")
        .and_then(Value::as_array)
        .map(|arr| {
            arr.iter()
                .filter_map(|it| {
                    if !is_trashed(it) {
                        return None;
                    }
                    Some(TrashRow {
                        id: it.get("id")?.as_str()?.to_string(),
                        name: it.get("name").and_then(Value::as_str).unwrap_or("").to_string(),
                        deleted_at: it.get("deletedAt").and_then(Value::as_i64)?,
                    })
                })
                .collect()
        })
        .unwrap_or_default()
}

fn item_mut<'a>(raw: &'a mut Value, id: &str) -> Option<&'a mut Value> {
    raw.get_mut("items")?.as_array_mut()?.iter_mut().find(|it| it.get("id").and_then(Value::as_str) == Some(id))
}

fn item<'a>(raw: &'a Value, id: &str) -> Option<&'a Value> {
    raw.get("items")?.as_array()?.iter().find(|it| it.get("id").and_then(Value::as_str) == Some(id))
}

/// Anexa um arquivo a um item — escrita conservadora: só mexe no array
/// `attachments` do item alvo, o resto do cofre atravessa intacto.
///
/// `data_b64` é o conteúdo já em base64 (o blob `.tkeys` cifra o item inteiro,
/// então o anexo não precisa de cifra própria). `size` é o tamanho dos bytes
/// DECODIFICADOS, como o oficial espera. Base64 inválido ou item inexistente →
/// `false` e nada muda.
pub fn add_attachment(raw: &mut Value, item_id: &str, name: &str, mime: &str, data_b64: &str) -> bool {
    use base64::Engine as _;

    let data_b64 = data_b64.trim();
    let Ok(bytes) = base64::engine::general_purpose::STANDARD.decode(data_b64) else {
        return false;
    };
    let Some(it) = item_mut(raw, item_id) else {
        return false;
    };
    let att = json!({
        "id": gen_id(),
        "name": name,
        "size": bytes.len(),
        "mime": mime,
        "dataB64": data_b64,
        "deletedAt": null,
    });
    if !it.get("attachments").is_some_and(Value::is_array) {
        it["attachments"] = json!([]);
    }
    it["attachments"].as_array_mut().expect("attachments é array (garantido acima)").push(att);
    true
}

/// `(name, dataB64)` de um anexo — o que a UI precisa pra salvar em disco.
/// Só anexos vivos (não na lixeira).
pub fn attachment_data(raw: &Value, item_id: &str, attachment_id: &str) -> Option<(String, String)> {
    let atts = item(raw, item_id)?.get("attachments")?.as_array()?;
    let att = atts.iter().find(|a| {
        a.get("id").and_then(Value::as_str) == Some(attachment_id) && (a.get("deletedAt").is_none() || a.get("deletedAt") == Some(&Value::Null))
    })?;
    Some((att.get("name").and_then(Value::as_str)?.to_string(), att.get("dataB64").and_then(Value::as_str)?.to_string()))
}

/// Remove um anexo do item (escrita conservadora: só o array do item alvo).
pub fn delete_attachment(raw: &mut Value, item_id: &str, attachment_id: &str) -> bool {
    let Some(it) = item_mut(raw, item_id) else {
        return false;
    };
    let Some(atts) = it.get_mut("attachments").and_then(Value::as_array_mut) else {
        return false;
    };
    let mut changed = false;
    for a in atts.iter_mut() {
        if a.get("id").and_then(Value::as_str) == Some(attachment_id) {
            a["deletedAt"] = json!(now_ms());
            changed = true;
            break;
        }
    }
    changed
}

/// Restaura um anexo da lixeira (limpa `deletedAt`).
pub fn restore_attachment(raw: &mut Value, item_id: &str, attachment_id: &str) -> bool {
    let Some(it) = item_mut(raw, item_id) else {
        return false;
    };
    let Some(atts) = it.get_mut("attachments").and_then(Value::as_array_mut) else {
        return false;
    };
    let mut changed = false;
    for a in atts.iter_mut() {
        if a.get("id").and_then(Value::as_str) == Some(attachment_id) {
            a["deletedAt"] = Value::Null;
            changed = true;
            break;
        }
    }
    changed
}

/// Exclui um anexo de vez (remove do array).
pub fn delete_attachment_forever(raw: &mut Value, item_id: &str, attachment_id: &str) -> bool {
    let Some(it) = item_mut(raw, item_id) else {
        return false;
    };
    let Some(atts) = it.get_mut("attachments").and_then(Value::as_array_mut) else {
        return false;
    };
    let before = atts.len();
    atts.retain(|a| a.get("id").and_then(Value::as_str) != Some(attachment_id));
    atts.len() < before
}

/// Lista anexos na lixeira de um item.
#[allow(dead_code)] // API simétrica a attachment_data; ainda sem tela que use
pub fn trashed_attachments(raw: &Value, item_id: &str) -> Vec<(String, String, u64)> {
    let Some(it) = item(raw, item_id) else {
        return Vec::new();
    };
    let Some(atts) = it.get("attachments").and_then(Value::as_array) else {
        return Vec::new();
    };
    atts.iter()
        .filter_map(|a| {
            if a.get("deletedAt").is_none() || a.get("deletedAt") == Some(&Value::Null) {
                return None;
            }
            Some((
                a.get("id").and_then(Value::as_str)?.to_string(),
                a.get("name").and_then(Value::as_str).unwrap_or("anexo").to_string(),
                a.get("size").and_then(Value::as_u64).unwrap_or(0),
            ))
        })
        .collect()
}

// ── pastas (folders) ─────────────────────────────────────────────────────

/// Uma pasta do vault (visão de lista).
pub struct FolderView {
    pub id: String,
    pub name: String,
}

/// Lista as pastas do vault.
pub fn folders(raw: &Value) -> Vec<FolderView> {
    raw.get("folders")
        .and_then(Value::as_array)
        .map(|arr| {
            arr.iter()
                .filter_map(|f| {
                    Some(FolderView {
                        id: f.get("id").and_then(Value::as_str)?.to_string(),
                        name: f.get("name").and_then(Value::as_str)?.to_string(),
                    })
                })
                .collect()
        })
        .unwrap_or_default()
}

/// Cria uma pasta nova (id no formato do oficial).
pub fn add_folder(raw: &mut Value, name: &str) {
    if !raw["folders"].is_array() {
        raw["folders"] = json!([]);
    }
    raw["folders"].as_array_mut().expect("folders é array (garantido acima)").push(json!({ "id": gen_id(), "name": name }));
}

/// Renomeia uma pasta.
pub fn rename_folder(raw: &mut Value, folder_id: &str, new_name: &str) -> bool {
    let Some(arr) = raw.get_mut("folders").and_then(Value::as_array_mut) else {
        return false;
    };
    for f in arr.iter_mut() {
        if f.get("id").and_then(Value::as_str) == Some(folder_id) {
            f["name"] = json!(new_name);
            return true;
        }
    }
    false
}

/// Remove uma pasta — itens dela ficam sem pasta (folderId = null).
pub fn delete_folder(raw: &mut Value, folder_id: &str) -> bool {
    let Some(arr) = raw.get_mut("folders").and_then(Value::as_array_mut) else {
        return false;
    };
    let before = arr.len();
    arr.retain(|f| f.get("id").and_then(Value::as_str) != Some(folder_id));
    if arr.len() < before {
        // Limpa folderId dos itens que apontavam pra ela
        if let Some(items) = raw.get_mut("items").and_then(Value::as_array_mut) {
            for it in items.iter_mut() {
                if it.get("folderId").and_then(Value::as_str) == Some(folder_id) {
                    it["folderId"] = Value::Null;
                }
            }
        }
        true
    } else {
        false
    }
}

/// Atribui um item a uma pasta (folder_id pode ser None = sem pasta).
pub fn set_item_folder(raw: &mut Value, item_id: &str, folder_id: Option<&str>) -> bool {
    let Some(items) = raw.get_mut("items").and_then(Value::as_array_mut) else {
        return false;
    };
    for it in items.iter_mut() {
        if it.get("id").and_then(Value::as_str) == Some(item_id) {
            it["folderId"] = match folder_id {
                Some(id) => json!(id),
                None => Value::Null,
            };
            it["updatedAt"] = json!(now_ms());
            return true;
        }
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Form mínimo pra maioria dos testes (sem url/notas/totp).
    fn form<'a>(name: &'a str, username: &'a str, password: &'a str) -> LoginForm<'a> {
        LoginForm { name, username, password, totp: "", url: "", notes: "" }
    }

    fn vault_com_item() -> Value {
        let mut v: Value = serde_json::from_str(EMPTY_VAULT).unwrap();
        add_login(&mut v, form("Gmail", "joao", "secreta"));
        v
    }

    #[test]
    fn add_login_popula_items() {
        let v = vault_com_item();
        let items = v["items"].as_array().unwrap();
        assert_eq!(items.len(), 1);
        assert_eq!(items[0]["kind"], "login");
        assert_eq!(items[0]["name"], "Gmail");
        assert_eq!(items[0]["login"]["username"], "joao");
        // campos camelCase exatamente como o oficial espera
        assert!(items[0].get("createdAt").is_some());
        assert!(items[0].get("deletedAt").is_some());
        assert!(items[0].get("folderId").is_some());
    }

    #[test]
    fn view_esconde_lixeira() {
        let mut v = vault_com_item();
        v["items"][0]["deletedAt"] = json!(1700000000000i64);
        assert!(items_view(&v).is_empty());
    }

    /// `deletedAt = 0` é falsy no JS do oficial e o resto do app o trata como FORA da lixeira: o item tem de
    /// aparecer na lista e fora da lixeira — a mesma regra do lote (`batch::is_trashed`).
    #[test]
    fn deleted_at_zero_aparece_na_lista_e_nao_na_lixeira() {
        let v: Value = serde_json::from_str(
            r#"{"version":1,"folders":[],"items":[
                {"id":"zero","kind":"note","name":"Zero","deletedAt":0},
                {"id":"trashed","kind":"note","name":"Na lixeira","deletedAt":1700000000000},
                {"id":"live","kind":"note","name":"Vivo","deletedAt":null}
            ]}"#,
        )
        .unwrap();

        let view = items_view(&v);
        let ids: Vec<&str> = view.iter().map(|item| item.id.as_str()).collect();
        assert_eq!(ids, ["zero", "live"], "o 0 não é lixeira: aparece na lista");
        let trash = trashed_items(&v);
        let trashed: Vec<&str> = trash.iter().map(|row| row.id.as_str()).collect();
        assert_eq!(trashed, ["trashed"], "só o que tem data de verdade entra na lixeira");
    }

    #[test]
    fn login_triple_devolve_as_credenciais() {
        let v = vault_com_item();
        let id = v["items"][0]["id"].as_str().unwrap().to_string();
        assert_eq!(login_triple(&v, &id), Some(("joao".into(), "secreta".into(), "".into())));
        assert_eq!(login_triple(&v, "id-que-nao-existe"), None);
    }

    #[test]
    fn add_preserva_campos_desconhecidos() {
        // Um cofre REAL tem anexos/campos custom que o app não conhece —
        // acrescentar item não pode derrubar nada disso.
        let mut v: Value = serde_json::from_str(
            r#"{"version":1,"folders":[{"id":"f1","name":"Pessoal"}],
               "items":[{"id":"x","kind":"note","name":"nota",
                          "customFields":[{"id":"c1","name":"k","value":"v","hidden":false},
                                          {"id":"c2","name":"k2","value":"v2","hidden":true}],
                          "attachments":[{"id":"a1","name":"f.png","size":10,"mime":"image/png","dataB64":"aGk="}]}]}"#,
        )
        .unwrap();
        add_login(&mut v, form("Novo", "u", "p"));
        assert_eq!(v["folders"][0]["name"], "Pessoal");
        assert_eq!(v["items"].as_array().unwrap().len(), 2);
        assert_eq!(v["items"][0]["customFields"].as_array().unwrap().len(), 2);
        assert_eq!(v["items"][0]["attachments"][0]["mime"], "image/png");
        assert_eq!(v["items"][1]["login"]["password"], "p");
    }

    #[test]
    fn view_mostra_totp_quando_existe() {
        let mut v = vault_com_item();
        v["items"][0]["login"]["totp"] = json!("JBSWY3DPEHPK3PXP");
        let items = items_view(&v);
        assert_eq!(items[0].totp.as_deref(), Some("JBSWY3DPEHPK3PXP"));

        let mut v2 = vault_com_item(); // sem totp
        v2["items"][0]["login"]["totp"] = json!("");
        assert!(items_view(&v2)[0].totp.is_none());
    }

    #[test]
    fn edita_login_sem_derrubar_o_resto() {
        let mut v: Value = serde_json::from_str(
            r#"{"version":1,"folders":[{"id":"f1","name":"Pessoal"}],
               "items":[{"id":"x","kind":"login","name":"Antigo","favorite":true,"folderId":"f1",
                          "notes":"keep me","createdAt":1,"updatedAt":1,"deletedAt":null,
                          "login":{"username":"u","password":"p","uris":["https://a"],"totp":""}}]}"#,
        )
        .unwrap();
        assert!(edit_login(
            &mut v,
            "x",
            LoginForm {
                name: "Novo",
                username: "user2",
                password: "pass2",
                totp: "JBSWY3DPEHPK3PXP",
                // o form devolve a URL que carregou — a lista não muda
                url: "https://a",
                notes: "keep me",
            }
        ));
        let it = &v["items"][0];
        assert_eq!(it["name"], "Novo");
        assert_eq!(it["login"]["username"], "user2");
        assert_eq!(it["login"]["password"], "pass2");
        assert_eq!(it["login"]["totp"], "JBSWY3DPEHPK3PXP");
        // o que NÃO foi editado atravessa intacto
        assert_eq!(it["favorite"], true);
        assert_eq!(it["folderId"], "f1");
        assert_eq!(it["notes"], "keep me");
        assert_eq!(it["login"]["uris"][0], "https://a");
        assert_eq!(v["folders"][0]["name"], "Pessoal");
        // id inexistente: false e nada muda (compara o item antes/depois —
        // sem segurar borrow imutável através do &mut)
        let antes = serde_json::to_string(&v["items"][0]).unwrap();
        assert!(!edit_login(&mut v, "não-existe", form("n", "u", "p")));
        let depois = serde_json::to_string(&v["items"][0]).unwrap();
        assert_eq!(antes, depois);
    }

    #[test]
    fn editar_url_troca_a_primeira_uri_e_preserva_as_outras() {
        // O form edita só uris[0]; o resto da lista (que o app não mostra)
        // continua onde estava — escrita conservadora.
        let mut v: Value = serde_json::from_str(
            r#"{"version":1,"folders":[],"items":[{"id":"x","kind":"login","name":"N",
               "favorite":false,"notes":"","login":{"username":"u","password":"p",
               "uris":["https://velho","https://segundo","https://terceiro"],"totp":""}}]}"#,
        )
        .unwrap();
        assert!(edit_login(&mut v, "x", LoginForm { url: "https://novo", ..form("N", "u", "p") }));
        assert_eq!(v["items"][0]["login"]["uris"], json!(["https://novo", "https://segundo", "https://terceiro"]));
    }

    #[test]
    fn editar_sem_url_deixa_a_lista_como_o_empty_item() {
        // uris ausente/vazia → [""], igual ao emptyItem do oficial.
        let mut v: Value = serde_json::from_str(
            r#"{"version":1,"folders":[],"items":[{"id":"x","kind":"login","name":"N",
               "favorite":false,"notes":"","login":{"username":"u","password":"p","uris":[],"totp":""}}]}"#,
        )
        .unwrap();
        assert!(edit_login(&mut v, "x", form("N", "u", "p")));
        assert_eq!(v["items"][0]["login"]["uris"], json!([""]));
    }

    #[test]
    fn editar_grava_as_notas() {
        let mut v: Value = serde_json::from_str(
            r#"{"version":1,"folders":[],"items":[{"id":"x","kind":"login","name":"Gmail",
               "favorite":false,"notes":"velho","updatedAt":1,"deletedAt":null,
               "passwordHistory":[{"password":"mais-velha","at":1}],
               "login":{"username":"joao","password":"secreta","uris":["https://a"],"totp":""}}]}"#,
        )
        .unwrap();
        assert!(edit_login(&mut v, "x", LoginForm { notes: "recado novo\nsegunda linha", ..form("Gmail", "joao", "secreta") }));
        assert_eq!(v["items"][0]["notes"], "recado novo\nsegunda linha");
        // editar nota/url não mexe no histórico (senha não mudou)
        assert_eq!(v["items"][0]["passwordHistory"], json!([{ "password": "mais-velha", "at": 1 }]));
    }

    #[test]
    fn add_login_com_url_e_notas() {
        let mut v: Value = serde_json::from_str(EMPTY_VAULT).unwrap();
        add_login(
            &mut v,
            LoginForm {
                name: "GitHub",
                username: "joao",
                password: "p",
                totp: "JBSWY3DPEHPK3PXP",
                url: "https://github.com",
                notes: "conta pessoal",
            },
        );
        let it = &v["items"][0];
        assert_eq!(it["login"]["uris"], json!(["https://github.com"]));
        assert_eq!(it["login"]["totp"], "JBSWY3DPEHPK3PXP");
        assert_eq!(it["notes"], "conta pessoal");
    }

    #[test]
    fn item_full_carrega_o_login_do_form() {
        let v: Value = serde_json::from_str(
            r#"{"version":1,"folders":[],"items":[{"id":"x","kind":"login","name":"N",
               "notes":"nota","login":{"username":"u","password":"p",
               "uris":["https://a","https://b"],"totp":"JBSWY3DPEHPK3PXP"}}]}"#,
        )
        .unwrap();
        let full = item_full(&v, "x").expect("item existe");
        assert_eq!(full.kind, ItemKind::Login);
        assert_eq!(full.notes, "nota");
        let login = full.login.expect("login carregado");
        assert_eq!(login.username, "u");
        assert_eq!(login.password, "p");
        assert_eq!(login.totp, "JBSWY3DPEHPK3PXP");
        assert_eq!(login.url, "https://a", "só a primeira URI");
        assert!(full.card.is_none() && full.identity.is_none());
        assert!(item_full(&v, "não-existe").is_none());
    }

    #[test]
    fn favoritar_mexe_no_updated_at_como_o_update_item_do_oficial() {
        let mut v = vault_com_item();
        let id = v["items"][0]["id"].as_str().unwrap().to_string();
        v["items"][0]["updatedAt"] = json!(1);
        assert!(set_favorite(&mut v, &id, true));
        assert_eq!(v["items"][0]["favorite"], true);
        assert_ne!(v["items"][0]["updatedAt"], json!(1), "updateItem grava updatedAt");
        assert!(set_favorite(&mut v, &id, false));
        assert_eq!(v["items"][0]["favorite"], false);
        assert!(!set_favorite(&mut v, "não-existe", true));
    }

    #[test]
    fn restaurar_zera_o_deleted_at_sem_mexer_no_resto() {
        let mut v = vault_com_item();
        let id = v["items"][0]["id"].as_str().unwrap().to_string();
        assert!(delete_login(&mut v, &id));
        let updated_antes = v["items"][0]["updatedAt"].clone();
        assert!(restore_item(&mut v, &id));
        assert_eq!(v["items"][0]["deletedAt"], Value::Null);
        assert_eq!(v["items"][0]["updatedAt"], updated_antes, "restoreItem não toca updatedAt");
        assert_eq!(items_view(&v).len(), 1, "volta pra lista");
        assert!(!restore_item(&mut v, "não-existe"));
    }

    #[test]
    fn excluir_de_vez_tira_do_array() {
        let mut v = vault_com_item();
        let id = v["items"][0]["id"].as_str().unwrap().to_string();
        assert!(delete_forever(&mut v, &id));
        assert!(v["items"].as_array().unwrap().is_empty());
        assert!(!delete_forever(&mut v, &id));
        assert_eq!(v["folders"], json!([]), "só o item sai");
    }

    #[test]
    fn lixeira_lista_so_os_deletados_com_a_data() {
        let mut v = vault_com_item();
        let id = v["items"][0]["id"].as_str().unwrap().to_string();
        assert!(trashed_items(&v).is_empty());
        v["items"][0]["deletedAt"] = json!(1_700_000_000_000i64);
        let rows = trashed_items(&v);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].id, id);
        assert_eq!(rows[0].name, "Gmail");
        assert_eq!(rows[0].deleted_at, 1_700_000_000_000);
        // deletedAt null (o oficial grava null ao restaurar) = fora da lixeira
        v["items"][0]["deletedAt"] = Value::Null;
        assert!(trashed_items(&v).is_empty());
    }

    #[test]
    fn editar_item_sem_login_cria_o_login() {
        // note/card não têm login; se o usuário "editar" um desses no app,
        // o login nasce com a forma completa do oficial.
        let mut v: Value = serde_json::from_str(
            r#"{"version":1,"folders":[],"items":[{"id":"n1","kind":"note","name":"nota",
               "favorite":false,"folderId":null,"notes":"","createdAt":1,"updatedAt":1,"deletedAt":null}]}"#,
        )
        .unwrap();
        assert!(edit_login(&mut v, "n1", form("nota", "u", "p")));
        assert_eq!(v["items"][0]["login"]["username"], "u");
        assert_eq!(v["items"][0]["login"]["uris"], json!([""]));
        assert_eq!(v["items"][0]["kind"], "note"); // classe intacta
    }

    #[test]
    fn exclusao_e_logica_e_some_da_view() {
        let mut v = vault_com_item();
        let id = v["items"][0]["id"].as_str().unwrap().to_string();
        // updatedAt original, pra provar que a lixeira NÃO o mexe (o
        // trashItem do oficial só toca em deletedAt).
        let updated_antes = v["items"][0]["updatedAt"].clone();
        assert!(delete_login(&mut v, &id));
        // linha continua no JSON (lixeira do oficial)...
        assert_eq!(v["items"].as_array().unwrap().len(), 1);
        assert!(v["items"][0]["deletedAt"].is_number());
        assert_eq!(v["items"][0]["updatedAt"], updated_antes);
        // ...mas fora da lista
        assert!(items_view(&v).is_empty());
        assert!(!delete_login(&mut v, "não-existe"));
    }

    #[test]
    fn id_igual_ao_randomuuid_do_oficial() {
        // 8-4-4-4-12, nibble de versão 4 e variante 8/9/a/b — o formato
        // exato de crypto.randomUUID() no TS.
        let id = gen_id();
        let part: Vec<&str> = id.split('-').collect();
        assert_eq!(part.iter().map(|p| p.len()).collect::<Vec<_>>(), vec![8, 4, 4, 4, 12]);
        assert!(id.chars().all(|c| c.is_ascii_hexdigit() || c == '-'));
        assert_eq!(&id[14..15], "4", "version nibble");
        assert!(['8', '9', 'a', 'b'].contains(&id.chars().nth(19).unwrap()), "variante RFC 4122");
        assert_ne!(gen_id(), gen_id());
    }

    #[test]
    fn editar_senha_guarda_a_anterior_no_historico() {
        // Mesma regra do updateItem do oficial: login + senha antiga não vazia
        // + senha mudou → {password, at} no topo, cap 20.
        let mut v: Value = serde_json::from_str(
            r#"{"version":1,"folders":[],"items":[{"id":"x","kind":"login","name":"N",
               "favorite":false,"folderId":null,"notes":"","createdAt":1,"updatedAt":1,"deletedAt":null,
               "login":{"username":"u","password":"velha","uris":[],"totp":""}}]}"#,
        )
        .unwrap();
        assert!(edit_login(&mut v, "x", form("N", "u", "nova1")));
        let hist = v["items"][0]["passwordHistory"].as_array().unwrap();
        assert_eq!(hist.len(), 1);
        assert_eq!(hist[0]["password"], "velha");
        assert!(hist[0]["at"].is_number());

        // Segunda troca: a "nova1" entra no TOPO, a "velha" desce.
        assert!(edit_login(&mut v, "x", form("N", "u", "nova2")));
        let hist = v["items"][0]["passwordHistory"].as_array().unwrap();
        assert_eq!(hist.len(), 2);
        assert_eq!(hist[0]["password"], "nova1");
        assert_eq!(hist[1]["password"], "velha");
    }

    #[test]
    fn editar_sem_trocar_senha_nao_cria_historico() {
        let mut v: Value = serde_json::from_str(
            r#"{"version":1,"folders":[],"items":[{"id":"x","kind":"login","name":"N",
               "favorite":false,"folderId":null,"notes":"","createdAt":1,"updatedAt":1,"deletedAt":null,
               "login":{"username":"u","password":"igual","uris":[],"totp":""}}]}"#,
        )
        .unwrap();
        assert!(edit_login(&mut v, "x", form("Outro nome", "u2", "igual")));
        assert!(v["items"][0].get("passwordHistory").is_none(), "senha não mudou → sem histórico (igual ao oficial)");
        assert_eq!(v["items"][0]["name"], "Outro nome");
    }

    #[test]
    fn item_de_nota_nao_carrega_login() {
        // O emptyItem("note") do oficial não tem `login` — os importadores
        // dependem disso pra nota não virar login sem senha.
        let item = make_item(NewItem { kind: "note", name: "Nota", notes: "texto", favorite: true, login: None, custom_fields: Vec::new() });
        assert_eq!(item["kind"], "note");
        assert_eq!(item["notes"], "texto");
        assert_eq!(item["favorite"], true);
        assert!(item.get("login").is_none());
        assert!(item.get("customFields").is_none());
    }

    #[test]
    fn anexo_entra_com_tamanho_decodificado() {
        let mut v = vault_com_item();
        let id = v["items"][0]["id"].as_str().unwrap().to_string();
        // "hi" em base64 → 2 bytes decodificados (o size é do conteúdo, não do b64)
        assert!(add_attachment(&mut v, &id, "f.txt", "text/plain", "aGk="));
        let atts = v["items"][0]["attachments"].as_array().unwrap();
        assert_eq!(atts.len(), 1);
        assert_eq!(atts[0]["name"], "f.txt");
        assert_eq!(atts[0]["mime"], "text/plain");
        assert_eq!(atts[0]["size"], 2);
        assert_eq!(atts[0]["dataB64"], "aGk=");
        // id no formato UUID do oficial
        let att_id = atts[0]["id"].as_str().unwrap();
        assert_eq!(att_id.len(), 36);
        assert_eq!(attachment_data(&v, &id, att_id), Some(("f.txt".into(), "aGk=".into())));
    }

    #[test]
    fn anexo_preserva_o_resto_do_cofre() {
        let mut v: Value = serde_json::from_str(
            r#"{"version":1,"folders":[{"id":"f1","name":"Pessoal"}],
               "items":[{"id":"x","kind":"login","name":"N","favorite":true,"folderId":"f1",
                          "notes":"keep","customFields":[{"id":"c1","name":"k","value":"v","hidden":false}],
                          "attachments":[{"id":"a1","name":"velho.png","size":3,"mime":"image/png","dataB64":"YWJj"}],
                          "login":{"username":"u","password":"p","uris":["https://a"],"totp":""}}]}"#,
        )
        .unwrap();
        assert!(add_attachment(&mut v, "x", "novo.txt", "text/plain", "aGk="));
        assert_eq!(v["folders"][0]["name"], "Pessoal");
        assert_eq!(v["items"][0]["notes"], "keep");
        assert_eq!(v["items"][0]["customFields"][0]["value"], "v");
        let atts = v["items"][0]["attachments"].as_array().unwrap();
        assert_eq!(atts.len(), 2, "anexo existente não pode ser derrubado");
        assert_eq!(atts[0]["name"], "velho.png");
        assert_eq!(atts[1]["name"], "novo.txt");
    }

    #[test]
    fn anexo_invalido_nao_grava_nada() {
        let mut v = vault_com_item();
        let id = v["items"][0]["id"].as_str().unwrap().to_string();
        let antes = serde_json::to_string(&v).unwrap();
        assert!(!add_attachment(&mut v, &id, "f", "text/plain", "não-é-base64!!"));
        assert!(!add_attachment(&mut v, "id-que-nao-existe", "f", "text/plain", "aGk="));
        assert_eq!(serde_json::to_string(&v).unwrap(), antes);
        assert_eq!(attachment_data(&v, &id, "a-nao-existe"), None);
        assert_eq!(attachment_data(&v, "id-que-nao-existe", "a1"), None);
    }

    #[test]
    fn push_items_acrescenta_o_lote() {
        let mut v = vault_com_item();
        let lote = vec![
            make_item(NewItem {
                kind: "login",
                name: "A",
                notes: "",
                favorite: false,
                login: Some(LoginFields { username: "u".into(), password: "p".into(), uris: vec!["https://a".into()], totp: String::new() }),
                custom_fields: vec![custom_field("pin", "1234", true)],
            }),
            make_item(NewItem { kind: "note", name: "B", notes: "", favorite: false, login: None, custom_fields: Vec::new() }),
        ];
        push_items(&mut v, lote);
        assert_eq!(v["items"].as_array().unwrap().len(), 3);
        assert_eq!(v["items"][1]["customFields"][0]["name"], "pin");
        assert_eq!(v["items"][1]["customFields"][0]["hidden"], true);
        assert_eq!(v["items"][2]["kind"], "note");
    }

    #[test]
    fn atomic_write_escreve_e_reescreve() {
        let dir = std::env::temp_dir()
            .join(format!("local-keys-aw-{}", std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("v.tkeys");
        atomic_write(&p, b"um").unwrap();
        assert_eq!(std::fs::read(&p).unwrap(), b"um");
        atomic_write(&p, b"dois").unwrap();
        assert_eq!(std::fs::read(&p).unwrap(), b"dois");
        assert_eq!(std::fs::read(dir.join("v.tkeys.bak")).unwrap(), b"um");
        std::fs::remove_dir_all(&dir).ok();
    }

    // ── pedido 022, fase A: os quatro tipos ─────────────────────────────

    #[test]
    fn add_card_grava_o_formato_fixo_do_cofre() {
        let mut v: Value = serde_json::from_str(EMPTY_VAULT).unwrap();
        add_card(
            &mut v,
            CardForm {
                name: "Nubank",
                cardholder: "João da Silva",
                brand: "Elo",
                // colado com espaços e traço: o cofre guarda só os dígitos
                number: "6362 9700-0045 7013",
                exp: "01/2027",
                code: "12a4",
                notes: "",
            },
        );
        let it = &v["items"][0];
        assert_eq!(it["kind"], "card");
        assert_eq!(it["name"], "Nubank");
        assert_eq!(it["card"]["cardholder"], "João da Silva");
        assert_eq!(it["card"]["brand"], "Elo");
        assert_eq!(it["card"]["number"], "6362970000457013", "número: só dígitos");
        assert_eq!(it["card"]["exp"], "01/2027", "MM/AAAA aceito");
        assert_eq!(it["card"]["code"], "124", "código: só dígitos");
        assert!(it.get("login").is_none(), "cartão não carrega login (o mesmo do oficial)");
    }

    #[test]
    fn edit_card_normaliza_e_preserva_o_resto() {
        let mut v: Value = serde_json::from_str(
            r#"{"version":1,"folders":[{"id":"f1","name":"Pessoal"}],
               "items":[{"id":"c1","kind":"card","name":"Velho","favorite":true,"folderId":"f1","notes":"keep",
                          "createdAt":1,"updatedAt":1,"deletedAt":null,
                          "card":{"cardholder":"João","brand":"Visa","number":"4111 1111 1111 1111","exp":"12/27","code":"123","chaveDesconhecida":9},
                          "customFields":[{"id":"cf1","name":"pin","value":"1234","hidden":true}]}]}"#,
        )
        .unwrap();
        assert!(edit_card(
            &mut v,
            "c1",
            CardForm {
                name: "Itaú",
                cardholder: "João",
                brand: "Mastercard",
                number: "5555 5555 5555 4444",
                exp: "0330",
                code: "9876",
                notes: "keep"
            }
        ));
        let it = &v["items"][0];
        assert_eq!(it["name"], "Itaú");
        assert_eq!(it["card"]["number"], "5555555555554444");
        assert_eq!(it["card"]["exp"], "03/30", "sem barra vira MM/AA");
        assert_eq!(it["card"]["code"], "9876");
        // o que não foi editado atravessa intacto
        assert_eq!(it["kind"], "card");
        assert_eq!(it["favorite"], true);
        assert_eq!(it["folderId"], "f1");
        assert_eq!(it["card"]["chaveDesconhecida"], 9, "chave desconhecida dentro de card fica");
        assert_eq!(it["customFields"][0]["name"], "pin");
        assert_ne!(it["updatedAt"], json!(1), "updateItem grava updatedAt");
        assert!(!edit_card(&mut v, "não-existe", CardForm { name: "x", cardholder: "", brand: "", number: "", exp: "", code: "", notes: "" }));
    }

    #[test]
    fn add_identity_grava_os_cinco_campos_sem_login() {
        let mut v: Value = serde_json::from_str(EMPTY_VAULT).unwrap();
        add_identity(
            &mut v,
            IdentityForm {
                name: "Passaporte João",
                first_name: "João",
                last_name: "Silva",
                email: "joao@example.com",
                phone: "+55 11 91234-5678",
                address: "Rua A, 123",
                notes: "",
            },
        );
        let it = &v["items"][0];
        assert_eq!(it["kind"], "identity");
        assert_eq!(it["identity"]["firstName"], "João");
        assert_eq!(it["identity"]["lastName"], "Silva");
        assert_eq!(it["identity"]["email"], "joao@example.com");
        assert_eq!(it["identity"]["phone"], "+55 11 91234-5678");
        assert_eq!(it["identity"]["address"], "Rua A, 123");
        assert!(it.get("login").is_none());
        assert!(it.get("card").is_none());

        let id = v["items"][0]["id"].as_str().unwrap().to_string();
        assert!(edit_identity(
            &mut v,
            &id,
            IdentityForm { name: "Passaporte João", first_name: "João", last_name: "Souza", email: "", phone: "", address: "", notes: "" }
        ));
        assert_eq!(v["items"][0]["identity"]["lastName"], "Souza");
        assert_eq!(v["items"][0]["identity"]["email"], "", "campo limpo grava vazio");
        // identidade que não existia (item corrompido) nasce no edit
        v["items"][0]["identity"] = Value::Null;
        assert!(edit_identity(&mut v, &id, IdentityForm { name: "x", first_name: "a", last_name: "", email: "", phone: "", address: "", notes: "" }));
        assert_eq!(v["items"][0]["identity"]["firstName"], "a");
    }

    #[test]
    fn add_nota_e_edita_sem_criar_outra_coisa() {
        let mut v: Value = serde_json::from_str(EMPTY_VAULT).unwrap();
        add_note(&mut v, NoteForm { name: "Receita", notes: "1 ovo\n2 colheres de açúcar" });
        let it = &v["items"][0];
        assert_eq!(it["kind"], "note");
        assert_eq!(it["notes"], "1 ovo\n2 colheres de açúcar");
        for key in ["login", "card", "identity", "customFields"] {
            assert!(it.get(key).is_none(), "nota não carrega {key}");
        }
        let id = it["id"].as_str().unwrap().to_string();
        assert!(edit_note(&mut v, &id, NoteForm { name: "Receita do bolo", notes: "só o texto" }));
        assert_eq!(v["items"][0]["name"], "Receita do bolo");
        assert_eq!(v["items"][0]["notes"], "só o texto");
    }

    #[test]
    fn resumo_da_lista_por_tipo() {
        let v: Value = serde_json::from_str(
            r#"{"version":1,"folders":[],"items":[
               {"id":"l1","kind":"login","name":"Gmail","favorite":false,"deletedAt":null,
                "login":{"username":"joao","password":"p","uris":[],"totp":""}},
               {"id":"l2","kind":"login","name":"Sem usuário","favorite":false,"deletedAt":null,
                "login":{"username":"","password":"p","uris":[],"totp":""}},
               {"id":"c1","kind":"card","name":"Nubank","favorite":false,"deletedAt":null,
                "card":{"cardholder":"João","brand":"Elo","number":"6362970000457013","exp":"01/2027","code":"123"}},
               {"id":"c2","kind":"card","name":"Velho","favorite":false,"deletedAt":null,
                "card":{"cardholder":"João","brand":"","number":"123","exp":"","code":""}},
               {"id":"i1","kind":"identity","name":"Eu","favorite":false,"deletedAt":null,
                "identity":{"firstName":"João","lastName":"Silva","email":"joao@example.com","phone":"","address":""}},
               {"id":"i2","kind":"identity","name":"Contato","favorite":false,"deletedAt":null,
                "identity":{"firstName":"","lastName":"","email":"maria@example.com","phone":"","address":""}},
               {"id":"n1","kind":"note","name":"Lista","favorite":false,"deletedAt":null,
                "notes":"\n   \ncomprar pão\nlevar o note"}]}"#,
        )
        .unwrap();
        let resumo = |id: &str| items_view(&v).iter().find(|i| i.id == id).and_then(|i| i.summary.clone());
        assert_eq!(resumo("l1").as_deref(), Some("joao"));
        assert_eq!(resumo("l2"), None, "login sem usuário: sem segunda linha");
        assert_eq!(resumo("c1").as_deref(), Some("Elo  •••• 7013"));
        assert_eq!(resumo("c2").as_deref(), Some("João"), "sem bandeira nem 4 dígitos: o titular");
        assert_eq!(resumo("i1").as_deref(), Some("João Silva"));
        assert_eq!(resumo("i2").as_deref(), Some("maria@example.com"), "sem nome: o e-mail");
        assert_eq!(resumo("n1").as_deref(), Some("comprar pão"), "a primeira linha não vazia");
        // o kind de cada linha
        let kind = |id: &str| items_view(&v).iter().find(|i| i.id == id).map(|i| i.kind);
        assert_eq!(kind("l1"), Some(ItemKind::Login));
        assert_eq!(kind("c1"), Some(ItemKind::Card));
        assert_eq!(kind("i1"), Some(ItemKind::Identity));
        assert_eq!(kind("n1"), Some(ItemKind::Note));
    }

    #[test]
    fn campos_personalizados_por_id_do_item() {
        let mut v: Value = serde_json::from_str(
            r#"{"version":1,"folders":[],"items":[
               {"id":"x","kind":"login","name":"N","favorite":false,"deletedAt":null,
                "login":{"username":"u","password":"p","uris":[],"totp":""},
                "customFields":[{"id":"cf1","name":"pin","value":"1234","hidden":true,"extra":"fica"}]},
               {"id":"y","kind":"note","name":"M","favorite":false,"deletedAt":null,"notes":""}]}"#,
        )
        .unwrap();
        // ler
        let campos = custom_fields(&v, "x");
        assert_eq!(campos.len(), 1);
        assert_eq!(campos[0].name, "pin");
        assert!(campos[0].hidden);
        assert!(custom_fields(&v, "y").is_empty());
        // gravar: edita o existente (id mantido, chave desconhecida preservada) e acrescenta um novo
        let novos = vec![
            CustomFieldView { id: "cf1".into(), name: "PIN".into(), value: "4321".into(), hidden: false },
            CustomFieldView { id: String::new(), name: "recuperacao".into(), value: "código".into(), hidden: true },
        ];
        assert!(set_custom_fields(&mut v, "x", &novos));
        let arr = v["items"][0]["customFields"].as_array().unwrap();
        assert_eq!(arr.len(), 2);
        assert_eq!(arr[0]["id"], "cf1");
        assert_eq!(arr[0]["name"], "PIN");
        assert_eq!(arr[0]["hidden"], false);
        assert_eq!(arr[0]["extra"], "fica", "chave desconhecida do campo atravessa");
        let novo_id = arr[1]["id"].as_str().unwrap();
        assert_eq!(novo_id.len(), 36, "campo novo ganha id do formato do oficial");
        assert_eq!(arr[1]["value"], "código");
        // remover todos num item que TINHA: vira lista vazia
        assert!(set_custom_fields(&mut v, "x", &[]));
        assert_eq!(v["items"][0]["customFields"], json!([]));
        // item que NÃO TINHA e lista vazia: nem cria a chave
        assert!(set_custom_fields(&mut v, "y", &[]));
        assert!(v["items"][1].get("customFields").is_none());
        // id inexistente: false
        assert!(!set_custom_fields(&mut v, "não-existe", &novos));
    }

    #[test]
    fn item_full_carrega_cartao_identidade_e_nota() {
        let v: Value = serde_json::from_str(
            r#"{"version":1,"folders":[],"items":[
               {"id":"c","kind":"card","name":"Visa","favorite":false,"deletedAt":null,
                "card":{"cardholder":"João","brand":"Visa","number":"4111111111111111","exp":"12/27","code":"123"}},
               {"id":"i","kind":"identity","name":"Eu","favorite":false,"deletedAt":null,
                "identity":{"firstName":"João","lastName":"Silva","email":"j@e.com","phone":"11","address":"Rua A"}},
               {"id":"n","kind":"note","name":"Nota","favorite":false,"deletedAt":null,"notes":"texto",
                "customFields":[{"id":"cf","name":"k","value":"v","hidden":false}]}]}"#,
        )
        .unwrap();
        let card = item_full(&v, "c").expect("cartão");
        assert_eq!(card.kind, ItemKind::Card);
        let c = card.card.expect("campos do cartão");
        assert_eq!(c.number, "4111111111111111");
        assert!(card.login.is_none() && card.identity.is_none());
        let id = item_full(&v, "i").expect("identidade");
        let ident = id.identity.expect("campos da identidade");
        assert_eq!(ident.first_name, "João");
        assert_eq!(ident.address, "Rua A");
        let note = item_full(&v, "n").expect("nota");
        assert_eq!(note.kind, ItemKind::Note);
        assert_eq!(note.notes, "texto");
        assert_eq!(note.custom_fields.len(), 1);
        assert_eq!(note.custom_fields[0].value, "v");
        // kind desconhecido abre como nota (não perde nada: nota não tem campos próprios)
        let mut v2 = v.clone();
        v2["items"][0]["kind"] = json!("passkey-futuro");
        assert_eq!(item_full(&v2, "c").expect("abre").kind, ItemKind::Note);
    }
}
