//! A operação em lote da unidade 5 do pedido 027: apagar (para a lixeira) e mover de pasta vários itens de uma vez.
//!
//! Regras que estes testes travam: lote é TUDO OU NADA (um id inválido, um item já na lixeira ou uma pasta que não
//! existe e nada muda — nem nos outros itens), o lote inteiro carimba o MESMO `now`, apagar é ir para a lixeira
//! (recuperável), mover para "sem pasta" é permitido, e a contagem devolvida é a dos itens que mudaram de fato.

use localkeys_core::batch::{
    BatchError, move_items, move_items_json_with_folders, move_items_with_folders, move_many, trash_items, trash_items_json, trash_many,
};
use serde_json::{Value, json};

/// O `now` do lote (um só para todos os itens).
const NOW: u64 = 1_700_000_000_000;

/// Um item no shape do cofre: só os campos que a regra toca e um vizinho para provar que ele não muda.
fn item(id: &str, folder_id: Option<&str>, deleted_at: Option<u64>) -> Value {
    json!({
        "id": id,
        "kind": "login",
        "name": format!("Item {id}"),
        "favorite": false,
        "folderId": folder_id,
        "notes": "",
        "createdAt": 1,
        "updatedAt": 2,
        "deletedAt": deleted_at,
        "login": {"username": id, "password": "segredo", "uris": [], "totp": ""},
    })
}

/// O cofre com as pastas `work`/`home` e os itens na ordem dada.
fn vault(items: Vec<Value>) -> Value {
    json!({
        "version": 1,
        "folders": [{"id": "work", "name": "Work"}, {"id": "home", "name": "Home"}],
        "items": items,
    })
}

// ── sobre uma lista de itens (o caminho da ponte) ────────────────────────────

#[test]
fn trash_items_marca_todos_com_o_mesmo_agora() {
    let mut items = vec![item("a", None, None), item("b", Some("work"), None)];

    let changed = trash_items(&mut items, NOW).unwrap_or_else(|e| panic!("lote válido recusado: {e}"));

    assert_eq!(changed, 2);
    for it in &items {
        assert_eq!(it["deletedAt"], json!(NOW), "{it}");
        assert_eq!(it["updatedAt"], json!(NOW), "{it}");
    }
    // O resto do item atravessa intacto.
    assert_eq!(items[1]["folderId"], "work");
    assert_eq!(items[1]["login"]["password"], "segredo");
}

#[test]
fn trash_items_recusa_item_ja_na_lixeira_e_nada_muda() {
    let items = vec![item("a", None, None), item("b", None, Some(123))];
    let before = items.clone();
    let mut items = items;

    assert_eq!(trash_items(&mut items, NOW), Err(BatchError::AlreadyTrashed { id: "b".into() }));
    assert_eq!(items, before, "lote parcial: o item 'a' não pode ter ido para a lixeira");
}

#[test]
fn trash_items_item_sem_id_e_erro_e_nada_muda() {
    let items = vec![item("a", None, None), json!({"name": "sem id"})];
    let before = items.clone();
    let mut items = items;

    assert_eq!(trash_items(&mut items, NOW), Err(BatchError::MissingId));
    assert_eq!(items, before);
}

/// `deletedAt = 0` é falsy no JS e o resto do app o trata como fora da lixeira (`trashed_items`/`inTrash`): o lote
/// não pode recusar para sempre um item assim, que aparece na lista e pode ser selecionado.
#[test]
fn trash_items_trata_deleted_at_zero_como_fora_da_lixeira() {
    let mut items = vec![item("a", None, Some(0))];

    assert_eq!(trash_items(&mut items, NOW), Ok(1));
    assert_eq!(items[0]["deletedAt"], json!(NOW));
}

#[test]
fn move_items_with_folders_recusa_pasta_inexistente_e_nada_muda() {
    let before = vec![item("a", Some("work"), None), item("b", Some("work"), None)];
    let mut items = before.clone();
    let folders = vec!["work".to_string(), "home".to_string()];

    assert_eq!(move_items_with_folders(&mut items, Some("sumiu"), &folders, NOW), Err(BatchError::UnknownFolder { id: "sumiu".into() }));
    assert_eq!(items, before, "a lista fica inteira como estava");
}

#[test]
fn move_items_with_folders_move_para_pasta_conhecida_e_para_sem_pasta() {
    let folders = vec!["work".to_string(), "home".to_string()];
    let mut items = vec![item("a", Some("work"), None), item("b", Some("home"), None)];

    assert_eq!(move_items_with_folders(&mut items, Some("home"), &folders, NOW), Ok(1));
    assert_eq!(items[0]["folderId"], "home");
    assert_eq!(items[0]["updatedAt"], json!(NOW));
    assert_eq!(items[1]["updatedAt"], 2);

    assert_eq!(move_items_with_folders(&mut items, None, &folders, NOW), Ok(2));
    for it in &items {
        assert_eq!(it["folderId"], Value::Null, "{it}");
    }
    // "Sem pasta" é operação válida mesmo com a lista de pastas conhecidas vazia.
    assert_eq!(move_items_with_folders(&mut items, None, &[], NOW), Ok(0));
}

#[test]
fn move_items_move_para_pasta_e_para_sem_pasta() {
    let mut items = vec![item("a", Some("work"), None), item("b", Some("home"), None)];

    let changed = move_items(&mut items, Some("home"), NOW).unwrap_or_else(|e| panic!("pasta existente recusada: {e}"));
    // Só o 'a' mudou: o 'b' já estava em 'home' e fica com o `updatedAt` antigo.
    assert_eq!(changed, 1);
    assert_eq!(items[0]["folderId"], "home");
    assert_eq!(items[0]["updatedAt"], json!(NOW));
    assert_eq!(items[1]["updatedAt"], 2, "item que não mudou não ganha carimbo");

    let changed = move_items(&mut items, None, NOW).unwrap_or_else(|e| panic!("'sem pasta' é permitido: {e}"));
    assert_eq!(changed, 2);
    for it in &items {
        assert_eq!(it["folderId"], Value::Null, "{it}");
    }

    // Já sem pasta: nada muda.
    assert_eq!(move_items(&mut items, None, NOW), Ok(0));
}

#[test]
fn move_items_item_sem_id_e_erro_e_nada_muda() {
    let items = vec![item("a", Some("work"), None), json!({"folderId": "work"})];
    let before = items.clone();
    let mut items = items;

    assert_eq!(move_items(&mut items, Some("home"), NOW), Err(BatchError::MissingId));
    assert_eq!(items, before);
}

// ── sobre o cofre (o caminho do desktop) ────────────────────────────────────

#[test]
fn trash_many_id_inexistente_nao_muda_nada() {
    let mut raw = vault(vec![item("a", None, None), item("b", None, None)]);
    let before = raw.clone();
    let ids = vec!["a".to_string(), "fantasma".to_string()];

    assert_eq!(trash_many(&mut raw, &ids, NOW), Err(BatchError::UnknownId { id: "fantasma".into() }));
    assert_eq!(raw, before, "o 'a' existe, mas o lote inteiro tem que ser recusado");
}

#[test]
fn trash_many_marca_so_os_ids_pedidos() {
    let mut raw = vault(vec![item("a", None, None), item("b", None, None)]);
    let ids = vec!["a".to_string()];

    assert_eq!(trash_many(&mut raw, &ids, NOW), Ok(1));
    assert_eq!(raw["items"][0]["deletedAt"], json!(NOW));
    assert_eq!(raw["items"][0]["updatedAt"], json!(NOW));
    assert_eq!(raw["items"][1], item("b", None, None), "o vizinho não pode mudar");
}

#[test]
fn trash_many_id_duplicado_conta_uma_vez() {
    let mut raw = vault(vec![item("a", None, None)]);
    let ids = vec!["a".to_string(), "a".to_string()];

    assert_eq!(trash_many(&mut raw, &ids, NOW), Ok(1));
    assert_eq!(raw["items"][0]["deletedAt"], json!(NOW));
}

#[test]
fn trash_many_recusa_item_ja_na_lixeira() {
    let mut raw = vault(vec![item("a", None, None), item("b", None, Some(123))]);
    let before = raw.clone();
    let ids = vec!["a".to_string(), "b".to_string()];

    assert_eq!(trash_many(&mut raw, &ids, NOW), Err(BatchError::AlreadyTrashed { id: "b".into() }));
    assert_eq!(raw, before);
}

#[test]
fn trash_many_cofre_sem_items_e_erro() {
    for malformed in [json!({}), json!({"items": null}), json!("nada")] {
        let mut raw = malformed.clone();
        let ids = vec!["a".to_string()];
        assert_eq!(trash_many(&mut raw, &ids, NOW), Err(BatchError::MalformedVault), "{malformed}");
        assert_eq!(raw, malformed, "cofre intacto");
    }
}

#[test]
fn move_many_move_so_os_ids_pedidos() {
    let mut raw = vault(vec![item("a", Some("work"), None), item("b", Some("work"), None)]);
    let ids = vec!["a".to_string()];

    assert_eq!(move_many(&mut raw, &ids, Some("home"), NOW), Ok(1));
    assert_eq!(raw["items"][0]["folderId"], "home");
    assert_eq!(raw["items"][0]["updatedAt"], json!(NOW));
    assert_eq!(raw["items"][1], item("b", Some("work"), None));

    // "Sem pasta" é permitido e é o mesmo lote.
    let ids = vec!["b".to_string()];
    assert_eq!(move_many(&mut raw, &ids, None, NOW), Ok(1));
    assert_eq!(raw["items"][1]["folderId"], Value::Null);
}

#[test]
fn move_many_pasta_inexistente_e_erro_e_nada_muda() {
    let mut raw = vault(vec![item("a", Some("work"), None)]);
    let before = raw.clone();
    let ids = vec!["a".to_string()];

    assert_eq!(move_many(&mut raw, &ids, Some("nao-existe"), NOW), Err(BatchError::UnknownFolder { id: "nao-existe".into() }));
    assert_eq!(raw, before);
}

#[test]
fn move_many_id_inexistente_nao_muda_nada() {
    let mut raw = vault(vec![item("a", Some("work"), None)]);
    let before = raw.clone();
    let ids = vec!["a".to_string(), "fantasma".to_string()];

    assert_eq!(move_many(&mut raw, &ids, Some("home"), NOW), Err(BatchError::UnknownId { id: "fantasma".into() }));
    assert_eq!(raw, before);
}

// ── entrada em JSON (o caminho da ponte UniFFI) ─────────────────────────────

#[test]
fn json_do_lote_devolve_o_array_atualizado() {
    let items = json!([item("a", Some("work"), None), item("b", Some("work"), None)]).to_string();

    let out = trash_items_json(&items, NOW).unwrap_or_else(|e| panic!("JSON válido recusado: {e}"));
    let out: Value = serde_json::from_str(&out).unwrap();
    assert_eq!(out[0]["deletedAt"], json!(NOW));
    assert_eq!(out[1]["deletedAt"], json!(NOW));

    let folders = vec!["work".to_string(), "home".to_string()];
    let out = move_items_json_with_folders(&items, Some("home"), &folders, NOW).unwrap_or_else(|e| panic!("JSON válido recusado: {e}"));
    let out: Value = serde_json::from_str(&out).unwrap();
    assert_eq!(out[0]["folderId"], "home");
    assert_eq!(out[1]["folderId"], "home");
}

#[test]
fn json_invalido_e_erro() {
    for bad in ["não é json", "{}", "42"] {
        assert!(matches!(trash_items_json(bad, NOW), Err(BatchError::InvalidJson(_))), "{bad}");
        assert!(matches!(move_items_json_with_folders(bad, None, &[], NOW), Err(BatchError::InvalidJson(_))), "{bad}");
    }
}

#[test]
fn mensagens_do_erro_em_ingles() {
    assert_eq!(BatchError::MissingId.to_string(), "item has no `id`");
    assert_eq!(BatchError::UnknownId { id: "x".into() }.to_string(), "item `x` does not exist");
    assert_eq!(BatchError::AlreadyTrashed { id: "x".into() }.to_string(), "item `x` is already in the trash");
    assert_eq!(BatchError::UnknownFolder { id: "f".into() }.to_string(), "folder `f` does not exist");
    assert_eq!(BatchError::MalformedVault.to_string(), "vault has no `items` list");
}
