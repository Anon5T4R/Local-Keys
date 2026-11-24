//! Mudança do TIPO (`kind`) de um item do cofre — a regra da unidade 4 do pedido 027.
//!
//! Nada some em silêncio: todo objeto tipado (`login`, `card`, `identity`) que não seja o do tipo novo vira campo
//! extra — visível ou oculto conforme o campo — e é removido do item; o `kind` muda e TODO o resto do item (nome,
//! notas, pasta, favorito, datas, lixeira, histórico de senha, anexos, passkeys, chaves desconhecidas) atravessa
//! idêntico. O objeto do tipo novo, se já existir, fica como está.
//!
//! O formato `.tkeys` não ganha chave nenhuma: a conversão usa só `kind`, os objetos tipados e `customFields`. O
//! núcleo não tem i18n — quem chama passa os rótulos no idioma do usuário ([`FieldLabels`]); rótulo vazio cai no
//! nome padrão em inglês daqui.

use serde_json::{Map, Value};

use crate::vault::{ItemKind, custom_field};

/// Os rótulos dos campos gerados na conversão, no idioma do usuário. Rótulo vazio (ou só espaço) cai no padrão em
/// inglês de [`FieldLabels::english`].
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FieldLabels {
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

impl FieldLabels {
    /// Os rótulos padrão em inglês — o que a regra usa quando o chamador não tem um rótulo.
    pub fn english() -> Self {
        Self {
            username: "Username".into(),
            password: "Password".into(),
            website: "Website".into(),
            totp: "TOTP".into(),
            cardholder: "Cardholder".into(),
            brand: "Brand".into(),
            card_number: "Card number".into(),
            expiry: "Expiry".into(),
            security_code: "Security code".into(),
            first_name: "First name".into(),
            last_name: "Last name".into(),
            email: "Email".into(),
            phone: "Phone".into(),
            address: "Address".into(),
        }
    }
}

impl Default for FieldLabels {
    fn default() -> Self {
        Self::english()
    }
}

/// Por que a conversão não aconteceu. Mensagens em inglês (diagnóstico; a UI fala pelo i18n dela).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ChangeKindError {
    /// O item não é um objeto JSON.
    NotAnObject,
    /// `kind` ausente ou fora dos quatro tipos que o cofre conhece.
    UnknownKind,
    /// O objeto tipado tem uma chave que o núcleo não sabe converter, e ela não está vazia.
    UnknownField { object: &'static str, field: String },
    /// Uma chave conhecida do objeto tipado veio com um tipo que o schema não prevê (ex.: `uris` sem ser lista).
    UnexpectedValue { object: &'static str, field: &'static str },
    /// `customFields` existe mas não é uma lista.
    MalformedCustomFields,
    /// O JSON do item não parseia (só no caminho [`change_kind_json`]).
    InvalidJson(String),
}

impl std::fmt::Display for ChangeKindError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::NotAnObject => write!(f, "item is not a JSON object"),
            Self::UnknownKind => write!(f, "unknown item kind"),
            Self::UnknownField { object, field } => write!(f, "cannot convert `{object}.{field}`: unknown field"),
            Self::UnexpectedValue { object, field } => write!(f, "cannot convert `{object}.{field}`: unexpected value"),
            Self::MalformedCustomFields => write!(f, "item customFields is not a list"),
            Self::InvalidJson(error) => write!(f, "invalid item JSON: {error}"),
        }
    }
}

impl std::error::Error for ChangeKindError {}

/// Como os valores de um objeto tipado viram campos extras.
type FieldGenerator = fn(&Map<String, Value>, &FieldLabels) -> Result<Vec<Value>, ChangeKindError>;

/// Um objeto tipado do cofre: a chave no item, o `kind` que ele representa, as chaves que o schema conhece e como
/// os valores viram campos extras.
struct TypedObject {
    key: &'static str,
    kind: ItemKind,
    known: &'static [&'static str],
    fields: FieldGenerator,
}

const LOGIN_KEYS: &[&str] = &["username", "password", "uris", "totp"];
const CARD_KEYS: &[&str] = &["cardholder", "brand", "number", "exp", "code"];
const IDENTITY_KEYS: &[&str] = &["firstName", "lastName", "email", "phone", "address"];

const TYPED_OBJECTS: &[TypedObject] = &[
    TypedObject { key: "login", kind: ItemKind::Login, known: LOGIN_KEYS, fields: login_fields },
    TypedObject { key: "card", kind: ItemKind::Card, known: CARD_KEYS, fields: card_fields },
    TypedObject { key: "identity", kind: ItemKind::Identity, known: IDENTITY_KEYS, fields: identity_fields },
];

/// O rótulo do chamador, ou o padrão em inglês quando ele veio vazio.
fn label<'a>(given: &'a str, fallback: &'static str) -> &'a str {
    let given = given.trim();
    if given.is_empty() { fallback } else { given }
}

/// Campo extra a partir de um valor do objeto tipado — valor vazio não gera campo.
fn push_field(fields: &mut Vec<Value>, name: &str, value: &str, hidden: bool) {
    if !value.trim().is_empty() {
        fields.push(custom_field(name, value, hidden));
    }
}

/// O texto de uma chave conhecida do objeto tipado; ausente/`null` → vazio. Outro tipo é erro: o núcleo não
/// converteria sem inventar (e o valor não pode sumir).
fn text(object: &Map<String, Value>, object_key: &'static str, field: &'static str) -> Result<String, ChangeKindError> {
    match object.get(field) {
        None | Some(Value::Null) => Ok(String::new()),
        Some(Value::String(value)) => Ok(value.clone()),
        Some(_) => Err(ChangeKindError::UnexpectedValue { object: object_key, field }),
    }
}

/// As `uris` do login, na ordem do arquivo; ausente/`null` → vazia.
fn uris(object: &Map<String, Value>) -> Result<Vec<String>, ChangeKindError> {
    match object.get("uris") {
        None | Some(Value::Null) => Ok(Vec::new()),
        Some(Value::Array(entries)) => entries
            .iter()
            .map(|entry| match entry {
                Value::String(uri) => Ok(uri.clone()),
                _ => Err(ChangeKindError::UnexpectedValue { object: "login", field: "uris" }),
            })
            .collect(),
        Some(_) => Err(ChangeKindError::UnexpectedValue { object: "login", field: "uris" }),
    }
}

fn login_fields(object: &Map<String, Value>, labels: &FieldLabels) -> Result<Vec<Value>, ChangeKindError> {
    let mut fields = Vec::new();
    push_field(&mut fields, label(&labels.username, "Username"), &text(object, "login", "username")?, false);
    push_field(&mut fields, label(&labels.password, "Password"), &text(object, "login", "password")?, true);
    let website = label(&labels.website, "Website");
    for (index, uri) in uris(object)?.iter().filter(|uri| !uri.trim().is_empty()).enumerate() {
        let name = if index == 0 { website.to_string() } else { format!("{website} {}", index + 1) };
        push_field(&mut fields, &name, uri, false);
    }
    push_field(&mut fields, label(&labels.totp, "TOTP"), &text(object, "login", "totp")?, true);
    Ok(fields)
}

fn card_fields(object: &Map<String, Value>, labels: &FieldLabels) -> Result<Vec<Value>, ChangeKindError> {
    let mut fields = Vec::new();
    push_field(&mut fields, label(&labels.cardholder, "Cardholder"), &text(object, "card", "cardholder")?, false);
    push_field(&mut fields, label(&labels.brand, "Brand"), &text(object, "card", "brand")?, false);
    push_field(&mut fields, label(&labels.card_number, "Card number"), &text(object, "card", "number")?, true);
    push_field(&mut fields, label(&labels.expiry, "Expiry"), &text(object, "card", "exp")?, false);
    push_field(&mut fields, label(&labels.security_code, "Security code"), &text(object, "card", "code")?, true);
    Ok(fields)
}

fn identity_fields(object: &Map<String, Value>, labels: &FieldLabels) -> Result<Vec<Value>, ChangeKindError> {
    let mut fields = Vec::new();
    push_field(&mut fields, label(&labels.first_name, "First name"), &text(object, "identity", "firstName")?, false);
    push_field(&mut fields, label(&labels.last_name, "Last name"), &text(object, "identity", "lastName")?, false);
    push_field(&mut fields, label(&labels.email, "Email"), &text(object, "identity", "email")?, false);
    push_field(&mut fields, label(&labels.phone, "Phone"), &text(object, "identity", "phone")?, false);
    push_field(&mut fields, label(&labels.address, "Address"), &text(object, "identity", "address")?, false);
    Ok(fields)
}

/// Um valor que não representa dado nenhum: `null`, string só de espaço, lista/objeto vazios. Chave desconhecida
/// assim não bloqueia a conversão (nada se perde ao descartá-la).
fn is_empty(value: &Value) -> bool {
    match value {
        Value::Null => true,
        Value::String(text) => text.trim().is_empty(),
        Value::Array(items) => items.is_empty(),
        Value::Object(fields) => fields.is_empty(),
        Value::Bool(_) | Value::Number(_) => false,
    }
}

/// Converte UM item (o valor do item no cofre, não o cofre) para `new_kind` e devolve o item convertido.
///
/// # Errors
/// [`ChangeKindError`] — e aí o item do chamador fica intacto: nada é mutado antes do `Ok`.
pub fn change_kind(item: &Value, new_kind: ItemKind, labels: &FieldLabels) -> Result<Value, ChangeKindError> {
    let Some(source) = item.as_object() else {
        return Err(ChangeKindError::NotAnObject);
    };
    let current = source.get("kind").and_then(Value::as_str).and_then(ItemKind::from_wire).ok_or(ChangeKindError::UnknownKind)?;
    if current == new_kind {
        return Ok(item.clone());
    }

    let mut converted = item.clone();
    let Some(map) = converted.as_object_mut() else {
        return Err(ChangeKindError::NotAnObject);
    };
    let mut generated = Vec::new();
    for object in TYPED_OBJECTS {
        if object.kind == new_kind {
            continue;
        }
        let Some(value) = source.get(object.key) else { continue };
        // Chave que não é objeto não é um objeto tipado: shape desconhecido atravessa como está.
        let Some(fields) = value.as_object() else { continue };
        if let Some((key, _)) = fields.iter().find(|(key, value)| !object.known.contains(&key.as_str()) && !is_empty(value)) {
            return Err(ChangeKindError::UnknownField { object: object.key, field: key.clone() });
        }
        generated.extend((object.fields)(fields, labels)?);
        map.remove(object.key);
    }

    if !generated.is_empty() {
        if !map.contains_key("customFields") {
            map.insert("customFields".into(), Value::Array(generated));
        } else if let Some(Value::Array(existing)) = map.get_mut("customFields") {
            existing.extend(generated);
        } else {
            return Err(ChangeKindError::MalformedCustomFields);
        }
    }
    map.insert("kind".into(), Value::String(new_kind.wire().to_string()));
    Ok(converted)
}

/// Aplica a conversão no cofre bruto pelo id. Item inexistente → `Ok(false)` e o cofre não muda; erro da regra →
/// o cofre fica intacto.
///
/// # Errors
/// [`ChangeKindError`] — o item alvo não é tocado.
pub fn change_item_kind(raw: &mut Value, item_id: &str, new_kind: ItemKind, labels: &FieldLabels) -> Result<bool, ChangeKindError> {
    let Some(items) = raw.get_mut("items").and_then(Value::as_array_mut) else {
        return Ok(false);
    };
    let Some(slot) = items.iter_mut().find(|item| item.get("id").and_then(Value::as_str) == Some(item_id)) else {
        return Ok(false);
    };
    let converted = change_kind(slot, new_kind, labels)?;
    *slot = converted;
    Ok(true)
}

/// A mesma regra com o item em JSON — o caminho da ponte UniFFI. O núcleo parseia para a ponte não ganhar
/// `serde_json` como dependência própria.
///
/// # Errors
/// [`ChangeKindError`] — JSON inválido, item que não é objeto ou qualquer erro de [`change_kind`].
pub fn change_kind_json(item_json: &str, new_kind: &str, labels: &FieldLabels) -> Result<String, ChangeKindError> {
    let item: Value = serde_json::from_str(item_json).map_err(|error| ChangeKindError::InvalidJson(error.to_string()))?;
    let new_kind = ItemKind::from_wire(new_kind).ok_or(ChangeKindError::UnknownKind)?;
    let converted = change_kind(&item, new_kind, labels)?;
    serde_json::to_string(&converted).map_err(|error| ChangeKindError::InvalidJson(error.to_string()))
}
