//! A operação em LOTE da unidade 5 do pedido 027: apagar (para a lixeira) e mover de pasta vários itens de uma vez.
//!
//! Duas formas da mesma regra:
//! - sobre uma **lista de itens** ([`trash_items`], [`move_items`]) e sobre o **cofre** pelo id ([`trash_many`],
//!   [`move_many`]) — o desktop usa as do cofre; a ponte UniFFI do Android usa as da lista, porque mandar o cofre
//!   inteiro para a ponte custa caro em memória num cofre grande;
//! - e as mesmas duas com o array em JSON ([`trash_items_json`], [`move_items_json`]), para a ponte não ganhar
//!   `serde_json` como dependência própria.
//!
//! Decisões da regra:
//! - **tudo ou nada**: o lote valida TODOS os itens antes de tocar em qualquer um. Um item sem `id`, um id que não
//!   existe no cofre, um item que já está na lixeira (apagar) ou uma pasta de destino que não existe (mover) e nada
//!   muda — nem nos outros itens do lote;
//! - um só `now` para o lote inteiro: quem chama carimba uma vez (cada gravação recifra o cofre; N gravações seriam
//!   proibitivas) e a regra não lê o relógio;
//! - apagar é mandar para a **lixeira** (`deletedAt = now`, recuperável) e carimba `updatedAt`; mover de pasta mexer
//!   `folderId` e `updatedAt` só nos itens que mudaram de fato;
//! - mover para "sem pasta" (`folder_id = None`) é uma operação válida como qualquer outra;
//! - id repetido na lista do lote conta uma vez só (a seleção é um conjunto; repetir não pode carimbar duas vezes).

use std::collections::HashSet;

use serde_json::Value;

use crate::vault;

/// Por que o lote não aconteceu. Mensagens em inglês (diagnóstico; a UI fala pelo i18n dela).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum BatchError {
    /// Um item do lote não é um objeto com `id` em texto.
    MissingId,
    /// Um dos ids do lote não existe no cofre.
    UnknownId { id: String },
    /// Um dos itens do lote já está na lixeira — apagar de novo não é operação.
    AlreadyTrashed { id: String },
    /// A pasta de destino não existe no cofre.
    UnknownFolder { id: String },
    /// O cofre bruto não tem `items` (ou `items` não é uma lista).
    MalformedVault,
    /// A entrada em JSON não é uma lista de itens (só no caminho [`trash_items_json`]/[`move_items_json`]).
    InvalidJson(String),
}

impl std::fmt::Display for BatchError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::MissingId => write!(f, "item has no `id`"),
            Self::UnknownId { id } => write!(f, "item `{id}` does not exist"),
            Self::AlreadyTrashed { id } => write!(f, "item `{id}` is already in the trash"),
            Self::UnknownFolder { id } => write!(f, "folder `{id}` does not exist"),
            Self::MalformedVault => write!(f, "vault has no `items` list"),
            Self::InvalidJson(error) => write!(f, "invalid items JSON: {error}"),
        }
    }
}

impl std::error::Error for BatchError {}

/// O `id` do item; item que não é objeto ou sem `id` em texto é erro.
fn item_id(item: &Value) -> Result<&str, BatchError> {
    item.get("id").and_then(Value::as_str).ok_or(BatchError::MissingId)
}

/// Na lixeira? `deletedAt` presente e não nulo (mesma regra da lista do desktop, que esconde a lixeira).
fn is_trashed(item: &Value) -> bool {
    item.get("deletedAt").is_some_and(|deleted_at| !deleted_at.is_null())
}

/// O item já está no destino? `None` = "sem pasta", que é o mesmo que `folderId` ausente ou nulo.
fn already_in(item: &Value, folder_id: Option<&str>) -> bool {
    match folder_id {
        Some(target) => item.get("folderId").and_then(Value::as_str) == Some(target),
        None => item.get("folderId").is_none_or(Value::is_null),
    }
}

/// Muda a pasta do item e carimba `updatedAt` — só nos itens que estavam em outro lugar.
fn set_folder(item: &mut Value, folder_id: Option<&str>, now: u64) -> bool {
    if already_in(item, folder_id) {
        return false;
    }
    item["folderId"] = folder_id.map_or(Value::Null, |id| Value::String(id.to_string()));
    item["updatedAt"] = serde_json::json!(now);
    true
}

/// Os índices dos itens do cofre na ordem pedida, sem repetir id. Id que não existe → erro (e nada é mutado: quem
/// chama só toca nos itens depois do `Ok`).
fn indices_of(items: &[Value], ids: &[String]) -> Result<Vec<usize>, BatchError> {
    let mut seen = HashSet::new();
    let mut indices = Vec::new();
    for id in ids {
        if !seen.insert(id.as_str()) {
            continue;
        }
        let index = items
            .iter()
            .position(|item| item.get("id").and_then(Value::as_str) == Some(id.as_str()))
            .ok_or_else(|| BatchError::UnknownId { id: id.clone() })?;
        indices.push(index);
    }
    Ok(indices)
}

/// Os itens de um array JSON, ou [`BatchError::InvalidJson`].
fn parse_items(items_json: &str) -> Result<Vec<Value>, BatchError> {
    match serde_json::from_str::<Value>(items_json).map_err(|error| BatchError::InvalidJson(error.to_string()))? {
        Value::Array(items) => Ok(items),
        _ => Err(BatchError::InvalidJson("items is not a JSON array".into())),
    }
}

/// Manda os itens para a lixeira: `deletedAt` e `updatedAt` = `now` em todos. Devolve quantos mudaram (todos).
///
/// # Errors
/// [`BatchError::MissingId`] (item que não é objeto com id) ou [`BatchError::AlreadyTrashed`] — e aí a lista fica
/// inteira como estava.
pub fn trash_items(items: &mut [Value], now: u64) -> Result<usize, BatchError> {
    for item in items.iter() {
        let id = item_id(item)?;
        if is_trashed(item) {
            return Err(BatchError::AlreadyTrashed { id: id.to_string() });
        }
    }
    let changed = items.len();
    for item in items.iter_mut() {
        item["deletedAt"] = serde_json::json!(now);
        item["updatedAt"] = serde_json::json!(now);
    }
    Ok(changed)
}

/// Põe os itens na pasta `folder_id` (`None` = sem pasta). Devolve quantos mudaram de fato — item que já estava no
/// destino não ganha carimbo nem entra na contagem.
///
/// # Errors
/// [`BatchError::MissingId`] — e aí a lista fica inteira como estava.
pub fn move_items(items: &mut [Value], folder_id: Option<&str>, now: u64) -> Result<usize, BatchError> {
    for item in items.iter() {
        item_id(item)?;
    }
    let mut changed = 0;
    for item in items.iter_mut() {
        if set_folder(item, folder_id, now) {
            changed += 1;
        }
    }
    Ok(changed)
}

/// [`trash_items`] no cofre bruto, pelos ids. Id inexistente → erro e o cofre não muda.
///
/// # Errors
/// [`BatchError::MalformedVault`], [`BatchError::UnknownId`] ou [`BatchError::AlreadyTrashed`].
pub fn trash_many(raw: &mut Value, ids: &[String], now: u64) -> Result<usize, BatchError> {
    let items = raw.get_mut("items").and_then(Value::as_array_mut).ok_or(BatchError::MalformedVault)?;
    let indices = indices_of(items, ids)?;
    for index in &indices {
        let item = &items[*index];
        let id = item_id(item)?;
        if is_trashed(item) {
            return Err(BatchError::AlreadyTrashed { id: id.to_string() });
        }
    }
    let changed = indices.len();
    for index in indices {
        items[index]["deletedAt"] = serde_json::json!(now);
        items[index]["updatedAt"] = serde_json::json!(now);
    }
    Ok(changed)
}

/// [`move_items`] no cofre bruto, pelos ids. Pasta de destino inexistente ou id inexistente → erro e o cofre não
/// muda.
///
/// # Errors
/// [`BatchError::UnknownFolder`], [`BatchError::MalformedVault`] ou [`BatchError::UnknownId`].
pub fn move_many(raw: &mut Value, ids: &[String], folder_id: Option<&str>, now: u64) -> Result<usize, BatchError> {
    if let Some(target) = folder_id
        && !vault::folders(raw).iter().any(|folder| folder.id == target)
    {
        return Err(BatchError::UnknownFolder { id: target.to_string() });
    }
    let items = raw.get_mut("items").and_then(Value::as_array_mut).ok_or(BatchError::MalformedVault)?;
    let indices = indices_of(items, ids)?;
    let mut changed = 0;
    for index in indices {
        if set_folder(&mut items[index], folder_id, now) {
            changed += 1;
        }
    }
    Ok(changed)
}

/// [`trash_items`] com o array em JSON — o caminho da ponte UniFFI. Devolve o array atualizado.
///
/// # Errors
/// [`BatchError::InvalidJson`] ou qualquer erro de [`trash_items`].
pub fn trash_items_json(items_json: &str, now: u64) -> Result<String, BatchError> {
    let mut items = parse_items(items_json)?;
    trash_items(&mut items, now)?;
    serde_json::to_string(&items).map_err(|error| BatchError::InvalidJson(error.to_string()))
}

/// [`move_items`] com o array em JSON — o caminho da ponte UniFFI. Devolve o array atualizado.
///
/// # Errors
/// [`BatchError::InvalidJson`] ou qualquer erro de [`move_items`].
pub fn move_items_json(items_json: &str, folder_id: Option<&str>, now: u64) -> Result<String, BatchError> {
    let mut items = parse_items(items_json)?;
    move_items(&mut items, folder_id, now)?;
    serde_json::to_string(&items).map_err(|error| BatchError::InvalidJson(error.to_string()))
}
