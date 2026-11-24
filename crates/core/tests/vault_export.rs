//! Exportar uma SELEÇÃO de itens para um cofre novo (unidade 6 do pedido 027): o cofre novo nasce com os itens
//! escolhidos INTACTOS (id, anexos, histórico, passkeys, chaves desconhecidas), só com as pastas que eles
//! referenciam, e reabre como qualquer `.tkeys`.

use localkeys_core::crypto::{self, KdfParams};
use localkeys_core::vault::{self, EMPTY_VAULT};
use localkeys_core::vault_export::{ExportVaultError, subset_vault, vault_from_items};
use serde_json::{Value, json};

/// Argon2 mínimo: a ida e volta pelo `.tkeys` não é o que este teste mede.
const LIGHT: KdfParams = KdfParams { m_cost: 512, t_cost: 1, p_cost: 1 };

/// Item completo: o que o cofre exportado não pode perder nem tocar.
fn item(id: &str, folder_id: Option<&str>) -> Value {
    json!({
        "id": id,
        "kind": "login",
        "name": format!("Item {id}"),
        "favorite": true,
        "folderId": folder_id,
        "notes": "notas",
        "createdAt": 1,
        "updatedAt": 2,
        "deletedAt": null,
        "login": {"username": id, "password": "segredo", "uris": ["https://example.com"], "totp": ""},
        "passwordHistory": [{"password": "antiga", "at": 1}],
        "customFields": [{"id": "c1", "name": "Extra", "value": "v", "hidden": false}],
        "attachments": [{"id": "a1", "name": "f.png", "size": 3, "mime": "image/png", "dataB64": "aGk="}],
        "passkeys": [{"credentialId": "pk"}],
        "unknownKey": {"nested": [1, 2, 3]}
    })
}

/// Pastas com uma chave desconhecida (que atravessa) e uma sem item nenhum.
fn folders() -> Vec<Value> {
    vec![
        json!({"id": "work", "name": "Work", "unknownFolderKey": true}),
        json!({"id": "home", "name": "Home"}),
        json!({"id": "unused", "name": "Unused"}),
    ]
}

/// O cofre de origem: itens em pastas diferentes, um sem pasta, um na lixeira e chaves que a raiz não exporta.
fn vault() -> Value {
    let mut trashed = item("trashed", Some("home"));
    trashed["deletedAt"] = json!(1_700_000_000_000i64);
    json!({
        "version": 2,
        "folders": folders(),
        "items": [item("a", Some("work")), item("b", None), item("c", Some("home")), trashed],
        "preferences": {"theme": "dark"},
        "unknownRoot": 42
    })
}

fn parse(json: &str) -> Value {
    serde_json::from_str(json).unwrap_or_else(|e| panic!("cofre novo não é JSON: {e}"))
}

/// O item como texto — a comparação byte a byte é a prova de que ele atravessou intacto.
fn text(item: &Value) -> String {
    serde_json::to_string(item).unwrap_or_else(|e| panic!("item não serializa: {e}"))
}

#[test]
fn subset_vault_mantem_os_itens_intactos_e_so_as_pastas_referenciadas() {
    let items = vec![item("a", Some("work")), item("b", None)];

    let out = parse(&subset_vault(&folders(), &items).unwrap_or_else(|e| panic!("seleção válida recusada: {e}")));

    assert_eq!(out["version"], 1);
    let new_items = out["items"].as_array().expect("items");
    assert_eq!(new_items.len(), 2);
    for (new, original) in new_items.iter().zip(items.iter()) {
        assert_eq!(text(new), text(original), "item `{}` mudou de forma", original["id"]);
    }
    // Só a pasta referenciada, e ela inteira (a chave desconhecida da pasta atravessa).
    let new_folders = out["folders"].as_array().expect("folders");
    assert_eq!(new_folders.len(), 1, "pastas sem item não vão");
    assert_eq!(new_folders[0], json!({"id": "work", "name": "Work", "unknownFolderKey": true}));
}

#[test]
fn subset_vault_guarda_a_ordem_dos_itens_e_das_pastas() {
    let items = vec![item("c", Some("home")), item("a", Some("work"))];

    let out = parse(&subset_vault(&folders(), &items).unwrap_or_else(|e| panic!("seleção válida recusada: {e}")));

    let ids: Vec<&str> = out["items"].as_array().expect("items").iter().filter_map(|it| it["id"].as_str()).collect();
    assert_eq!(ids, ["c", "a"], "a ordem é a que a seleção deu");
    let folder_ids: Vec<&str> = out["folders"].as_array().expect("folders").iter().filter_map(|f| f["id"].as_str()).collect();
    assert_eq!(folder_ids, ["work", "home"], "as pastas mantêm a ordem do cofre de origem");
}

#[test]
fn subset_vault_item_sem_pasta_nao_leva_pasta_nenhuma() {
    let items = vec![item("b", None)];

    let out = parse(&subset_vault(&folders(), &items).unwrap_or_else(|e| panic!("seleção válida recusada: {e}")));

    assert_eq!(out["folders"], json!([]));
    assert_eq!(out["items"][0]["folderId"], Value::Null, "e o item fica como veio");
}

#[test]
fn subset_vault_lista_vazia_e_erro() {
    assert_eq!(subset_vault(&folders(), &[]), Err(ExportVaultError::NoItems));
}

#[test]
fn subset_vault_item_sem_id_ou_que_nao_e_objeto_e_erro() {
    assert_eq!(subset_vault(&folders(), &[json!({"name": "sem id"})]), Err(ExportVaultError::MissingId));
    assert_eq!(subset_vault(&folders(), &[item("a", None), json!("não é item")]), Err(ExportVaultError::NotAnObject));
}

#[test]
fn vault_from_items_seleciona_pelos_ids_e_copia_a_versao_da_origem() {
    let raw = vault();
    let ids = vec!["a".to_string(), "c".to_string()];

    let out = parse(&vault_from_items(&raw, &ids).unwrap_or_else(|e| panic!("ids válidos recusados: {e}")));

    assert_eq!(out["version"], 2, "a versão é a do cofre de origem");
    let ids: Vec<&str> = out["items"].as_array().expect("items").iter().filter_map(|it| it["id"].as_str()).collect();
    assert_eq!(ids, ["a", "c"], "na ordem pedida e sem os que não foram pedidos");
    let folder_ids: Vec<&str> = out["folders"].as_array().expect("folders").iter().filter_map(|f| f["id"].as_str()).collect();
    assert_eq!(folder_ids, ["work", "home"], "só as pastas dos itens e na ordem do arquivo");
    assert!(out.get("preferences").is_none(), "nada mais da raiz do cofre original vai");
    assert!(out.get("unknownRoot").is_none());
}

#[test]
fn vault_from_items_id_inexistente_e_erro() {
    let ids = vec!["a".to_string(), "fantasma".to_string()];

    assert_eq!(vault_from_items(&vault(), &ids), Err(ExportVaultError::UnknownId { id: "fantasma".into() }));
}

#[test]
fn vault_from_items_lista_vazia_e_erro() {
    assert_eq!(vault_from_items(&vault(), &[]), Err(ExportVaultError::NoItems));
}

#[test]
fn vault_from_items_id_repetido_conta_uma_vez() {
    let ids = vec!["a".to_string(), "a".to_string()];

    let out = parse(&vault_from_items(&vault(), &ids).unwrap_or_else(|e| panic!("id repetido é a mesma seleção: {e}")));

    assert_eq!(out["items"].as_array().expect("items").len(), 1);
}

/// Id repetido DENTRO do cofre de origem: o índice guarda a PRIMEIRA ocorrência (o mesmo item que o `find` do
/// `subset_vault` e a ponte entregam), não a última.
#[test]
fn vault_from_items_id_repetido_usa_a_primeira_ocorrencia() {
    let mut first = item("a", None);
    first["name"] = json!("A primeira");
    let mut last = item("a", None);
    last["name"] = json!("A última");
    let source = json!({"version": 1, "folders": [], "items": [first, last]});

    let out = parse(&vault_from_items(&source, &["a".to_string()]).unwrap_or_else(|e| panic!("seleção válida recusada: {e}")));

    let items = out["items"].as_array().expect("items");
    assert_eq!(items.len(), 1);
    assert_eq!(items[0]["name"], "A primeira", "a primeira ocorrência do id fica, como no subset/ponte");
}

/// A ponte manda a lista já pronta: id repetido também conta uma vez (a primeira ocorrência fica), como no caminho
/// do desktop.
#[test]
fn subset_vault_id_repetido_conta_uma_vez() {
    let items = vec![item("a", Some("work")), item("b", None), item("a", Some("work"))];

    let out = parse(&subset_vault(&folders(), &items).unwrap_or_else(|e| panic!("seleção válida recusada: {e}")));

    let ids: Vec<&str> = out["items"].as_array().expect("items").iter().filter_map(|it| it["id"].as_str()).collect();
    assert_eq!(ids, ["a", "b"], "a primeira ocorrência fica; a repetida sai");
}

#[test]
fn vault_from_items_sem_lista_de_itens_e_erro() {
    for malformed in [json!({}), json!({"items": null}), json!({"items": "nada"})] {
        let ids = vec!["a".to_string()];
        assert_eq!(vault_from_items(&malformed, &ids), Err(ExportVaultError::MalformedVault), "{malformed}");
    }
}

#[test]
fn o_cofre_novo_reabre_como_cofre() {
    // O item da lixeira, se selecionado de algum jeito, vai COMO ESTÁ: esta peça não decide a lixeira.
    let ids = vec!["a".to_string(), "trashed".to_string()];
    let out = parse(&vault_from_items(&vault(), &ids).unwrap_or_else(|e| panic!("ids válidos recusados: {e}")));

    let view: Vec<String> = vault::items_view(&out).into_iter().map(|it| it.id).collect();
    assert_eq!(view, ["a"], "a visão de lista enxerga o item vivo e esconde o da lixeira");
    assert_eq!(out["items"][1]["deletedAt"], json!(1_700_000_000_000i64), "o item da lixeira não foi alterado");
    let folder_ids: Vec<String> = vault::folders(&out).into_iter().map(|f| f.id).collect();
    assert_eq!(folder_ids, ["work", "home"]);
}

#[test]
fn a_ida_e_volta_pelo_tkeys_devolve_o_mesmo_plaintext() {
    let json = vault_from_items(&vault(), &["a".to_string(), "c".to_string()]).unwrap_or_else(|e| panic!("ids válidos recusados: {e}"));

    let (file, _session) =
        crypto::create_vault_with("senha-do-cofre-novo", json.as_bytes(), LIGHT).unwrap_or_else(|e| panic!("cofre novo não cifra: {e}"));
    let (plaintext, _session) = crypto::open_vault("senha-do-cofre-novo", &file).unwrap_or_else(|e| panic!("cofre novo não reabre: {e}"));

    assert_eq!(&plaintext[..], json.as_bytes());
}

#[test]
fn o_cofre_de_origem_nao_muda() {
    let raw = vault();
    let before = raw.clone();
    let _ = vault_from_items(&raw, &["a".to_string()]);

    assert_eq!(raw, before, "exportar lê a origem; não toca nela");
    // Uma recusa também não deixa rastro.
    let _ = vault_from_items(&raw, &["fantasma".to_string()]);
    assert_eq!(raw, before);
}

#[test]
fn mensagens_do_erro_em_ingles() {
    assert_eq!(ExportVaultError::NoItems.to_string(), "no items selected");
    assert_eq!(ExportVaultError::NotAnObject.to_string(), "item is not a JSON object");
    assert_eq!(ExportVaultError::MissingId.to_string(), "item has no `id`");
    assert_eq!(ExportVaultError::UnknownId { id: "x".into() }.to_string(), "item `x` does not exist");
    assert_eq!(ExportVaultError::MalformedVault.to_string(), "vault has no `items` list");
}

/// O cofre vazio do app continua sendo a semente de um cofre novo.
#[test]
fn cofre_vazio_e_um_cofre_novo_legivel() {
    let empty: Value = serde_json::from_str(EMPTY_VAULT).unwrap_or_else(|e| panic!("semente inválida: {e}"));
    assert_eq!(empty["version"], 1);
}
