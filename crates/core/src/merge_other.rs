//! Junta um cofre **DIFERENTE** no cofre aberto (unidade 7 do pedido 027): "juntar outro `.tkeys`".
//!
//! Não confundir com [`crate::merge`]: aquele é a sincronização de **duas cópias do mesmo cofre** (resolve o id a
//! favor do mais novo e grava nos dois arquivos). Aqui os cofres são distintos: nada de "mais novo"; o outro cofre é
//! **somente leitura** e o arquivo dele fica intocado.
//!
//! Decisões da regra:
//! - item do outro com id que **não existe** no aberto entra **inteiro**, com o mesmo id (anexos, passkeys, histórico
//!   e chaves desconhecidas vêm juntos);
//! - mesmo id e **conteúdo igual** → nada a fazer (`already_present`);
//! - mesmo id e **conteúdo diferente** → ficam os dois: o que entra ganha um **id novo** (o do aberto não muda) e o
//!   item vai inteiro; se o conteúdo do outro já existe no aberto sob OUTRO id (a duplicata de uma passada anterior),
//!   conta como `already_present` — é o que torna a junção idempotente;
//! - item na lixeira do outro entra como está (na lixeira);
//! - pastas: mesmo id → usa a do aberto; id diferente com o **mesmo nome** (comparação exata) → une, os itens que
//!   apontavam para a pasta do outro passam a apontar para a do aberto; senão a pasta entra inteira (com as chaves
//!   que o app não conhece). Pasta órfã de um item do outro é preservada como está;
//! - só `folders` e `items` do outro entram; a raiz (versão, preferências, chaves desconhecidas do cofre) é a do
//!   aberto;
//! - `updatedAt` dos itens que entram **não** muda: carimbar o cofre é do chamador, não da regra.
//!
//! A função é **pura em relação ao disco** (recebe e devolve JSON) e devolve o **resumo** que a tela mostra antes de
//! confirmar. O formato `.tkeys` não ganha chave nenhuma.

use std::collections::{BTreeMap, BTreeSet};

use serde_json::Value;

use crate::merge::json_same;
use crate::vault;

/// O que a junção fez, para a tela mostrar antes de confirmar.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub struct MergeSummary {
    /// Itens do outro cofre que entraram com o id próprio.
    pub added: u32,
    /// Mesmo id e conteúdo diferente: entraram com um id novo (ficaram os dois).
    pub collisions: u32,
    /// Itens que os dois cofres já tinham com o mesmo conteúdo.
    pub already_present: u32,
    /// Pastas do outro que entraram inteiras.
    pub folders_added: u32,
    /// Pastas do outro unidas a uma pasta do aberto com o mesmo nome (id diferente).
    pub folders_merged: u32,
}

impl std::fmt::Display for MergeSummary {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "{} added, {} in duplicate, {} already present, {} folders added, {} folders merged",
            self.added, self.collisions, self.already_present, self.folders_added, self.folders_merged
        )
    }
}

/// Por que os cofres não se juntaram. Mensagens em inglês (diagnóstico; a UI fala pelo i18n dela).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum MergeOtherError {
    /// O cofre (`base` ou `other`) não é um objeto com `items` e `folders` em listas.
    MalformedVault { which: &'static str },
    /// Um item do cofre (`base` ou `other`) não é um objeto JSON.
    ItemNotObject { which: &'static str },
    /// Um item do cofre (`base` ou `other`) não tem `id` em texto.
    ItemWithoutId { which: &'static str },
    /// Uma pasta do cofre (`base` ou `other`) não é um objeto JSON.
    FolderNotObject { which: &'static str },
    /// Uma pasta do cofre (`base` ou `other`) não tem `id` em texto.
    FolderWithoutId { which: &'static str },
    /// O JSON de entrada não parseia (só no caminho [`merge_other_json`]).
    InvalidJson { which: &'static str, error: String },
    /// O cofre juntado não vira JSON de saída (só no caminho [`merge_other_json`]).
    Serialize(String),
}

impl std::fmt::Display for MergeOtherError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::MalformedVault { which } => write!(f, "the {which} vault has no valid `items`/`folders` lists"),
            Self::ItemNotObject { which } => write!(f, "an item in the {which} vault is not a JSON object"),
            Self::ItemWithoutId { which } => write!(f, "an item in the {which} vault has no `id`"),
            Self::FolderNotObject { which } => write!(f, "a folder in the {which} vault is not a JSON object"),
            Self::FolderWithoutId { which } => write!(f, "a folder in the {which} vault has no `id`"),
            Self::InvalidJson { which, error } => write!(f, "invalid {which} JSON: {error}"),
            Self::Serialize(error) => write!(f, "could not serialize the merged vault: {error}"),
        }
    }
}

impl std::error::Error for MergeOtherError {}

/// A lista de `items` de um cofre (objeto com a lista).
fn items_of<'a>(vault: &'a Value, which: &'static str) -> Result<&'a Vec<Value>, MergeOtherError> {
    vault.get("items").and_then(Value::as_array).ok_or(MergeOtherError::MalformedVault { which })
}

/// A lista de `folders` de um cofre (objeto com a lista).
fn folders_of<'a>(vault: &'a Value, which: &'static str) -> Result<&'a Vec<Value>, MergeOtherError> {
    vault.get("folders").and_then(Value::as_array).ok_or(MergeOtherError::MalformedVault { which })
}

/// O `id` em texto de um item ou pasta; vazio conta como ausente.
fn id_of<'a>(entry: &'a Value, kind: &'static str, which: &'static str) -> Result<&'a str, MergeOtherError> {
    let id = entry.get("id").and_then(Value::as_str).filter(|id| !id.is_empty());
    id.ok_or(match kind {
        "item" => MergeOtherError::ItemWithoutId { which },
        _ => MergeOtherError::FolderWithoutId { which },
    })
}

/// Valida a forma do cofre e dos itens/pastas das duas entradas (tudo ou nada antes de montar o resultado).
fn validate(vault: &Value, which: &'static str) -> Result<(), MergeOtherError> {
    for item in items_of(vault, which)? {
        if !item.is_object() {
            return Err(MergeOtherError::ItemNotObject { which });
        }
        id_of(item, "item", which)?;
    }
    for folder in folders_of(vault, which)? {
        if !folder.is_object() {
            return Err(MergeOtherError::FolderNotObject { which });
        }
        id_of(folder, "folder", which)?;
    }
    Ok(())
}

/// Um id novo (UUID v4 do núcleo) que não colide com nenhum id dos dois cofres nem com outro gerado nesta junção.
fn new_id(taken: &mut BTreeSet<String>) -> String {
    // O id é aleatório (UUID v4); a colisão é praticamente impossível, mas o laço garante o contrato.
    loop {
        let id = vault::gen_id();
        if taken.insert(id.clone()) {
            return id;
        }
    }
}

/// Junta o cofre `other` no cofre `base`, sem tocar em nenhum dos dois. Devolve o cofre novo e o resumo.
///
/// # Errors
/// [`MergeOtherError`] — cofre sem `items`/`folders` válido, item/pasta que não é objeto ou sem `id`; e aí nada é
/// montado.
pub fn merge_other(base: &Value, other: &Value) -> Result<(Value, MergeSummary), MergeOtherError> {
    validate(base, "base")?;
    validate(other, "other")?;
    let base_items = items_of(base, "base")?;
    let other_items = items_of(other, "other")?;

    // Pastas: a do aberto fica; a do outro com o mesmo id usa a do aberto; id diferente com o mesmo NOME é unida à do
    // aberto (os itens repontam); o resto entra inteiro.
    let base_folders = folders_of(base, "base")?;
    let other_folders = folders_of(other, "other")?;
    let base_folder_ids: BTreeSet<&str> = base_folders.iter().filter_map(|folder| folder.get("id").and_then(Value::as_str)).collect();
    let mut base_ids_by_name: BTreeMap<&str, &str> = BTreeMap::new();
    for folder in base_folders {
        if let (Some(id), Some(name)) = (folder.get("id").and_then(Value::as_str), folder.get("name").and_then(Value::as_str)) {
            base_ids_by_name.entry(name).or_insert(id);
        }
    }
    let mut folder_map: BTreeMap<String, String> = BTreeMap::new();
    let mut merged_folders: Vec<Value> = base_folders.clone();
    let mut summary = MergeSummary::default();
    for folder in other_folders {
        let id = id_of(folder, "folder", "other")?;
        if base_folder_ids.contains(id) {
            folder_map.insert(id.to_string(), id.to_string());
            continue;
        }
        let same_name = folder.get("name").and_then(Value::as_str).and_then(|name| base_ids_by_name.get(name).copied());
        if let Some(base_id) = same_name {
            folder_map.insert(id.to_string(), base_id.to_string());
            summary.folders_merged += 1;
            continue;
        }
        merged_folders.push(folder.clone());
        folder_map.insert(id.to_string(), id.to_string());
        summary.folders_added += 1;
    }

    // Itens: os do aberto ficam na ordem; os do outro entram na ordem dele, com a pasta repontada quando unida.
    let mut taken: BTreeSet<String> =
        base_items.iter().chain(other_items.iter()).filter_map(|item| item.get("id").and_then(Value::as_str).map(str::to_string)).collect();
    let base_by_id: BTreeMap<&str, &Value> =
        base_items.iter().filter_map(|item| item.get("id").and_then(Value::as_str).map(|id| (id, item))).collect();
    let mut merged_items: Vec<Value> = base_items.clone();
    for item in other_items {
        let id = id_of(item, "item", "other")?;
        let incoming = remap_folder(item, &folder_map);
        let Some(current) = base_by_id.get(id) else {
            // Id que só existe no outro: entra inteiro, com o id dele.
            merged_items.push(incoming);
            summary.added += 1;
            continue;
        };
        if json_same(current, &incoming) {
            summary.already_present += 1;
            continue;
        }
        // O conteúdo pode já estar no aberto sob OUTRO id: é a duplicata de uma junção anterior. Conta como presente
        // (sem isso, juntar o mesmo cofre duas vezes criaria outra duplicata).
        if base_items.iter().any(|item| same_content(item, &incoming)) {
            summary.already_present += 1;
            continue;
        }
        // Mesmo id e conteúdo diferente: ficam os dois; o que entra ganha um id novo.
        let mut duplicate = incoming;
        duplicate["id"] = Value::String(new_id(&mut taken));
        merged_items.push(duplicate);
        summary.collisions += 1;
    }

    let mut merged = base.clone();
    merged["items"] = Value::Array(merged_items);
    merged["folders"] = Value::Array(merged_folders);
    Ok((merged, summary))
}

/// Igualdade de conteúdo de item **sem o `id`** (a duplicata de uma junção anterior tem outro id).
fn same_content(a: &Value, b: &Value) -> bool {
    fn without_id(value: &Value) -> Value {
        let mut object = value.as_object().cloned().unwrap_or_default();
        object.remove("id");
        Value::Object(object)
    }
    json_same(&without_id(a), &without_id(b))
}

/// O item com a pasta do outro repontada para a pasta do aberto quando elas foram unidas (mesmo nome); qualquer outro
/// `folderId` (inclusive o de pasta órfã) fica como está.
fn remap_folder(item: &Value, folder_map: &BTreeMap<String, String>) -> Value {
    let mut out = item.clone();
    let Some(folder_id) = out.get("folderId").and_then(Value::as_str).map(str::to_string) else {
        return out;
    };
    if let Some(target) = folder_map.get(&folder_id)
        && target != &folder_id
    {
        out["folderId"] = Value::String(target.clone());
    }
    out
}

/// [`merge_other`] com os dois cofres em JSON — o caminho da ponte UniFFI, para ela não ganhar `serde_json` como
/// dependência própria. Devolve o JSON do cofre juntado.
///
/// # Errors
/// [`MergeOtherError::InvalidJson`] (entrada que não parseia), [`MergeOtherError::Serialize`] ou qualquer erro de
/// [`merge_other`].
pub fn merge_other_json(base_json: &str, other_json: &str) -> Result<(String, MergeSummary), MergeOtherError> {
    let base: Value = serde_json::from_str(base_json).map_err(|error| MergeOtherError::InvalidJson { which: "base", error: error.to_string() })?;
    let other: Value = serde_json::from_str(other_json).map_err(|error| MergeOtherError::InvalidJson { which: "other", error: error.to_string() })?;
    let (merged, summary) = merge_other(&base, &other)?;
    let text = serde_json::to_string(&merged).map_err(|error| MergeOtherError::Serialize(error.to_string()))?;
    Ok((text, summary))
}
