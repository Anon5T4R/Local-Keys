//! Junta duas cópias do mesmo cofre ("Sincronizar com outra cópia", pedido 022 fase D). As regras
//! estão em `docs/sync-merge.md` na raiz do repositório e os casos comuns em `testdata/sync-merge/` —
//! o Android (`VaultMerge.kt`) segue os mesmos, e o runner de fixtures aqui roda os mesmos arquivos.
//!
//! Resumo: itens por `id`; de um lado só, ficam; dos dois, ganha o de maior **tempo efetivo**
//! (`updatedAt`, `deletedAt` do item e dos anexos — mandar para a lixeira não mexe no `updatedAt`);
//! empate com conteúdo diferente = fica o do cofre atual e conta como conflito. Pastas: união por
//! `id`, nome do atual. Nível do cofre (versão e chaves desconhecidas): o do atual. Nada de chave
//! nova: o formato não muda (os limites aceitos estão na seção "Limites" do doc).

use serde_json::Value;
use std::collections::BTreeMap;

/// O resumo que a tela mostra antes de gravar (ponto de vista do cofre atual).
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub struct MergeSummary {
    /// Itens que só existiam na outra cópia.
    pub added: u64,
    /// Itens em que a outra cópia era mais nova (fora lixeira/restauração).
    pub updated: u64,
    /// Itens que a outra cópia mandou para a lixeira.
    pub trashed: u64,
    /// Itens que a outra cópia tirou da lixeira.
    pub restored: u64,
    /// Mesmo tempo, conteúdo diferente: ficou o do cofre atual.
    pub conflicts: u64,
    /// Pastas que só existiam na outra cópia.
    pub folders_added: u64,
    /// Itens que a outra cópia vai ganhar ou trocar (só existiam aqui, ou aqui eram mais novos).
    pub to_other: u64,
    /// Pastas que a outra cópia vai ganhar.
    pub folders_to_other: u64,
}

impl MergeSummary {
    /// Nada muda no cofre atual nem na outra cópia.
    pub fn nothing_changes(&self) -> bool {
        self.added + self.updated + self.trashed + self.restored + self.folders_added + self.to_other + self.folders_to_other == 0
    }
}

/// O resultado do merge: o cofre juntado (novo `items`/`folders`, o resto do atual) e o resumo.
pub struct MergeResult {
    pub vault: Value,
    pub summary: MergeSummary,
}

/// Um item do cofre (de tipo conhecido ou não) visto pelo JSON: id, tempo efetivo e lixeira.
struct Entry {
    id: String,
    json: Value,
    time: i64,
    in_trash: bool,
}

impl Entry {
    fn of(item: &Value) -> Option<Self> {
        let id = item.get("id").and_then(Value::as_str)?;
        if id.is_empty() {
            return None;
        }
        Some(Self { id: id.to_string(), json: item.clone(), time: effective_time(item), in_trash: deleted_at(item) != 0 })
    }
}

/// `deletedAt` do objeto: número diferente de 0 (o 0 é falsy no JS, `if (i.deletedAt)`); ausente/null = 0.
fn deleted_at(obj: &Value) -> i64 {
    obj.get("deletedAt").and_then(Value::as_i64).unwrap_or(0)
}

/// Tempo efetivo: o maior entre `updatedAt`, o `deletedAt` do item e os `deletedAt` dos anexos.
/// Mandar para a lixeira (item ou anexo) grava só o `deletedAt` — sem isso a lixeira nunca
/// "ganharia" de uma cópia sem ela.
pub fn effective_time(item: &Value) -> i64 {
    let mut t = item.get("updatedAt").and_then(Value::as_i64).unwrap_or(0);
    t = t.max(deleted_at(item));
    if let Some(atts) = item.get("attachments").and_then(Value::as_array) {
        for att in atts {
            t = t.max(deleted_at(att));
        }
    }
    t
}

/// Junta a cópia **atual** (a aberta) com a **outra**. Não grava nada — quem chava decide.
pub fn merge(current: &Value, other: &Value) -> MergeResult {
    let mut s = MergeSummary::default();

    let cur = entries(current);
    let oth = entries(other);
    let oth_by_id: BTreeMap<&str, &Entry> = oth.iter().map(|e| (e.id.as_str(), e)).collect();
    let cur_ids: std::collections::BTreeSet<&str> = cur.iter().map(|e| e.id.as_str()).collect();

    let mut merged: Vec<Value> = Vec::new();
    for c in &cur {
        let Some(o) = oth_by_id.get(c.id.as_str()) else {
            // só aqui: fica, e a outra cópia vai ganhar.
            merged.push(c.json.clone());
            s.to_other += 1;
            continue;
        };
        let same = json_same(&c.json, &o.json);
        let other_wins = !same && o.time > c.time;
        if !same {
            if other_wins {
                if o.in_trash && !c.in_trash {
                    s.trashed += 1;
                } else if !o.in_trash && c.in_trash {
                    s.restored += 1;
                } else {
                    s.updated += 1;
                }
            } else {
                // daqui para a outra cópia: era mais novo aqui, ou empatou com conteúdo diferente
                // (conflito — fica o atual, e a outra cópia o recebe).
                s.to_other += 1;
                if o.time == c.time {
                    s.conflicts += 1;
                }
            }
        }
        merged.push(if other_wins { o.json.clone() } else { c.json.clone() });
    }
    for o in &oth {
        if !cur_ids.contains(o.id.as_str()) {
            merged.push(o.json.clone());
            s.added += 1;
        }
    }
    // Item sem `id` (JSON corrompido) fica de fora da regra e é mantido do atual no fim — não some
    // por causa da sincronização.
    if let Some(arr) = current.get("items").and_then(Value::as_array) {
        merged.extend(arr.iter().filter(|i| i.get("id").and_then(Value::as_str).is_none_or(str::is_empty)).cloned());
    }

    // Pastas: união por id — as do atual primeiro (nome e chaves do atual ficam), depois as novas.
    let cur_folders = current.get("folders").and_then(Value::as_array).cloned().unwrap_or_default();
    let oth_folders = other.get("folders").and_then(Value::as_array).cloned().unwrap_or_default();
    let cur_folder_ids: std::collections::BTreeSet<String> =
        cur_folders.iter().filter_map(|f| f.get("id").and_then(Value::as_str).map(str::to_string)).collect();
    let new_folders: Vec<Value> = oth_folders
        .iter()
        .filter(|f| f.get("id").and_then(Value::as_str).is_none_or(|id| !cur_folder_ids.contains(id)))
        .cloned()
        .collect();
    s.folders_added = new_folders.len() as u64;
    let mut folders = cur_folders;
    folders.extend(new_folders);
    // A outra cópia também ganha as pastas que só existiam aqui.
    let other_folder_ids: std::collections::BTreeSet<&str> =
        oth_folders.iter().filter_map(|f| f.get("id").and_then(Value::as_str)).collect();
    s.folders_to_other = folders
        .iter()
        .filter(|f| f.get("id").and_then(Value::as_str).is_none_or(|id| !other_folder_ids.contains(id)))
        .count() as u64;

    // O cofre juntado: o atual com `items` e `folders` novos — o resto (versão, chaves desconhecidas)
    // fica do atual.
    let mut vault = current.clone();
    vault["items"] = Value::Array(merged);
    vault["folders"] = Value::Array(folders);
    MergeResult { vault, summary: s }
}

/// Os itens com `id` do cofre, na ordem dele (conhecidos ou não: a regra é a mesma, pelo JSON).
fn entries(vault: &Value) -> Vec<Entry> {
    vault.get("items").and_then(Value::as_array).map(|arr| arr.iter().filter_map(Entry::of).collect()).unwrap_or_default()
}

/// Igualdade de JSON pelo conteúdo: ordem de chaves não importa (objetos do serde_json já são mapa
/// ordenado), ordem de array importa, números integrais comparam pelo valor (1 == 1.0) e null só é
/// null — o `JsonEq` do Android.
pub fn json_same(a: &Value, b: &Value) -> bool {
    match (a, b) {
        (Value::Null, Value::Null) => true,
        (Value::Null, _) | (_, Value::Null) => false,
        (Value::Object(x), Value::Object(y)) => x.len() == y.len() && x.iter().all(|(k, v)| y.get(k).is_some_and(|w| json_same(v, w))),
        (Value::Array(x), Value::Array(y)) => x.len() == y.len() && x.iter().zip(y).all(|(v, w)| json_same(v, w)),
        // números integrais: 1 (u64) é igual a 1.0 (f64)
        (Value::Number(x), Value::Number(y)) => match (x.as_i64(), y.as_i64()) {
            (Some(xi), Some(yi)) => xi == yi,
            _ => x.as_f64().is_some_and(|xf| y.as_f64().is_some_and(|yf| xf == yf)),
        },
        _ => a == b,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::{Path, PathBuf};

    /// O diretório dos casos comuns (os mesmos que o Android roda; ver `VaultMergeTest.kt`).
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

    /// `items` e `folders` comparados por `id` (a ordem não faz parte da regra); o resto, igual.
    fn assert_same_vault(name: &str, expected: &Value, got: &Value) {
        let keys = |v: &Value| v.as_object().expect("cofre é objeto").keys().cloned().collect::<Vec<_>>();
        assert_eq!(keys(expected), keys(got), "{name}: chaves do cofre");
        for key in keys(expected) {
            match key.as_str() {
                "items" | "folders" => {
                    fn by_id(arr: &Value) -> BTreeMap<String, &Value> {
                        arr.as_array()
                            .expect("array")
                            .iter()
                            .map(|o| (o.get("id").and_then(Value::as_str).unwrap_or_default().to_string(), o))
                            .collect()
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

    /// O resumo de `summary.json` tem exatamente os campos do [`MergeSummary`].
    fn summary_json(s: &MergeSummary) -> Value {
        serde_json::json!({
            "added": s.added, "updated": s.updated, "trashed": s.trashed, "restored": s.restored,
            "conflicts": s.conflicts, "foldersAdded": s.folders_added, "toOther": s.to_other,
            "foldersToOther": s.folders_to_other,
        })
    }

    /// Roda TODOS os casos de `testdata/sync-merge/`: o resultado tem o conteúdo de `expected.json`,
    /// o resumo de `summary.json` e juntar de novo com qualquer uma das cópias não muda nada
    /// (as duas cópias ficam iguais).
    #[test]
    fn casos_compartilhados() {
        let mut casos: Vec<_> = std::fs::read_dir(cases_dir()).expect("listar casos").filter_map(|e| e.ok()).map(|e| e.path()).filter(|p| p.is_dir()).collect();
        casos.sort();
        assert!(casos.len() >= 10, "sem casos em {:?}", cases_dir());
        for case in casos {
            let nome = case.file_name().unwrap_or_default().to_string_lossy().to_string();
            let a = read_json(&case.join("a.json"));
            let b = read_json(&case.join("b.json"));
            let result = merge(&a, &b);
            let expected = read_json(&case.join("expected.json"));
            assert_same_vault(&nome, &expected, &result.vault);
            assert!(json_same(&read_json(&case.join("summary.json")), &summary_json(&result.summary)), "{nome}: resumo");

            // As duas cópias ficam iguais: juntar o resultado com a outra de novo não muda nada.
            let again = merge(&result.vault, &b);
            assert_same_vault(&format!("{nome} (de novo)"), &result.vault, &again.vault);
        }
    }

    /// Juntar um cofre com ele mesmo: nada muda, nenhum conflito — e o resultado reabre (parseia).
    #[test]
    fn juntar_consigo_mesmo_nao_muda_nada() {
        let case = cases_dir().join("09-anexos");
        let a = read_json(&case.join("a.json"));
        let b = read_json(&case.join("b.json"));
        let merged = merge(&a, &b).vault;
        let texto = serde_json::to_string(&merged).expect("serializa");
        let reaberto: Value = serde_json::from_str(&texto).expect("o resultado reabre");
        let self_merge = merge(&reaberto, &reaberto);
        assert!(self_merge.summary.nothing_changes());
        assert_eq!(self_merge.summary.conflicts, 0);
    }

    /// Igualdade de JSON: ordem de chaves não importa, ordem de array sim, null não é 0.
    #[test]
    fn igualdade_de_json() {
        let parse = |t: &str| serde_json::from_str::<Value>(t).expect("json");
        assert!(json_same(&parse(r#"{"a":1,"b":[1,2]}"#), &parse(r#"{"b":[1,2],"a":1}"#)));
        assert!(!json_same(&parse(r#"{"b":[1,2]}"#), &parse(r#"{"b":[2,1]}"#)));
        assert!(json_same(&parse(r#"{"a":null}"#), &parse(r#"{"a":null}"#)));
        assert!(!json_same(&parse(r#"{"a":null}"#), &parse(r#"{"a":0}"#)));
        // números integrais em forma diferente (1 u64 vs 1.0 f64)
        assert!(json_same(&parse(r#"{"n":1}"#), &parse(r#"{"n":1.0}"#)));
    }

    /// Bordas que os fixtures não cobrem: cofre vazio de um lado, item sem id e chaves desconhecidas
    /// fora de items/folders ficando do atual.
    #[test]
    fn bordas_fora_dos_fixtures() {
        let vazio = serde_json::json!({ "version": 1, "folders": [], "items": [] });
        let atual = serde_json::json!({
            "version": 1, "chaveDesconhecida": {"fica": true},
            "folders": [{"id": "f1", "name": "Pessoal"}],
            "items": [
                {"id": "a", "kind": "login", "name": "A", "updatedAt": 10, "deletedAt": null},
                {"semId": true}
            ]
        });
        // outra vazia: nada muda no atual e tudo vai para a outra.
        let r = merge(&atual, &vazio);
        assert_eq!((r.summary.to_other, r.summary.folders_to_other, r.summary.added), (1, 1, 0));
        assert_eq!(r.vault["version"], 1);
        assert_eq!(r.vault["chaveDesconhecida"], serde_json::json!({"fica": true}), "nível do cofre fica do atual");

        // item sem id (corrompido): fica de fora da regra, mantido do atual no fim.
        assert!(r.vault["items"].as_array().is_some_and(|arr| arr.iter().any(|i| i.get("semId").is_some())));

        // atual vazio: tudo vem da outra.
        let r = merge(&vazio, &atual);
        assert_eq!(r.summary.added, 1);
        assert_eq!(r.summary.folders_added, 1);
        assert_eq!(r.vault["items"].as_array().map(Vec::len), Some(1));
    }
}
