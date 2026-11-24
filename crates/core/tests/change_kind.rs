//! A regra de mudar o tipo de um item (`change_kind`, unidade 4 do pedido 027): todo valor não vazio do objeto
//! do tipo antigo chega ao item novo como campo extra — oculto quando o campo é segredo — e todo o resto do item
//! atravessa idêntico.

use localkeys_core::change_kind::{ChangeKindError, FieldLabels, change_item_kind, change_kind, change_kind_json};
use localkeys_core::vault::ItemKind;
use serde_json::{Value, json};

const KINDS: [ItemKind; 4] = [ItemKind::Login, ItemKind::Note, ItemKind::Card, ItemKind::Identity];

/// A chave do objeto tipado de um tipo (nota não tem objeto próprio).
fn object_key(kind: ItemKind) -> Option<&'static str> {
    match kind {
        ItemKind::Login => Some("login"),
        ItemKind::Card => Some("card"),
        ItemKind::Identity => Some("identity"),
        ItemKind::Note => None,
    }
}

/// Item completo de cada tipo: o objeto tipado com valores distintos (para achar cada um no destino), campo extra
/// antigo, histórico, anexos, passkeys, chave desconhecida e o resto do nível do item.
fn item_of(kind: ItemKind) -> Value {
    let typed = match kind {
        ItemKind::Login => json!({
            "login": {"username": "user-login", "password": "pass-login", "uris": ["https://one", "https://two"], "totp": "totp-login"}
        }),
        ItemKind::Card => json!({
            "card": {"cardholder": "holder-card", "brand": "brand-card", "number": "4111111111111111", "exp": "12/30", "code": "123"}
        }),
        ItemKind::Identity => json!({
            "identity": {"firstName": "first-identity", "lastName": "last-identity", "email": "email-identity", "phone": "phone-identity", "address": "address-identity"}
        }),
        ItemKind::Note => json!({}),
    };
    let mut item = json!({
        "id": "item-1",
        "kind": kind.wire(),
        "name": "Item",
        "favorite": true,
        "folderId": "folder-1",
        "notes": "notas",
        "createdAt": 1,
        "updatedAt": 2,
        "deletedAt": null,
        "customFields": [{"id": "old-1", "name": "Extra", "value": "valor-extra", "hidden": false}],
        "passwordHistory": [{"password": "antiga", "at": 1}],
        "attachments": [{"id": "att-1", "name": "f.png", "size": 1, "mime": "image/png", "dataB64": "aGk="}],
        "passkeys": [{"id": "pk-1"}],
        "unknownKey": 42
    });
    for (key, value) in typed.as_object().unwrap_or_else(|| unreachable!("objeto tipado é objeto")) {
        item[key.as_str()] = value.clone();
    }
    item
}

/// O que a regra manda gerar de cada tipo, com os rótulos padrão em inglês: (nome, valor, oculto).
fn extras_of(kind: ItemKind) -> Vec<(&'static str, &'static str, bool)> {
    match kind {
        ItemKind::Login => vec![
            ("Username", "user-login", false),
            ("Password", "pass-login", true),
            ("Website", "https://one", false),
            ("Website 2", "https://two", false),
            ("TOTP", "totp-login", true),
        ],
        ItemKind::Card => vec![
            ("Cardholder", "holder-card", false),
            ("Brand", "brand-card", false),
            ("Card number", "4111111111111111", true),
            ("Expiry", "12/30", false),
            ("Security code", "123", true),
        ],
        ItemKind::Identity => vec![
            ("First name", "first-identity", false),
            ("Last name", "last-identity", false),
            ("Email", "email-identity", false),
            ("Phone", "phone-identity", false),
            ("Address", "address-identity", false),
        ],
        ItemKind::Note => Vec::new(),
    }
}

fn triples(fields: &[Value]) -> Vec<(String, String, bool)> {
    fields
        .iter()
        .map(|field| {
            (
                field["name"].as_str().unwrap_or_default().to_string(),
                field["value"].as_str().unwrap_or_default().to_string(),
                field["hidden"].as_bool().unwrap_or(false),
            )
        })
        .collect()
}

fn names(fields: &[Value]) -> Vec<String> {
    fields.iter().map(|field| field["name"].as_str().unwrap_or_default().to_string()).collect()
}

#[test]
fn cada_par_de_tipos_distintos_converte_sem_perder_valor() {
    for from in KINDS {
        for to in KINDS {
            if from == to {
                continue;
            }
            let context = format!("{from:?} -> {to:?}");
            let item = item_of(from);

            let converted = change_kind(&item, to, &FieldLabels::default()).unwrap_or_else(|e| panic!("{context}: {e}"));
            assert_eq!(converted["kind"], to.wire(), "{context}");

            // Campos extras antigos na frente e intactos; os gerados entram depois.
            let old = item["customFields"].as_array().unwrap_or_else(|| unreachable!("fixture tem customFields"));
            let fields = converted["customFields"].as_array().unwrap_or_else(|| unreachable!("conversão gerou customFields"));
            assert_eq!(&fields[..old.len()], old.as_slice(), "{context}: campos antigos mudaram");
            let want: Vec<(String, String, bool)> =
                extras_of(from).into_iter().map(|(name, value, hidden)| (name.to_string(), value.to_string(), hidden)).collect();
            assert_eq!(triples(&fields[old.len()..]), want, "{context}: campos gerados");

            // Nenhum valor não vazio se perdeu, e o oculto continua oculto.
            for (name, value, hidden) in extras_of(from) {
                let field = fields
                    .iter()
                    .find(|field| field["name"] == name && field["value"] == value)
                    .unwrap_or_else(|| panic!("{context}: campo '{name}' com '{value}' sumiu"));
                assert_eq!(field["hidden"], hidden, "{context}: visibilidade de '{name}'");
            }

            // O objeto do tipo antigo saiu do item.
            if let Some(key) = object_key(from) {
                assert!(converted.get(key).is_none(), "{context}: objeto '{key}' ficou");
            }

            // Todo o resto do item é idêntico: só `kind` e `customFields` mudam.
            let mut expected = item.clone();
            {
                let map = expected.as_object_mut().unwrap_or_else(|| unreachable!("fixture é objeto"));
                for key in ["login", "card", "identity"] {
                    map.remove(key);
                }
                map.remove("customFields");
                map.insert("kind".into(), json!(to.wire()));
            }
            let mut actual = converted.clone();
            actual.as_object_mut().unwrap_or_else(|| unreachable!("convertido é objeto")).remove("customFields");
            assert_eq!(actual, expected, "{context}: o resto do item mudou");
        }
    }
}

#[test]
fn tipo_igual_nao_muda_nada() {
    for kind in KINDS {
        let item = item_of(kind);
        assert_eq!(change_kind(&item, kind, &FieldLabels::default()).unwrap(), item, "{kind:?}");
    }
}

#[test]
fn tipo_desconhecido_e_erro_e_o_item_nao_muda() {
    let item = json!({"id": "x", "kind": "otp", "name": "n", "login": {"username": "u"}});
    let mut vault = json!({"items": [item.clone()]});
    let result = change_item_kind(&mut vault, "x", ItemKind::Note, &FieldLabels::default());
    assert_eq!(result, Err(ChangeKindError::UnknownKind));
    assert_eq!(vault["items"][0], item);
}

#[test]
fn item_sem_kind_e_erro() {
    let item = json!({"id": "x", "name": "n"});
    let result = change_kind(&item, ItemKind::Note, &FieldLabels::default());
    assert_eq!(result, Err(ChangeKindError::UnknownKind));
}

#[test]
fn chave_desconhecida_com_valor_no_objeto_tipado_e_erro() {
    let item = json!({
        "id": "x",
        "kind": "login",
        "name": "n",
        "login": {"username": "u", "password": "p", "uris": [], "totp": "", "extra": "nao-some"}
    });
    let mut vault = json!({"items": [item.clone()]});
    let result = change_item_kind(&mut vault, "x", ItemKind::Note, &FieldLabels::default());
    assert_eq!(result, Err(ChangeKindError::UnknownField { object: "login", field: "extra".into() }));
    assert_eq!(vault["items"][0], item);
}

#[test]
fn chave_desconhecida_vazia_nao_bloqueia() {
    let item = json!({
        "id": "x",
        "kind": "login",
        "name": "n",
        "login": {"username": "u", "extra": "  ", "outra": [], "outro": null}
    });
    let converted = change_kind(&item, ItemKind::Note, &FieldLabels::default()).unwrap();
    assert_eq!(converted["kind"], "note");
    assert_eq!(converted["customFields"][0]["value"], "u");
}

#[test]
fn item_inexistente_devolve_false_sem_mexer_no_cofre() {
    let mut vault = json!({"items": []});
    let before = vault.clone();
    assert_eq!(change_item_kind(&mut vault, "nada", ItemKind::Note, &FieldLabels::default()), Ok(false));
    assert_eq!(vault, before);
}

#[test]
fn aplica_no_cofre_pelo_id_sem_tocar_nos_outros_itens() {
    let mut vault = json!({
        "version": 1,
        "folders": [{"id": "f1", "name": "Pessoal"}],
        "items": [
            {"id": "a", "kind": "login", "name": "A", "login": {"username": "u", "password": "p", "uris": [], "totp": ""}},
            {"id": "b", "kind": "note", "name": "B", "notes": "fica"}
        ]
    });
    assert_eq!(change_item_kind(&mut vault, "a", ItemKind::Note, &FieldLabels::default()), Ok(true));
    assert_eq!(vault["items"][0]["kind"], "note");
    assert!(vault["items"][0].get("login").is_none());
    assert_eq!(vault["items"][1], json!({"id": "b", "kind": "note", "name": "B", "notes": "fica"}));
    assert_eq!(vault["folders"][0]["name"], "Pessoal");
}

#[test]
fn uris_vazias_saem_da_numeracao() {
    let item = json!({
        "id": "x",
        "kind": "login",
        "name": "n",
        "login": {"username": "", "password": "", "uris": ["https://a", "  ", "", "https://b", "https://c"], "totp": ""}
    });
    let converted = change_kind(&item, ItemKind::Note, &FieldLabels::default()).unwrap();
    let fields = converted["customFields"].as_array().unwrap_or_else(|| unreachable!("gerou campos"));
    assert_eq!(names(fields), ["Website", "Website 2", "Website 3"]);
}

#[test]
fn valores_vazios_nao_geram_campos() {
    let item = json!({
        "id": "x",
        "kind": "card",
        "name": "n",
        "card": {"cardholder": "", "brand": "  ", "number": "", "exp": "", "code": ""}
    });
    let converted = change_kind(&item, ItemKind::Note, &FieldLabels::default()).unwrap();
    assert!(converted.get("customFields").is_none(), "customFields vazio não é criado");
}

#[test]
fn rotulo_vazio_cai_no_padrao_em_ingles() {
    let labels = FieldLabels { username: "  ".into(), password: String::new(), ..FieldLabels::english() };
    let item = json!({"id": "x", "kind": "login", "name": "n", "login": {"username": "u", "password": "p"}});
    let converted = change_kind(&item, ItemKind::Note, &labels).unwrap();
    let fields = converted["customFields"].as_array().unwrap_or_else(|| unreachable!("gerou campos"));
    let got = names(fields);
    assert_eq!(got, ["Username", "Password"]);
}

#[test]
fn rotulos_do_chamador_chegam_aos_campos() {
    let labels = FieldLabels { card_number: "Número do cartão".into(), ..FieldLabels::english() };
    let item = json!({"id": "x", "kind": "card", "name": "n", "card": {"number": "4111"}});
    let converted = change_kind(&item, ItemKind::Note, &labels).unwrap();
    let fields = converted["customFields"].as_array().unwrap_or_else(|| unreachable!("gerou campos"));
    assert_eq!(names(fields), ["Número do cartão"]);
}

#[test]
fn sobra_de_outro_tipo_tambem_vira_campo_extra() {
    let item = json!({
        "id": "x",
        "kind": "note",
        "name": "n",
        "login": {"username": "u", "password": "p", "uris": [], "totp": ""},
        "card": {"cardholder": "h", "brand": "b", "number": "1", "exp": "e", "code": "c"}
    });
    let converted = change_kind(&item, ItemKind::Identity, &FieldLabels::default()).unwrap();
    let fields = converted["customFields"].as_array().unwrap_or_else(|| unreachable!("gerou campos"));
    assert_eq!(names(fields), ["Username", "Password", "Cardholder", "Brand", "Card number", "Expiry", "Security code"]);
    assert!(converted.get("login").is_none());
    assert!(converted.get("card").is_none());
}

#[test]
fn objeto_do_tipo_novo_fica_como_esta() {
    let item = json!({
        "id": "x",
        "kind": "note",
        "name": "n",
        "login": {"username": "u", "chaveDesconhecida": "fica"},
        "card": {"cardholder": "h", "brand": "b", "number": "1", "exp": "e", "code": "c"}
    });
    let converted = change_kind(&item, ItemKind::Login, &FieldLabels::default()).unwrap();
    assert_eq!(converted["login"], json!({"username": "u", "chaveDesconhecida": "fica"}));
    assert!(converted.get("card").is_none());
    assert_eq!(converted["customFields"][0]["name"], "Cardholder");
}

#[test]
fn json_invalido_e_tipo_desconhecido_pela_entrada_de_json() {
    let labels = FieldLabels::default();
    assert!(matches!(change_kind_json("não é json", "note", &labels), Err(ChangeKindError::InvalidJson(_))));
    assert_eq!(change_kind_json(r#"{"id":"x","kind":"login"}"#, "otp", &labels), Err(ChangeKindError::UnknownKind));

    let out = change_kind_json(r#"{"id":"x","kind":"login","name":"n","login":{"username":"u"}}"#, "note", &labels).unwrap();
    let value: Value = serde_json::from_str(&out).unwrap();
    assert_eq!(value["kind"], "note");
    assert_eq!(value["customFields"][0]["name"], "Username");
}
