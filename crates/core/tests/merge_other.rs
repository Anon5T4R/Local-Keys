//! A junção de um cofre DIFERENTE no aberto (unidade 7 do pedido 027): roda as fixtures `other-*` de
//! `testdata/sync-merge/` (esperado escrito à mão) e as propriedades da regra — idempotência, resumo conferindo com o
//! resultado e nada do outro cofre entrando além de `folders`/`items`.

use std::collections::BTreeMap;
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use localkeys_core::merge::json_same;
use localkeys_core::merge_other::{MergeOtherError, MergeSummary, merge_other};
use serde_json::{Value, json};

/// O diretório dos casos comuns (o mesmo que o desktop e o Android leem).
fn cases_dir() -> PathBuf {
    let manifest = Path::new(env!("CARGO_MANIFEST_DIR"));
    [manifest.join("../../testdata/sync-merge"), manifest.join("../testdata/sync-merge")]
        .into_iter()
        .find(|p| p.is_dir())
        .unwrap_or_else(|| panic!("testdata/sync-merge não encontrado a partir de {}", manifest.display()))
}

fn read_json(path: &Path) -> Value {
    let text = std::fs::read_to_string(path).unwrap_or_else(|e| panic!("ler {}: {e}", path.display()));
    serde_json::from_str(&text).unwrap_or_else(|e| panic!("parse {}: {e}", path.display()))
}

fn item_ids(vault: &Value) -> Vec<String> {
    vault
        .get("items")
        .and_then(Value::as_array)
        .map(|arr| arr.iter().filter_map(|item| item.get("id").and_then(Value::as_str).map(str::to_string)).collect())
        .unwrap_or_default()
}

/// Troca os `"<new>"` do esperado pelos ids que a regra gerou (a colisão ganha um id aleatório, que o fixture não
/// pode prever): o esperado diz QUANTOS itens novos existem, não quais.
fn bind_new_ids(expected: &Value, merged: &Value, base: &Value, other: &Value) -> Value {
    let taken: BTreeSet<String> = item_ids(base).into_iter().chain(item_ids(other)).collect();
    let fresh: Vec<String> = item_ids(merged).into_iter().filter(|id| !taken.contains(id)).collect();
    let mut out = expected.clone();
    let mut used = 0;
    if let Some(items) = out.get_mut("items").and_then(Value::as_array_mut) {
        for item in items.iter_mut() {
            if item.get("id").and_then(Value::as_str) == Some("<new>") {
                let id = fresh.get(used).unwrap_or_else(|| panic!("o esperado tem mais `<new>` do que ids gerados"));
                item["id"] = Value::String(id.clone());
                used += 1;
            }
        }
    }
    assert_eq!(used, fresh.len(), "todo id gerado tem o seu `<new>` no esperado");
    out
}

/// `items` e `folders` comparados por `id` (a ordem não faz parte da regra); o resto, igual.
fn assert_same_vault(name: &str, expected: &Value, got: &Value) {
    let keys = |v: &Value| v.as_object().expect("cofre é objeto").keys().cloned().collect::<Vec<_>>();
    assert_eq!(keys(expected), keys(got), "{name}: chaves do cofre");
    for key in keys(expected) {
        match key.as_str() {
            "items" | "folders" => {
                fn by_id(arr: &Value) -> BTreeMap<String, &Value> {
                    arr.as_array().expect("array").iter().map(|o| (o.get("id").and_then(Value::as_str).unwrap_or_default().to_string(), o)).collect()
                }
                let e = by_id(expected.get(&key).expect("presente"));
                let g = by_id(got.get(&key).expect("presente"));
                assert_eq!(e.keys().collect::<Vec<_>>(), g.keys().collect::<Vec<_>>(), "{name}: ids em {key}");
                for (id, obj) in e {
                    assert!(json_same(obj, g[&id]), "{name}: {key}/{id}\nesperado {obj}\nveio {}", g[&id]);
                }
            }
            _ => assert!(json_same(expected.get(&key).expect("presente"), got.get(&key).expect("presente")), "{name}: {key}"),
        }
    }
}

fn summary_json(s: &MergeSummary) -> Value {
    json!({
        "added": s.added,
        "collisions": s.collisions,
        "alreadyPresent": s.already_present,
        "foldersAdded": s.folders_added,
        "foldersMerged": s.folders_merged,
    })
}

/// Roda TODOS os casos `other-*-base.json`: o resultado tem o conteúdo de `-expected.json` (com os ids gerados
/// ligados aos `<new>`) e o resumo de `-summary.json`; juntar de novo com o mesmo cofre não muda nada; e as duas
/// entradas ficam intactas.
#[test]
fn casos_compartilhados() {
    let dir = cases_dir();
    let mut bases: Vec<PathBuf> = std::fs::read_dir(&dir)
        .expect("listar testdata/sync-merge")
        .filter_map(|entry| entry.ok())
        .map(|entry| entry.path())
        .filter(|path| {
            path.is_file() && path.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.starts_with("other-") && n.ends_with("-base.json"))
        })
        .collect();
    bases.sort();
    assert!(bases.len() >= 10, "sem casos `other-*` em {}", dir.display());

    for base_path in bases {
        let file_name = base_path.file_name().and_then(|n| n.to_str()).expect("nome do arquivo").to_string();
        let name = file_name.strip_suffix("-base.json").expect("sufixo").to_string();
        let base = read_json(&base_path);
        let other = read_json(&dir.join(format!("{name}-other.json")));
        let base_before = base.clone();
        let other_before = other.clone();

        let (merged, summary) = merge_other(&base, &other).unwrap_or_else(|e| panic!("{name}: {e}"));
        let expected = bind_new_ids(&read_json(&dir.join(format!("{name}-expected.json"))), &merged, &base, &other);
        assert_same_vault(&name, &expected, &merged);
        assert!(json_same(&read_json(&dir.join(format!("{name}-summary.json"))), &summary_json(&summary)), "{name}: resumo");

        // Nada do outro cofre além de `folders`/`items` entra; as duas entradas não mudam.
        assert_eq!(
            base.as_object().expect("objeto").keys().collect::<Vec<_>>(),
            merged.as_object().expect("objeto").keys().collect::<Vec<_>>(),
            "{name}: a raiz é a do cofre aberto"
        );
        assert!(json_same(&base_before, &base), "{name}: o cofre aberto não muda");
        assert!(json_same(&other_before, &other), "{name}: o outro cofre não muda");

        // Resumo confere com o resultado.
        let base_ids: BTreeSet<String> = item_ids(&base).into_iter().collect();
        let arrived = item_ids(&merged).into_iter().filter(|id| !base_ids.contains(id)).count();
        assert_eq!(arrived, (summary.added + summary.collisions) as usize, "{name}: itens que chegaram");
        let base_folders: BTreeSet<String> =
            base["folders"].as_array().expect("folders").iter().filter_map(|f| f.get("id").and_then(Value::as_str).map(str::to_string)).collect();
        let new_folders = merged["folders"]
            .as_array()
            .expect("folders")
            .iter()
            .filter(|f| f.get("id").and_then(Value::as_str).is_some_and(|id| !base_folders.contains(id)))
            .count();
        assert_eq!(new_folders, summary.folders_added as usize, "{name}: pastas que chegaram");

        // Idempotente: juntar o resultado com o mesmo outro cofre de novo não adiciona nada nem cria colisão.
        let (again, again_summary) = merge_other(&merged, &other).unwrap_or_else(|e| panic!("{name} (de novo): {e}"));
        assert_same_vault(&format!("{name} (de novo)"), &merged, &again);
        assert_eq!(
            (again_summary.added, again_summary.collisions, again_summary.folders_added),
            (0, 0, 0),
            "{name}: a segunda passada não adiciona nada"
        );
    }
}

#[test]
fn cofre_invalido_e_item_sem_id_sao_erro() {
    let empty = json!({"version": 1, "folders": [], "items": []});

    assert_eq!(merge_other(&json!({"version": 1, "folders": []}), &empty).err(), Some(MergeOtherError::MalformedVault { which: "base" }));
    assert_eq!(merge_other(&empty, &json!({"items": []})).err(), Some(MergeOtherError::MalformedVault { which: "other" }));
    assert_eq!(
        merge_other(&empty, &json!({"version": 1, "folders": [], "items": ["não é objeto"]})).err(),
        Some(MergeOtherError::ItemNotObject { which: "other" })
    );
    assert_eq!(
        merge_other(&json!({"version": 1, "folders": [], "items": [{"name": "sem id"}]}), &empty).err(),
        Some(MergeOtherError::ItemWithoutId { which: "base" })
    );
    assert_eq!(
        merge_other(&empty, &json!({"version": 1, "folders": ["x"], "items": []})).err(),
        Some(MergeOtherError::FolderNotObject { which: "other" })
    );
    assert_eq!(
        merge_other(&empty, &json!({"version": 1, "folders": [{"name": "sem id"}], "items": []})).err(),
        Some(MergeOtherError::FolderWithoutId { which: "other" })
    );
}

/// O resumo tem uma linha curta em inglês (a tela traduz o que mostra).
#[test]
fn resumo_e_erros_tem_texto_em_ingles() {
    let s = MergeSummary { added: 2, collisions: 1, already_present: 3, folders_added: 4, folders_merged: 5 };
    assert_eq!(s.to_string(), "2 added, 1 in duplicate, 3 already present, 4 folders added, 5 folders merged");
    assert_eq!(MergeOtherError::MalformedVault { which: "other" }.to_string(), "the other vault has no valid `items`/`folders` lists");
    assert_eq!(MergeOtherError::ItemWithoutId { which: "base" }.to_string(), "an item in the base vault has no `id`");
}
