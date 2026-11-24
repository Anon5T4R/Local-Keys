//! Exportar uma SELEÇÃO de itens para um cofre NOVO, com nome (o do arquivo) e senha escolhidos pelo usuário — a
//! regra da unidade 6 do pedido 027.
//!
//! Não confundir com [`crate::export`]: aquele é o export em CLARO (JSON/CSV) para migrar; este gera o conteúdo de um
//! `.tkeys` comum, que nasce cifrado pelo chamador (`crypto::create_vault`). Nenhuma chave nova no formato.
//!
//! Decisões da regra:
//! - os itens vão **inteiros e na ordem dada** (id repetido conta uma vez: fica a primeira ocorrência): id, datas,
//!   lixeira, anexos, histórico de senha, passkeys, campos extras e chaves desconhecidas atravessam
//!   **semanticamente idênticos** — o `serde_json` da crate não usa `preserve_order`, então a ordem das chaves é
//!   normalizada; valores, listas e aninhamento não mudam. Nada é reescrito nem recarimbado (um merge futuro trata o
//!   mesmo id como o mesmo item);
//! - só as pastas **referenciadas** pela `folderId` de algum item vão, na ordem do cofre de origem e também elas
//!   inteiras; pasta sem item fica de fora, e nada mais da raiz do cofre original (preferências, chaves
//!   desconhecidas da raiz) entra. `folderId` que não aponta para pasta nenhuma do cofre de origem atravessa como
//!   está, sem criar pasta;
//! - **tudo ou nada**: item que não é objeto, item sem `id` ou id que não existe no cofre e nada é gerado; lista de
//!   itens vazia também é erro (não existe cofre novo sem seleção);
//! - o cofre novo nasce com a `version` do cofre de origem ([`vault_from_items`]) ou com a semente do app
//!   ([`subset_vault`], que não recebe a origem): `1`, o mesmo [`crate::vault::EMPTY_VAULT`];
//! - id repetido conta uma vez só (a seleção é um conjunto).
//!
//! Duas entradas: sobre o cofre pelo id ([`vault_from_items`], o caminho do desktop) e sobre listas já prontas
//! ([`subset_vault`], o caminho da ponte UniFFI do Android, que recebe só os itens selecionados e as pastas — o
//! cofre inteiro custaria memória num cofre grande).

use std::collections::HashSet;

use serde_json::{Map, Value, json};

/// Por que o cofre novo não foi gerado. Mensagens em inglês (diagnóstico; a UI fala pelo i18n dela).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ExportVaultError {
    /// A seleção veio vazia — não existe cofre novo sem itens.
    NoItems,
    /// Um item da seleção não é um objeto JSON.
    NotAnObject,
    /// Um item da seleção não tem `id` em texto.
    MissingId,
    /// Um dos ids pedidos não existe no cofre de origem.
    UnknownId { id: String },
    /// O cofre bruto de origem não tem `items` (ou `items` não é uma lista).
    MalformedVault,
    /// A entrada em JSON não é uma lista de objetos (só no caminho [`subset_vault_json`]). `which` diz qual das
    /// duas entradas falhou.
    InvalidJson { which: &'static str, error: String },
    /// O cofre novo não virou JSON de saída.
    Serialize(String),
}

impl std::fmt::Display for ExportVaultError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::NoItems => write!(f, "no items selected"),
            Self::NotAnObject => write!(f, "item is not a JSON object"),
            Self::MissingId => write!(f, "item has no `id`"),
            Self::UnknownId { id } => write!(f, "item `{id}` does not exist"),
            Self::MalformedVault => write!(f, "vault has no `items` list"),
            Self::InvalidJson { which, error } => write!(f, "invalid {which} JSON: {error}"),
            Self::Serialize(error) => write!(f, "could not serialize the new vault: {error}"),
        }
    }
}

impl std::error::Error for ExportVaultError {}

/// O `id` do item; item que não é objeto ou sem `id` em texto é erro.
fn item_id(item: &Value) -> Result<&str, ExportVaultError> {
    if !item.is_object() {
        return Err(ExportVaultError::NotAnObject);
    }
    item.get("id").and_then(Value::as_str).ok_or(ExportVaultError::MissingId)
}

/// As pastas do cofre de origem, na ordem do arquivo e INTOCADAS. É o mesmo conjunto que [`crate::vault::folders`]
/// lê (uma pasta precisa de `id` em texto), mas o JSON vai como veio: as chaves de pasta que o app não conhece
/// atravessam para o cofre novo.
fn folder_values(raw: &Value) -> &[Value] {
    raw.get("folders").and_then(Value::as_array).map_or(&[][..], Vec::as_slice)
}

/// Os itens sem repetir `id`, na ordem de entrada (a primeira ocorrência fica). Só chamar depois de validar os itens.
fn unique_items(items: &[Value]) -> Vec<Value> {
    let mut seen = HashSet::new();
    items.iter().filter(|item| item.get("id").and_then(Value::as_str).is_some_and(|id| seen.insert(id.to_string()))).cloned().collect()
}

/// Monta o JSON do cofre novo: `version`, as pastas referenciadas por algum item (na ordem de `folders`) e os itens.
fn build(version: u64, folders: &[Value], items: &[Value]) -> Result<String, ExportVaultError> {
    let referenced: HashSet<&str> = items.iter().filter_map(|item| item.get("folderId").and_then(Value::as_str)).collect();
    let kept: Vec<Value> =
        folders.iter().filter(|folder| folder.get("id").and_then(Value::as_str).is_some_and(|id| referenced.contains(id))).cloned().collect();
    let mut vault = Map::new();
    vault.insert("version".to_string(), json!(version));
    vault.insert("folders".to_string(), Value::Array(kept));
    vault.insert("items".to_string(), Value::Array(items.to_vec()));
    serde_json::to_string(&Value::Object(vault)).map_err(|error| ExportVaultError::Serialize(error.to_string()))
}

/// A regra pura: o JSON do cofre novo com as `folders` referenciadas pelos `items` e os `items` na ordem dada
/// (id repetido conta uma vez: fica a PRIMEIRA ocorrência), com a semente do app (`version: 1`). Um `folderId` que
/// não aponta para nenhuma pasta do cofre de origem atravessa como está, sem criar pasta.
///
/// Os itens são **semanticamente idênticos** aos da entrada: o `serde_json` da crate não usa `preserve_order`, então
/// a ordem das chaves muda (é normalizada alfabeticamente). Valores, listas e aninhamento não mudam.
///
/// # Errors
/// [`ExportVaultError::NoItems`] (seleção vazia), [`ExportVaultError::NotAnObject`] ou
/// [`ExportVaultError::MissingId`] — e aí nada é gerado.
pub fn subset_vault(folders: &[Value], items: &[Value]) -> Result<String, ExportVaultError> {
    if items.is_empty() {
        return Err(ExportVaultError::NoItems);
    }
    for item in items {
        item_id(item)?;
    }
    build(1, folders, &unique_items(items))
}

/// A regra sobre o cofre de origem, pelo id (o caminho do desktop): seleciona os itens pedidos, junta as pastas do
/// cofre e devolve o JSON do cofre novo — com a `version` da origem. Id repetido conta uma vez.
///
/// # Errors
/// [`ExportVaultError::MalformedVault`] (cofre sem `items` em lista), [`ExportVaultError::UnknownId`] (id que não
/// existe) ou [`ExportVaultError::NoItems`] (nenhum id) — e aí nada é gerado.
pub fn vault_from_items(raw: &Value, ids: &[String]) -> Result<String, ExportVaultError> {
    let items = raw.get("items").and_then(Value::as_array).ok_or(ExportVaultError::MalformedVault)?;
    // Um índice só: "selecionar tudo" num cofre grande não pode virar O(n·m). Id repetido: a PRIMEIRA ocorrência
    // vence (o mesmo que o `find` do `subset_vault` e da ponte).
    let mut by_id: std::collections::HashMap<&str, &Value> = std::collections::HashMap::new();
    for item in items {
        if let Some(id) = item.get("id").and_then(Value::as_str) {
            by_id.entry(id).or_insert(item);
        }
    }
    let mut seen = HashSet::new();
    let mut selected = Vec::new();
    for id in ids {
        if !seen.insert(id.as_str()) {
            continue;
        }
        let item = by_id.get(id.as_str()).ok_or_else(|| ExportVaultError::UnknownId { id: id.clone() })?;
        selected.push((*item).clone());
    }
    if selected.is_empty() {
        return Err(ExportVaultError::NoItems);
    }
    build(raw.get("version").and_then(Value::as_u64).unwrap_or(1), folder_values(raw), &selected)
}

/// Uma lista de objetos a partir do JSON — o caminho da ponte, que manda texto.
fn parse_array(json: &str, which: &'static str) -> Result<Vec<Value>, ExportVaultError> {
    match serde_json::from_str::<Value>(json) {
        Ok(Value::Array(values)) => Ok(values),
        Ok(_) => Err(ExportVaultError::InvalidJson { which, error: "not a JSON array".into() }),
        Err(error) => Err(ExportVaultError::InvalidJson { which, error: error.to_string() }),
    }
}

/// [`subset_vault`] com as duas listas em JSON — o caminho da ponte UniFFI do Android, que manda só os itens
/// selecionados e as pastas (o núcleo parseia para a ponte não ganhar `serde_json` como dependência própria).
///
/// # Errors
/// [`ExportVaultError::InvalidJson`] (entrada que não é lista) ou qualquer erro de [`subset_vault`].
pub fn subset_vault_json(folders_json: &str, items_json: &str) -> Result<String, ExportVaultError> {
    let folders = parse_array(folders_json, "folders")?;
    let items = parse_array(items_json, "items")?;
    subset_vault(&folders, &items)
}
