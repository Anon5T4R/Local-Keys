//! Testes de UI sem janela (egui_kittest) da casca da suíte: o botão de tema, a engrenagem, a lupa, as
//! Configurações e a paleta. Nenhum teste toca o config.json real nem a rede.

use std::path::{Path, PathBuf};

use craft_icons::ThemeMode;
use egui::accesskit::{Role, Toggled};
use egui_kittest::Harness;
use egui_kittest::kittest::{NodeT, Queryable};

use crate::prefs::Prefs;

use crate::{FormCustomField, KeysApp, NewKind};

/// Pasta temporária própria do teste (o config.json de teste vai para lá).
fn scratch(name: &str) -> PathBuf {
    let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_nanos());
    std::env::temp_dir().join(format!("local-keys-ui-{name}-{nanos}"))
}

fn app(name: &str, prefs: Prefs) -> KeysApp {
    let mut app = KeysApp::bare(prefs);
    app.config_file = scratch(name).join("config.json");
    app
}

/// Um cofre destrancado com itens (favorito ★, não favorito ☆, com TOTP), para os testes verem a tela de verdade.
fn unlocked(name: &str, prefs: Prefs) -> KeysApp {
    let mut app = app(name, prefs);
    let mut raw: serde_json::Value = serde_json::from_str(crate::vault::EMPTY_VAULT).expect("cofre vazio");
    for (item, user, totp) in [("192.168.1.34", "taylor", ""), ("account.matrix.org", "hades66669", "JBSWY3DPEHPK3PXP"), ("1lib.sk", "hades", "")] {
        let form = crate::vault::LoginForm { name: item, username: user, password: "x", totp, url: "", notes: "" };
        crate::vault::add_login(&mut raw, form);
    }
    raw["items"][0]["favorite"] = serde_json::Value::Bool(true);
    app.path = "C:\\Users\\qa\\Desktop\\meu-cofre.tkeys".into();
    app.adopt(crate::crypto::test_session(), raw);
    app
}

/// Um cofre destrancado com um item de CADA tipo (login com TOTP, cartão, identidade, nota), para os
/// testes dos resumos da lista e da edição por tipo.
fn unlocked_kinds(name: &str, prefs: Prefs) -> KeysApp {
    let mut app = app(name, prefs);
    let mut raw: serde_json::Value = serde_json::from_str(crate::vault::EMPTY_VAULT).expect("cofre vazio");
    crate::vault::add_login(
        &mut raw,
        crate::vault::LoginForm { name: "Gmail", username: "joao", password: "x", totp: "JBSWY3DPEHPK3PXP", url: "", notes: "" },
    );
    crate::vault::add_card(
        &mut raw,
        crate::vault::CardForm {
            name: "Nubank",
            cardholder: "João da Silva",
            brand: "Elo",
            number: "6362 9700-0045 7013",
            exp: "01/2027",
            code: "123",
            notes: "",
        },
    );
    crate::vault::add_identity(
        &mut raw,
        crate::vault::IdentityForm {
            name: "Documentos",
            first_name: "João",
            last_name: "Silva",
            email: "joao.silva@exemplo.com",
            phone: "+55 11 90000-0000",
            address: "Rua A, 123",
            notes: "",
        },
    );
    crate::vault::add_note(&mut raw, crate::vault::NoteForm { name: "Lista do mercado", notes: "\n   \ncomprar pão\nlevar o note" });
    app.path = "C:\\Users\\qa\\Desktop\\meu-cofre.tkeys".into();
    app.adopt(crate::crypto::test_session(), raw);
    app
}

/// Um cofre destrancado com [count] logins: a lista fica mais alta que a janela.
fn unlocked_with(count: usize, name: &str, prefs: Prefs) -> KeysApp {
    let mut app = app(name, prefs);
    let mut raw: serde_json::Value = serde_json::from_str(crate::vault::EMPTY_VAULT).expect("cofre vazio");
    for i in 0..count {
        let nome = format!("conta-{i:02}.exemplo.com");
        let form = crate::vault::LoginForm { name: &nome, username: "joao", password: "x", totp: "", url: "", notes: "" };
        crate::vault::add_login(&mut raw, form);
    }
    app.path = "C:\\Users\\qa\\Desktop\\meu-cofre.tkeys".into();
    app.adopt(crate::crypto::test_session(), raw);
    app
}

/// O id do item pelo nome (os helpers de cofre criam com id gerado).
fn item_id(app: &KeysApp, name: &str) -> String {
    app.raw
        .as_ref()
        .and_then(|r| r.get("items"))
        .and_then(|i| i.as_array())
        .and_then(|arr| arr.iter().find(|it| it.get("name").and_then(|v| v.as_str()) == Some(name)))
        .and_then(|it| it.get("id"))
        .and_then(|v| v.as_str())
        .expect("item existe")
        .to_string()
}

/// O texto de um nó: o rótulo, ou o valor quando o texto mora lá (TextRun carrega no valor).
fn node_text(node: &egui_kittest::Node<'_>) -> String {
    if let Some(label) = node.accesskit_node().label() {
        return label.to_string();
    }
    node.accesskit_node().data().value().unwrap_or_default().to_string()
}

// O kittest falha se algum texto da tela cair numa fonte sem o glyph (a "caixinha"): é o guarda dos emojis.
fn harness_wh(app: KeysApp, width: f32, height: f32) -> Harness<'static, KeysApp> {
    Harness::builder().with_size(egui::vec2(width, height)).with_step_dt(1.0 / 60.0).build_ui_state(
        |ui, app: &mut KeysApp| {
            egui_extras::install_image_loaders(ui.ctx());
            // O idioma vale por thread (a interface roda numa só): cada teste fixa o seu a cada quadro.
            crate::i18n::set_language(&app.prefs.language);
            crate::theme::apply(ui.ctx(), app.prefs.mode());
            app.frame(ui);
        },
        app,
    )
}

fn harness_sized(app: KeysApp, width: f32) -> Harness<'static, KeysApp> {
    harness_wh(app, width, 640.0)
}

fn harness(app: KeysApp) -> Harness<'static, KeysApp> {
    harness_sized(app, 480.0)
}

/// Os botões só-ícone da barra de cima, da esquerda para a direita: lupa, tema, engrenagem.
fn bar_buttons(harness: &Harness<'_, KeysApp>) -> Vec<egui::Rect> {
    let mut rects: Vec<egui::Rect> =
        harness.get_all_by_role(Role::Button).map(|node| node.rect()).filter(|rect| rect.min.y < 40.0 && rect.width() < 40.0).collect();
    rects.sort_by(|a, b| a.min.x.total_cmp(&b.min.x));
    rects
}

fn click_at(harness: &mut Harness<'_, KeysApp>, pos: egui::Pos2) {
    let push = |harness: &mut Harness<'_, KeysApp>, event: egui::Event| harness.input_mut().events.push(event);
    push(harness, egui::Event::PointerMoved(pos));
    harness.run_steps(1);
    let button = egui::PointerButton::Primary;
    push(harness, egui::Event::PointerButton { pos, button, pressed: true, modifiers: egui::Modifiers::default() });
    harness.run_steps(1);
    push(harness, egui::Event::PointerButton { pos, button, pressed: false, modifiers: egui::Modifiers::default() });
    harness.run_steps(2);
}

fn click_bar(harness: &mut Harness<'_, KeysApp>, index: usize) {
    let rects = bar_buttons(harness);
    assert_eq!(rects.len(), 3, "a barra tem lupa, tema e engrenagem: {rects:?}");
    click_at(harness, rects[index].center());
}

const SEARCH: usize = 0;
const THEME: usize = 1;
const GEAR: usize = 2;

fn press(harness: &mut Harness<'_, KeysApp>, key: egui::Key, modifiers: egui::Modifiers) {
    for pressed in [true, false] {
        harness.input_mut().events.push(egui::Event::Key { key, physical_key: None, pressed, repeat: false, modifiers });
    }
    harness.run_steps(2);
}

/// O clique numa linha da lista, com os modificadores do Ctrl/Shift (o mesmo caminho do ponteiro).
fn click_row(harness: &mut Harness<'_, KeysApp>, name: &str, modifiers: egui::Modifiers) {
    {
        let node = harness.get_by_label_contains(name);
        node.click_modifiers(modifiers);
    }
    harness.run_steps(2);
}

/// Um cofre destrancado com uma pasta a mais (os testes de mover para pasta).
fn unlocked_folder(name: &str, folder: &str, prefs: Prefs) -> KeysApp {
    let mut app = unlocked(name, prefs);
    let mut raw = app.raw.clone().expect("cofre aberto");
    crate::vault::add_folder(&mut raw, folder);
    app.adopt(crate::crypto::test_session(), raw);
    app
}

/// Um cofre destrancado com uma pasta e TODOS os itens já nela (o teste de mover para a pasta em que já estão).
fn unlocked_in_folder(name: &str, folder: &str, prefs: Prefs) -> (KeysApp, String) {
    let mut app = unlocked(name, prefs);
    let mut raw = app.raw.clone().expect("cofre aberto");
    crate::vault::add_folder(&mut raw, folder);
    let folder_id = crate::vault::folders(&raw).into_iter().next().map(|f| f.id).expect("pasta criada");
    if let Some(items) = raw.get_mut("items").and_then(serde_json::Value::as_array_mut) {
        for item in items {
            item["folderId"] = serde_json::Value::String(folder_id.clone());
        }
    }
    app.adopt(crate::crypto::test_session(), raw);
    (app, folder_id)
}

/// O item de um menu (a pasta tem o mesmo nome do chip da toolbar; o do popup é o último da árvore).
fn click_menu_item(harness: &mut Harness<'_, KeysApp>, label: &str) {
    {
        let node = harness.get_all_by_label(label).last().expect("item do menu");
        node.click();
    }
    harness.run_steps(2);
}

/// Clica no BOTÃO com esse rótulo (quando o mesmo texto também é título/heading, como no modal de exportar).
fn click_labelled_button(harness: &mut Harness<'_, KeysApp>, label: &str) {
    {
        let node = harness
            .get_all_by_label(label)
            .find(|node| node.accesskit_node().role() == Role::Button)
            .unwrap_or_else(|| panic!("botão '{label}' não encontrado"));
        node.click();
    }
    harness.run_steps(2);
}

/// Grava o cofre do app no caminho de teste e devolve a contagem de gravações (a base do "uma gravação só").
fn persist_now(app: &mut KeysApp) -> usize {
    let raw = app.raw.clone().expect("cofre aberto");
    assert!(app.persist(raw), "o cofre de teste grava: {:?}", app.err);
    app.persist_count
}

/// Espera o job (thread real) terminar e drena o resultado, com prazo — o job acorda a UI com `request_repaint`.
fn wait_for_job(harness: &mut Harness<'_, KeysApp>) {
    // O clique que dispara o job é processado no frame: roda um par de frames ANTES de esperar o `job_label`.
    harness.run_steps(2);
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
    while harness.state().job_label.is_some() {
        assert!(std::time::Instant::now() < deadline, "o job não terminou no prazo");
        std::thread::sleep(std::time::Duration::from_millis(20));
        harness.step();
    }
    harness.run_steps(2);
}

fn shot(harness: &mut Harness<'_, KeysApp>, name: &str) {
    if std::env::var_os("LOCALKEYS_QA_RENDER").is_none() {
        return;
    }
    let directory = Path::new(env!("CARGO_MANIFEST_DIR")).join("target/qa-casca");
    if std::fs::create_dir_all(&directory).is_err() {
        return;
    }
    if let Ok(image) = harness.render() {
        let _ = image.save(directory.join(format!("{name}.png")));
    }
}

/// O botão de tema da barra gira Automático -> Claro -> Escuro -> Automático e grava a escolha.
#[test]
fn the_theme_button_cycles_the_three_modes() {
    let mut harness = harness(app("theme", Prefs::default()));
    assert_eq!(harness.state().prefs.mode(), ThemeMode::System);
    click_bar(&mut harness, THEME);
    assert_eq!(harness.state().prefs.mode(), ThemeMode::Light);
    click_bar(&mut harness, THEME);
    assert_eq!(harness.state().prefs.mode(), ThemeMode::Dark);
    click_bar(&mut harness, THEME);
    assert_eq!(harness.state().prefs.mode(), ThemeMode::System);
    // Cada troca foi gravada: o arquivo de teste (nunca o real) guarda o modo atual.
    let saved = Prefs::load_from(&harness.state().config_file);
    assert_eq!(saved.mode(), ThemeMode::System);
}

/// Uma preferência de tema gravada por versões antigas (id que a casca não conhece) abre no Automático.
#[test]
fn an_old_theme_id_falls_back_to_automatic() {
    let dir = scratch("old-theme");
    std::fs::create_dir_all(&dir).expect("temp dir");
    let file = dir.join("config.json");
    std::fs::write(&file, r#"{"theme":"PunkPrincess","lang":"En"}"#).expect("write config");
    let prefs = Prefs::load_from(&file);
    assert_eq!(prefs.mode(), ThemeMode::System);
    assert_eq!(prefs.language, "en");
}

/// A engrenagem abre as Configurações; lá o idioma troca e o tema vira pelas três opções.
#[test]
fn the_gear_opens_settings_with_language_and_theme() {
    let mut harness = harness(app("gear", Prefs::new("en", ThemeMode::Dark)));
    click_bar(&mut harness, GEAR);
    assert!(harness.state().settings_open);
    harness.get_by_label("About Local Keys");
    shot(&mut harness, "settings-dark");
    harness.get_by_label("Light").click();
    harness.run_steps(2);
    assert_eq!(harness.state().prefs.mode(), ThemeMode::Light);
    shot(&mut harness, "settings-light");
    harness.get_by_label("Automatic").click();
    harness.run_steps(2);
    assert_eq!(harness.state().prefs.mode(), ThemeMode::System);
    // O idioma sai da barra e mora aqui: o combo troca para o português.
    harness.get_by_role(Role::ComboBox).click();
    harness.run_steps(2);
    harness.get_by_label("Português (Brasil)").click();
    harness.run_steps(2);
    assert_eq!(harness.state().prefs.language, "pt-br");
    harness.get_by_label("Sobre o Local Keys");
    // Esc fecha a janela.
    press(&mut harness, egui::Key::Escape, egui::Modifiers::NONE);
    assert!(!harness.state().settings_open);
}

/// A lupa e o Ctrl+K abrem a paleta; Enter roda o comando escolhido.
#[test]
fn the_search_icon_and_ctrl_k_open_the_palette_and_run_a_command() {
    let mut harness = harness(app("palette", Prefs::new("en", ThemeMode::Light)));
    assert!(!harness.state().palette.is_open());
    click_bar(&mut harness, SEARCH);
    assert!(harness.state().palette.is_open());
    shot(&mut harness, "palette-light");
    press(&mut harness, egui::Key::Escape, egui::Modifiers::NONE);
    assert!(!harness.state().palette.is_open());

    press(&mut harness, egui::Key::K, egui::Modifiers::COMMAND);
    assert!(harness.state().palette.is_open());
    harness.input_mut().events.push(egui::Event::Text("about".into()));
    harness.run_steps(2);
    press(&mut harness, egui::Key::Enter, egui::Modifiers::NONE);
    assert!(!harness.state().palette.is_open());
    assert!(harness.state().settings_open, "About abre as Configurações");
}

/// Com o cofre trancado a paleta oferece só destrancar, configurações, atualizações, sobre e sair.
#[test]
fn a_locked_vault_gets_no_vault_commands_in_the_palette() {
    let app = app("locked", Prefs::default());
    assert!(app.locked());
    let ids: Vec<String> = app.commands().into_iter().map(|command| command.id).collect();
    for id in &ids {
        let allowed = id == "vault.unlock" || id.starts_with("settings.") || id.starts_with("help.") || id == "app.exit";
        assert!(allowed, "comando fora do conjunto do cofre trancado: {id}");
    }
    for needed in ["vault.unlock", "settings.open", "help.updates", "help.about", "app.exit"] {
        assert!(ids.iter().any(|id| id == needed), "falta {needed}");
    }
    // Sem caminho nem senha, destrancar aparece mas não roda.
    let unlock = app.commands().into_iter().find(|command| command.id == "vault.unlock").expect("unlock is listed");
    assert!(!unlock.enabled);
}

/// "Exit" na paleta pede o encerramento pelo caminho da bandeja (a flag que o frame vira em Close).
#[test]
fn exit_in_the_palette_raises_the_quit_flag() {
    let mut harness = harness(app("exit", Prefs::new("en", ThemeMode::Dark)));
    press(&mut harness, egui::Key::K, egui::Modifiers::COMMAND);
    harness.input_mut().events.push(egui::Event::Text("exit".into()));
    harness.run_steps(2);
    press(&mut harness, egui::Key::Enter, egui::Modifiers::NONE);
    assert!(harness.state().quit.load(std::sync::atomic::Ordering::Relaxed));
}

/// Os três botões da barra continuam inteiros (sem cobrir "Local Keys") na largura estreita.
#[test]
fn the_top_bar_fits_a_narrow_window() {
    let mut harness = harness_sized(app("narrow", Prefs::new("pt-br", ThemeMode::Light)), 340.0);
    harness.run_steps(2);
    let rects = bar_buttons(&harness);
    assert_eq!(rects.len(), 3);
    for rect in &rects {
        assert!(rect.max.x <= 340.0 && rect.min.x >= 0.0, "botão fora da janela: {rect:?}");
    }
    shot(&mut harness, "narrow-light");
}

/// Na largura mínima os campos da tela de destrancar cabem na janela.
#[test]
fn the_unlock_fields_fit_the_narrow_window() {
    let mut harness = harness_sized(app("unlock-narrow", Prefs::new("pt-br", ThemeMode::Light)), 340.0);
    harness.run_steps(2);
    let fields: Vec<egui::Rect> =
        harness.get_all_by_role(Role::TextInput).chain(harness.get_all_by_role(Role::PasswordInput)).map(|node| node.rect()).collect();
    assert_eq!(fields.len(), 5, "dois caminhos e três senhas: {fields:?}");
    for rect in &fields {
        assert!(rect.min.x >= 0.0 && rect.max.x <= 340.0, "campo fora da janela: {rect:?}");
    }
}

/// Os campos de senha se anunciam pelo rótulo (leitor de tela).
#[test]
fn the_password_fields_have_names() {
    let harness = harness(app("unlock-names", Prefs::new("en", ThemeMode::Dark)));
    let names: Vec<String> = harness.get_all_by_role(Role::PasswordInput).map(|node| node.accesskit_node().label().unwrap_or_default()).collect();
    assert_eq!(names.len(), 3);
    assert!(names.iter().all(|name| !name.is_empty()), "campo de senha sem nome: {names:?}");
}

/// Com o cofre aberto, a paleta inteira fica dentro da janela estreita do teste (o caso do print de
/// 2026-11-20), nos dois eixos.
#[test]
fn the_palette_fits_a_narrow_window_over_the_vault() {
    const HEIGHT: f64 = 640.0;
    for width in [340.0, 500.0] {
        let mut harness = harness_sized(unlocked("palette-narrow", Prefs::new("en", ThemeMode::Dark)), width);
        harness.run_steps(2);
        click_bar(&mut harness, SEARCH);
        let rows: Vec<egui::Rect> = harness.get_all_by_role(Role::ListBoxOption).map(|node| node.rect()).collect();
        assert!(!rows.is_empty());
        for rect in &rows {
            assert!(
                rect.min.x >= 0.0 && rect.max.x <= width && rect.min.y >= 0.0 && rect.max.y <= HEIGHT as f32,
                "linha da paleta fora da janela de {width}: {rect:?}"
            );
        }
    }
}

/// Na largura mínima o caminho do cofre corta antes do "Lock" em vez de passar por baixo dele.
#[test]
fn the_vault_path_stops_before_the_buttons() {
    let mut harness = harness_sized(unlocked("path-narrow", Prefs::new("en", ThemeMode::Light)), 340.0);
    harness.run_steps(2);
    let path = harness.get_by_label_contains("meu-cofre.tkeys").rect();
    let lock = harness.get_by_label("Lock").rect();
    assert!(path.max.x <= lock.min.x, "o caminho {path:?} passa por baixo do Lock {lock:?}");
}

/// O ⋯ do cabeçalho do cofre (o único botão só-ícone da linha do caminho).
fn click_more(harness: &mut Harness<'_, KeysApp>) {
    let rect = harness
        .get_all_by_role(Role::Button)
        .map(|node| node.rect())
        .find(|rect| rect.min.y > 40.0 && rect.min.y < 80.0 && rect.width() < 40.0)
        .expect("o ⋯ está no cabeçalho do cofre");
    click_at(harness, rect.center());
}

/// Importar, exportar e trocar a senha mestra saem da linha de ferramentas e moram no ⋯; criar pasta fica em Pastas.
#[test]
fn rare_vault_actions_live_in_the_more_menu() {
    let mut harness = harness_sized(unlocked("more", Prefs::new("en", ThemeMode::Light)), 340.0);
    harness.run_steps(2);
    for gone in ["Import", "Export", "Change master password", "New folder"] {
        assert!(harness.query_by_label(gone).is_none(), "{gone} ainda está na linha de ferramentas");
    }
    click_more(&mut harness);
    harness.get_by_label("Import…");
    harness.get_by_label("Export…");
    harness.get_by_label("Merge another vault…");
    harness.get_by_label("Change master password…").click();
    harness.run_steps(2);
    assert!(harness.state().change_open);
}

/// Todo diálogo do cofre SEM área de rolagem cabe na janela do teste (340×640): nenhum controle passa
/// da borda em nenhum dos dois eixos. Os formulários de item (que rolam) ficam no teste irmão
/// [`every_form_dialog_keeps_its_chrome_in_the_narrow_window`].
#[test]
fn every_dialog_fits_the_narrow_window() {
    type Open = fn(&mut KeysApp);
    let dialogs: [(&str, Open); 9] = [
        ("new-kind", |app| app.new_kind_open = true),
        ("generator", |app| app.gen_open = true),
        ("change", |app| app.change_open = true),
        ("import", |app| app.import_open = true),
        ("export", |app| app.export_open = true),
        ("folders", |app| app.folders_open = true),
        ("new-folder", |app| app.folder_new_open = true),
        ("trash", |app| app.trash_open = true),
        ("sync", |app| {
            app.sync_plan = Some(crate::SyncPlan {
                other_path: r"C:\Users\joao\OneDrive\cofre.tkeys".into(),
                merged: app.raw.clone().expect("cofre"),
                summary: crate::merge::MergeSummary { added: 1, to_other: 1, ..Default::default() },
            });
            app.sync_open = true;
        }),
    ];
    const WIDTH: f64 = 340.0;
    const HEIGHT: f64 = 640.0;
    for (name, open) in dialogs {
        let mut app = unlocked("dialogs", Prefs::new("pt-br", ThemeMode::Dark));
        open(&mut app);
        let mut harness = harness_sized(app, WIDTH as f32);
        harness.run_steps(3);
        shot(&mut harness, &format!("dialog-{name}-340"));
        for node in harness.root().children_recursive() {
            let Some(rect) = node.accesskit_node().bounding_box() else { continue };
            if rect.width() > 0.0 {
                assert!(
                    rect.x0 >= 0.0 && rect.x1 <= WIDTH && rect.y0 >= 0.0 && rect.y1 <= HEIGHT,
                    "{name}: {:?} fora da janela: {rect:?}",
                    node.accesskit_node().role()
                );
            }
        }
    }
}

/// Os formulários de item (login, cartão, identidade, nota, item livre) têm área central com rolagem:
/// o conteúdo que passa da dobra existe no LAYOUT além da borda da janela (o ScrollArea pinta só o
/// viewport), então aqui valem duas regras: largura para todos os nós, e altura para o que fica FORA
/// da rolagem — o título, o crachá do tipo e os botões Salvar/Cancelar, que precisam estar sempre à vista.
#[test]
fn every_form_dialog_keeps_its_chrome_in_the_narrow_window() {
    type Open = fn(&mut KeysApp);
    // (nome do print, rótulo do crachá do tipo — o teste roda em pt-br)
    let forms: [(&str, &str, Open); 7] = [
        ("add", "Tipo: Login", |app| app.open_add_form(NewKind::Login)),
        ("add-with-attachments", "Tipo: Login", |app| {
            app.open_add_form(NewKind::Login);
            for name in ["contrato-assinado-2026.pdf", "chave-de-recuperacao.txt"] {
                app.form_attachments.push(pending(name));
            }
        }),
        ("card", "Tipo: Cartão", |app| app.open_add_form(NewKind::Card)),
        ("card-custom", "Tipo: Cartão", |app| {
            app.open_add_form(NewKind::Card);
            app.add_number = "6362970000457013".into();
            app.add_exp = "01/2027".into();
            app.form_custom.push(FormCustomField {
                id: String::new(),
                name: "PIN do cartão".into(),
                value: "1234".into(),
                hidden: true,
                revealed: false,
            });
        }),
        ("identity", "Tipo: Identidade", |app| app.open_add_form(NewKind::Identity)),
        ("note", "Tipo: Nota", |app| app.open_add_form(NewKind::Note)),
        ("free-item", "Tipo: Nota", |app| app.open_add_form(NewKind::FreeItem)),
    ];
    const WIDTH: f64 = 340.0;
    const HEIGHT: f64 = 640.0;
    for (name, badge_label, open) in forms {
        let mut app = unlocked("forms", Prefs::new("pt-br", ThemeMode::Dark));
        open(&mut app);
        let mut harness = harness_sized(app, WIDTH as f32);
        harness.run_steps(3);
        shot(&mut harness, &format!("form-{name}-340"));
        for node in harness.root().children_recursive() {
            let Some(rect) = node.accesskit_node().bounding_box() else { continue };
            if rect.width() > 0.0 {
                assert!(rect.x0 >= 0.0 && rect.x1 <= WIDTH, "{name}: nó fora da largura: {rect:?}");
            }
        }
        // O que fica fora da rolagem (título, crachá do tipo, Salvar, Cancelar) cabe na altura — o
        // conteúdo além da dobra é que rola (só existe além da borda no layout, invisível). Valem TODOS
        // os nós com esses rótulos (o "Adicionar" da barra de fundo entra junto, e também precisa caber).
        for label in ["Adicionar", "Salvar", "Cancelar"] {
            for node in harness.get_all_by_label(label) {
                let rect = node.rect();
                assert!(rect.min.y >= 0.0 && rect.max.y <= HEIGHT as f32, "{name}: {label} fora da janela: {rect:?}");
            }
        }
        let badge = harness.get_by_label_contains(badge_label).rect();
        assert!(badge.min.y >= 0.0 && badge.max.y <= HEIGHT as f32, "{name}: crachá {badge_label} fora da janela: {badge:?}");
    }
}

/// Edição com a troca de tipo pendente (os dois avisos) e uma mensagem de erro: os avisos ficam fora da
/// rolagem, então na janela mínima (340×640) os botões Salvar/Cancelar têm de continuar à vista.
#[test]
fn the_change_type_form_keeps_its_buttons_in_the_narrow_window() {
    let mut app = unlocked_kinds("change-narrow", Prefs::new("pt-br", ThemeMode::Dark));
    let id = item_id(&app, "Gmail");
    app.open_edit_form(&id);
    app.form_target_kind = Some(crate::vault::ItemKind::Note);
    app.form_err = Some("a gravação falhou".into());
    let mut harness = harness_sized(app, 340.0);
    harness.run_steps(3);
    shot(&mut harness, "form-change-narrow");
    for node in harness.root().children_recursive() {
        let Some(rect) = node.accesskit_node().bounding_box() else { continue };
        if rect.width() > 0.0 {
            assert!(rect.x0 >= 0.0 && rect.x1 <= 340.0, "nó fora da largura: {rect:?}");
        }
    }
    for label in ["Salvar", "Cancelar"] {
        for node in harness.get_all_by_label(label) {
            let rect = node.rect();
            assert!(rect.min.y >= 0.0 && rect.max.y <= 640.0, "{label} fora da janela: {rect:?}");
        }
    }
}

/// O painel de anexos de um item (aberto pela lista) com nome longo, anexos vivos e apagados: na janela de
/// 500 px e na mínima de 340, título, nomes, tamanho e todos os botões ficam inteiros e dentro da janela,
/// em todos os idiomas do app (os rótulos mudam de tamanho). Na janela larga, cada anexo continua numa
/// linha só (nome ao lado dos botões).
#[test]
fn the_attachments_panel_fits_the_narrow_window() {
    const HEIGHT: f64 = 640.0;
    const ITEM: &str = "Intranet corporativa do grupo com um nome bem comprido";
    const NAMES: [&str; 2] = ["foto-do-documento-de-identidade-2026-frente.jpg", "nota.txt"];
    const TRASHED: &str = "relatorio-anual-assinado-e-carimbado-pelo-contador.pdf";
    // (idioma, título, [Salvar, Excluir anexo, Restaurar, Excluir de vez, Adicionar anexo])
    const LANGS: [(&str, &str, [&str; 5]); 3] = [
        ("en", "Attachments", ["Save", "Delete attachment", "Restore", "Delete forever", "Add attachment"]),
        ("pt-br", "Anexos", ["Salvar", "Excluir anexo", "Restaurar", "Excluir de vez", "Adicionar anexo"]),
        ("es", "Adjuntos", ["Guardar", "Eliminar adjunto", "Restaurar", "Eliminar para siempre", "Añadir adjunto"]),
    ];
    for (lang, title, [save, del, restore, del_forever, add_label]) in LANGS {
        for width in [900.0, 500.0, 340.0] {
            let mut app = app("attachments", Prefs::new(lang, ThemeMode::Dark));
            let mut raw: serde_json::Value = serde_json::from_str(crate::vault::EMPTY_VAULT).expect("cofre vazio");
            crate::vault::add_login(&mut raw, crate::vault::LoginForm { name: ITEM, username: "joao", password: "x", totp: "", url: "", notes: "" });
            let item = raw.get_mut("items").and_then(|i| i.get_mut(0)).expect("item");
            item["attachments"] = serde_json::json!([
                { "id": "a1", "name": NAMES[0], "size": 2048 },
                { "id": "a2", "name": NAMES[1], "size": 12 },
                { "id": "a3", "name": TRASHED, "size": 4096, "deletedAt": 10 }
            ]);
            app.path = "C:\\Users\\qa\\Desktop\\meu-cofre.tkeys".into();
            app.adopt(crate::crypto::test_session(), raw);
            let id = item_id(&app, ITEM);
            app.attach_item = Some(id);
            let mut harness = harness_sized(app, width as f32);
            harness.run_steps(3);
            let case = format!("{lang}/{width}");
            shot(&mut harness, &format!("attachments-panel-{lang}-{width}"));
            let inside = |what: &str, rect: egui::Rect| {
                assert!(rect.min.x >= 0.0 && rect.max.x <= width as f32, "{case}: {what} cortado: {rect:?}");
            };
            inside("título", harness.get_by_label_contains(&format!("{title} — {ITEM}")).rect());
            for name in NAMES {
                let nodes: Vec<_> = harness.get_all_by_label(name).collect();
                assert!(!nodes.is_empty(), "{case}: nome {name} sumiu (truncado demais)");
                for node in &nodes {
                    inside(&format!("nome {name}"), node.rect());
                }
            }
            inside(&format!("nome {TRASHED}"), harness.get_by_label(TRASHED).rect());
            // Um botão por anexo vivo, mais um por anexo na lixeira.
            for (label, expected) in [(save, 2), (del, 2), (restore, 1), (del_forever, 1)] {
                let nodes: Vec<_> = harness.get_all_by_label(label).collect();
                assert_eq!(nodes.len(), expected, "{case}: quantidade de botões {label}");
                for node in &nodes {
                    inside(&format!("botão {label}"), node.rect());
                }
            }
            let add = harness.get_by_label(add_label).rect();
            inside(add_label, add);
            assert!(add.max.y <= HEIGHT as f32, "{case}: fora da altura");
            if width > 500.0 {
                // Janela larga: nome e botões dividem a mesma linha (o tamanho é só para a ordem).
                for (index, name, label) in [(0, NAMES[0], save), (1, NAMES[1], del), (0, TRASHED, restore)] {
                    let text = harness.get_by_label(name).rect();
                    let button = harness.get_all_by_label(label).nth(index).expect("botão").rect();
                    assert!(
                        text.top() <= button.bottom() && button.top() <= text.bottom(),
                        "{case}: {name} e {label} não estão na mesma linha: {text:?} {button:?}"
                    );
                }
            }
        }
    }
}

/// Um anexo já lido do disco, à espera no formulário.
fn pending(name: &str) -> crate::jobs::AttachmentRead {
    crate::jobs::AttachmentRead { item_id: crate::FORM_ATTACH.into(), name: name.into(), mime: "text/plain", size: 1, data_b64: "QQ==".into() }
}

/// O anexo escolhido no formulário entra no item já na criação (antes, só dava para anexar depois de salvar).
#[test]
fn a_new_item_is_saved_with_the_attachments_chosen_in_the_form() {
    let mut app = unlocked("form-attach", Prefs::new("pt-br", ThemeMode::Dark));
    let dir = scratch("form-attach");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    app.open_add_form(NewKind::Login);
    app.add_name = "com anexo".into();
    app.form_attachments.push(pending("nota.txt"));
    app.save_form();
    assert!(!app.form_open, "o formulário fecha ao salvar: {:?}", app.err);
    assert!(app.form_attachments.is_empty(), "a lista do formulário é esvaziada");
    let raw = app.raw.as_ref().expect("cofre aberto");
    let item = raw["items"].as_array().expect("itens").iter().find(|i| i["name"] == "com anexo").expect("o item novo");
    let atts = item["attachments"].as_array().expect("anexos");
    assert_eq!(atts.len(), 1);
    assert_eq!(atts[0]["name"], "nota.txt");
    assert_eq!(atts[0]["dataB64"], "QQ==");
    assert!(std::path::Path::new(&app.path).exists(), "o cofre foi gravado em disco");
}

/// O "+" pergunta o tipo ANTES de abrir o formulário: Login, Cartão, Identidade, Nota e Item livre.
/// Escolher Cartão abre o form do tipo (sem os campos de login).
#[test]
fn the_add_button_asks_the_type_before_the_form() {
    let mut harness = harness(unlocked("new-kind", Prefs::new("en", ThemeMode::Dark)));
    harness.get_by_label("Add").click();
    harness.run_steps(2);
    assert!(harness.state().new_kind_open);
    assert!(!harness.state().form_open, "o form só abre depois da escolha");
    harness.get_by_label("New item");
    for label in ["Login", "Card", "Identity", "Note", "Free item"] {
        harness.get_by_label_contains(label);
    }
    // escolher Cartão abre o formulário do tipo
    harness.get_by_label_contains("Card").click();
    harness.run_steps(2);
    assert!(!harness.state().new_kind_open);
    assert!(harness.state().form_open);
    assert_eq!(harness.state().form_kind, crate::vault::ItemKind::Card);
    // o crachá fixo do tipo e os campos do cartão (titular, bandeira, número, validade, código)
    harness.get_by_label_contains("Type: Card");
    harness.get_by_label("Cardholder");
    harness.get_by_label("Brand");
    harness.get_by_label("Number");
    harness.get_by_label("Expiry (MM/YY)");
    harness.get_by_label("Security code");
}

/// Criar de cada tipo grava o shape certo no cofre (o mesmo que o Android lê): cartão com número só em
/// dígitos e validade MM/AA, identidade nos cinco campos, nota sem login — e o item livre é uma nota com
/// um campo personalizado.
#[test]
fn creating_each_kind_stores_the_right_shape() {
    let mut app = unlocked("kinds", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("kinds");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();

    app.open_add_form(NewKind::Card);
    app.add_name = "Itaú".into();
    app.add_cardholder = "João".into();
    app.add_brand = "Mastercard".into();
    app.add_number = "5555 5555 5555 4444".into();
    app.add_exp = "0330".into();
    app.add_code = "12a4".into();
    app.save_form();
    assert!(!app.form_open, "salvou o cartão: {:?}", app.err);

    app.open_add_form(NewKind::Identity);
    app.add_name = "Passaporte".into();
    app.add_first = "Ana".into();
    app.add_last = "Souza".into();
    app.add_email = "ana@exemplo.com".into();
    app.add_phone = "11 91234-5678".into();
    app.add_address = "Rua B, 45".into();
    app.save_form();

    app.open_add_form(NewKind::Note);
    app.add_name = "Recados".into();
    app.add_notes = "levar o note".into();
    app.save_form();

    // item livre: nota que abre com um campo personalizado vazio
    app.open_add_form(NewKind::FreeItem);
    assert_eq!(app.form_custom.len(), 1, "o campo vazio já vem aberto");
    app.add_name = "Chaves do apartamento".into();
    app.form_custom[0].name = "portão".into();
    app.form_custom[0].value = "9090".into();
    app.form_custom[0].hidden = true;
    app.save_form();

    let raw = app.raw.as_ref().expect("cofre");
    let acha = |nome: &str| raw["items"].as_array().expect("itens").iter().find(|i| i["name"] == nome).expect(nome);
    let card = acha("Itaú");
    assert_eq!(card["kind"], "card");
    assert_eq!(card["card"]["number"], "5555555555554444", "número: só dígitos");
    assert_eq!(card["card"]["exp"], "03/30", "validade vira MM/AA");
    assert_eq!(card["card"]["code"], "124", "código: só dígitos");
    assert!(card.get("login").is_none(), "cartão não carrega login");
    let ident = acha("Passaporte");
    assert_eq!(ident["kind"], "identity");
    assert_eq!(ident["identity"]["firstName"], "Ana");
    assert_eq!(ident["identity"]["address"], "Rua B, 45");
    let note = acha("Recados");
    assert_eq!(note["kind"], "note");
    assert_eq!(note["notes"], "levar o note");
    let free = acha("Chaves do apartamento");
    assert_eq!(free["kind"], "note", "item livre é uma nota");
    assert_eq!(free["customFields"][0]["name"], "portão");
    assert_eq!(free["customFields"][0]["hidden"], true);
    assert!(std::path::Path::new(&app.path).exists(), "o cofre foi gravado em disco");
}

/// O aviso que acompanha a troca de tipo (o texto do `tr("…")` em `main.rs`).
const CONVERSION_WARNING: &str = "Fields that do not exist in the new type become custom fields when you save; hidden fields stay hidden. The fields of the new type only appear after you save and reopen the item.";

/// A segunda frase do aviso, só quando o item tem segredo TOTP e o tipo novo não é login.
const TOTP_WARNING: &str = "The TOTP secret becomes a hidden field, and this item stops generating codes.";

/// O item do cofre pelo id (o `raw` em memória, que o `persist` espelha no disco).
fn item_by_id<'a>(app: &'a KeysApp, id: &str) -> &'a serde_json::Value {
    app.raw.as_ref().expect("cofre").get("items").and_then(|i| i.as_array()).and_then(|items| items.iter().find(|it| it["id"] == id)).expect("item")
}

/// O mesmo item, mutável (o teste prepara o cofre antes de abrir o formulário).
fn item_by_id_mut<'a>(app: &'a mut KeysApp, id: &str) -> &'a mut serde_json::Value {
    app.raw
        .as_mut()
        .expect("cofre")
        .get_mut("items")
        .and_then(|i| i.as_array_mut())
        .and_then(|items| items.iter_mut().find(|it| it["id"] == id))
        .expect("item")
}

/// O valor que o seletor de tipo mostra (o `current_text_value` do ComboBox no AccessKit).
fn selector_text(harness: &Harness<'_, KeysApp>) -> String {
    let combo = harness.get_by_label("Type");
    assert_eq!(combo.accesskit_node().role(), Role::ComboBox, "o tipo da edição é um seletor");
    combo.accesskit_node().data().value().unwrap_or_default().to_string()
}

/// Fecha o popup do seletor de tipo com um clique num ponto neutro e FORA dele (a seção "Custom fields", no
/// fim do corpo). No egui o primeiro clique fora de um popup aberto só fecha o popup (não chega ao botão de
/// baixo) — como na mão, a pessoa clica no formulário antes de Salvar.
fn close_type_popup(harness: &mut Harness<'_, KeysApp>) {
    harness.get_by_label("Custom fields").click();
    harness.run_steps(2);
}

/// Na edição o tipo vira um seletor com as quatro opções, começando no tipo atual do item.
#[test]
fn editing_shows_a_type_selector_with_the_current_type() {
    let mut app = unlocked_kinds("edit-selector", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("edit-selector");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let id = item_id(&app, "Nubank");
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.state_mut().open_edit_form(&id);
    harness.run_steps(2);
    let atual = selector_text(&harness);
    assert!(atual.contains("Card"), "o seletor começa no tipo atual: {atual}");
    // os campos do próprio tipo carregam — o número JÁ em blocos (C.1 vale na edição)
    assert_eq!(harness.state().add_number, "6362 9700 0045 7013", "abre a edição com blocos de 4");
    // as quatro opções estão no popup (o clique de ponteiro não chega ao combo dentro do Modal no
    // kittest — o combo de pasta, que já existia, tem o mesmo comportamento; a ativação por AccessKit
    // é o caminho do leitor de tela e abre o mesmo popup)
    harness.get_by_label("Type").click_accesskit();
    harness.run_steps(2);
    for option in ["🔑 Login", "📝 Note", "💳 Card", "👤 Identity"] {
        harness.get_by_label(option);
    }
    shot(&mut harness, "edit-type-selector");
}

/// Login → nota e salvar: o `kind` vira nota, o objeto `login` sai e os campos dele nascem como campos
/// personalizados (senha e TOTP ocultos). A lista passa a tratar o item como nota.
#[test]
fn changing_a_login_to_a_note_moves_the_login_fields_to_custom_fields() {
    let mut app = unlocked_kinds("change-note", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("change-note");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let id = item_id(&app, "Gmail");
    item_by_id_mut(&mut app, &id)["updatedAt"] = serde_json::json!(1);
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.state_mut().open_edit_form(&id);
    harness.run_steps(2);
    harness.get_by_label("Type").click_accesskit();
    harness.run_steps(2);
    harness.get_by_label("📝 Note").click_accesskit();
    harness.run_steps(2);
    assert_eq!(harness.state().form_kind, crate::vault::ItemKind::Login, "os campos do formulário continuam os do item");
    // O TOTP do Gmail vira campo oculto e o item deixa de gerar código: a frase própria aparece ANTES de salvar.
    assert!(harness.query_by_label(TOTP_WARNING).is_some(), "o aviso do TOTP aparece com segredo e tipo novo sem login");
    close_type_popup(&mut harness);
    harness.get_by_label("Save").click();
    harness.run_steps(2);
    assert!(!harness.state().form_open, "salvou: {:?}", harness.state().err);
    let raw = harness.state().raw.as_ref().expect("cofre");
    let it = raw["items"].as_array().expect("itens").iter().find(|i| i["id"] == id.as_str()).expect("item");
    assert_eq!(it["kind"], "note");
    assert!(it.get("login").is_none(), "o objeto login saiu do item");
    let campos = it["customFields"].as_array().expect("campos extras");
    let campo = |nome: &str| campos.iter().find(|c| c["name"] == nome).unwrap_or_else(|| panic!("campo {nome}: {campos:?}"));
    assert_eq!(campo("Username")["value"], "joao");
    assert_eq!(campo("Password")["value"], "x");
    assert_eq!(campo("Password")["hidden"], true, "a senha continua oculta");
    assert_eq!(campo("TOTP")["hidden"], true, "o segredo TOTP continua oculto");
    assert!(campos.iter().all(|c| c["name"] != "URL"), "URL vazia não vira campo: {campos:?}");
    assert_ne!(it["updatedAt"], serde_json::json!(1), "a gravação carimba updatedAt");
    // a lista já mostra o item sob o tipo novo
    assert_eq!(harness.state().items.iter().find(|i| i.id == id).expect("item na lista").kind, crate::vault::ItemKind::Note);
}

/// Cartão → login e salvar: o objeto `card` sai, os campos dele viram campos personalizados ("Card number"
/// com nome próprio, oculto) e o item passa a ser um login sem objeto — os campos do tipo novo aparecem
/// vazios ao reabrir.
#[test]
fn changing_a_card_to_a_login_moves_the_card_fields_to_custom_fields() {
    let mut app = unlocked_kinds("change-card-login", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("change-card-login");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let id = item_id(&app, "Nubank");
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.state_mut().open_edit_form(&id);
    harness.run_steps(2);
    assert!(harness.query_by_label(TOTP_WARNING).is_none(), "cartão não tem segredo TOTP");
    harness.get_by_label("Type").click_accesskit();
    harness.run_steps(2);
    harness.get_by_label("🔑 Login").click_accesskit();
    harness.run_steps(2);
    close_type_popup(&mut harness);
    harness.get_by_label("Save").click();
    harness.run_steps(2);
    assert!(!harness.state().form_open, "salvou: {:?}", harness.state().err);
    let it = item_by_id(harness.state(), &id);
    assert_eq!(it["kind"], "login");
    assert!(it.get("card").is_none(), "o objeto card saiu do item");
    assert!(it.get("login").is_none(), "o tipo novo nasce sem objeto; os campos dele aparecem vazios ao reabrir");
    let campos = it["customFields"].as_array().expect("campos extras");
    let campo = |nome: &str| campos.iter().find(|c| c["name"] == nome).unwrap_or_else(|| panic!("campo {nome}: {campos:?}"));
    assert_eq!(campo("Cardholder")["value"], "João da Silva");
    assert_eq!(campo("Brand")["value"], "Elo");
    assert_eq!(campo("Card number")["value"], "6362970000457013", "o número do cartão tem nome próprio");
    assert_eq!(campo("Card number")["hidden"], true, "o número continua oculto");
    assert_eq!(campo("Expiry (MM/YY)")["value"], "01/2027");
    assert_eq!(campo("Security code")["hidden"], true, "o código continua oculto");
}

/// Trocar o tipo e voltar ao original antes de salvar não converte nada: o item sai como entrou.
#[test]
fn going_back_to_the_original_type_before_saving_keeps_the_item() {
    let mut app = unlocked_kinds("change-back", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("change-back");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let id = item_id(&app, "Gmail");
    item_by_id_mut(&mut app, &id)["updatedAt"] = serde_json::json!(1);
    let login_antes = item_by_id(&app, &id)["login"].clone();
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.state_mut().open_edit_form(&id);
    harness.run_steps(2);
    harness.get_by_label("Type").click_accesskit();
    harness.run_steps(2);
    harness.get_by_label("📝 Note").click_accesskit();
    harness.run_steps(2);
    assert!(harness.query_by_label(CONVERSION_WARNING).is_some(), "o aviso aparece com o tipo diferente: {:?}", harness.state().form_target_kind);
    // O popup aberto por AccessKit não fecha na escolha (o fechamento é do clique de ponteiro), então a
    // volta ao original escolhe a opção no MESMO popup.
    assert!(harness.query_by_label("🔑 Login").is_some(), "o popup do seletor continua aberto");
    harness.get_by_label("🔑 Login").click_accesskit();
    harness.run_steps(2);
    assert!(harness.query_by_label(CONVERSION_WARNING).is_none(), "voltou ao original: o aviso some");
    close_type_popup(&mut harness);
    harness.get_by_label("Save").click();
    harness.run_steps(2);
    assert!(!harness.state().form_open, "salvou: {:?}", harness.state().err);
    let it = item_by_id(harness.state(), &id);
    assert_eq!(it["kind"], "login");
    assert!(it.get("customFields").is_none(), "nenhum campo extra nasceu: {it}");
    assert_eq!(it["login"], login_antes, "o objeto login atravessou intacto");
    assert_ne!(it["updatedAt"], serde_json::json!(1), "a gravação aconteceu (não foi só fechar o formulário)");
}

/// Item de `kind` desconhecido: sem seletor, o tipo fica fixo como antes e o arquivo não perde o valor.
#[test]
fn an_unknown_kind_has_no_type_selector() {
    let mut app = unlocked_kinds("unknown-kind", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("unknown-kind");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let id = item_id(&app, "Gmail");
    item_by_id_mut(&mut app, &id)["kind"] = serde_json::json!("passkey");
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.state_mut().open_edit_form(&id);
    harness.run_steps(2);
    assert!(harness.query_by_label("Type").is_none(), "sem seletor de tipo para kind desconhecido");
    harness.get_by_label_contains("Type: Note"); // o crachá fixo de hoje
    assert_eq!(harness.state().form_kind, crate::vault::ItemKind::Note);
    harness.get_by_label("Save").click();
    harness.run_steps(2);
    assert!(!harness.state().form_open, "salvou: {:?}", harness.state().err);
    assert_eq!(item_by_id(harness.state(), &id)["kind"], "passkey", "o kind do arquivo não é reescrito");
}

/// O aviso de conversão aparece só enquanto o tipo escolhido é diferente do original.
#[test]
fn the_conversion_warning_shows_only_for_a_different_type() {
    let mut app = unlocked_kinds("change-warning", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("change-warning");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let id = item_id(&app, "Nubank");
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.state_mut().open_edit_form(&id);
    harness.run_steps(2);
    assert!(harness.query_by_label(CONVERSION_WARNING).is_none(), "no tipo atual não há aviso");
    harness.get_by_label("Type").click_accesskit();
    harness.run_steps(2);
    harness.get_by_label("👤 Identity").click_accesskit();
    harness.run_steps(2);
    assert!(harness.query_by_label(CONVERSION_WARNING).is_some(), "o aviso aparece com o tipo diferente: {:?}", harness.state().form_target_kind);
    // mesmo popup (a escolha por AccessKit não o fecha): volta ao cartão, o aviso some.
    harness.get_by_label("💳 Card").click_accesskit();
    harness.run_steps(2);
    assert!(harness.query_by_label(CONVERSION_WARNING).is_none(), "voltou ao original: o aviso some");
}

/// O aviso do TOTP depende do ITEM, não do seletor: login sem segredo só leva o aviso comum.
#[test]
fn the_totp_warning_needs_a_totp_secret() {
    let mut app = unlocked("change-no-totp", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("change-no-totp");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let id = item_id(&app, "192.168.1.34");
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.state_mut().open_edit_form(&id);
    harness.run_steps(2);
    harness.get_by_label("Type").click_accesskit();
    harness.run_steps(2);
    harness.get_by_label("📝 Note").click_accesskit();
    harness.run_steps(2);
    assert!(harness.query_by_label(CONVERSION_WARNING).is_some(), "o aviso comum aparece com o tipo diferente");
    assert!(harness.query_by_label(TOTP_WARNING).is_none(), "sem segredo TOTP não há o que avisar");
}

/// Conversão que o núcleo recusa: mensagem traduzida, nada gravado (o arquivo fica byte a byte igual) e o
/// formulário continua aberto no tipo original.
#[test]
fn a_failed_conversion_keeps_the_form_open_and_saves_nothing() {
    let mut app = unlocked_kinds("change-fail", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("change-fail");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let id = item_id(&app, "Gmail");
    // um campo que o núcleo não sabe converter, com valor: o `login` não pode virar campo extra
    item_by_id_mut(&mut app, &id)["login"]["passkeyId"] = serde_json::json!("abc");
    app.toggle_favorite(&id); // grava o cofre em disco, já com o campo desconhecido
    let antes = std::fs::read(&app.path).expect("cofre em disco");
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.state_mut().open_edit_form(&id);
    harness.run_steps(2);
    harness.state_mut().add_name = "Gmail editado".into();
    harness.get_by_label("Type").click_accesskit();
    harness.run_steps(2);
    harness.get_by_label("📝 Note").click_accesskit();
    harness.run_steps(2);
    close_type_popup(&mut harness);
    harness.get_by_label("Save").click();
    harness.run_steps(2);
    assert!(harness.state().form_open, "o erro mantém o formulário aberto");
    harness.get_by_label_contains("cannot change type"); // a mensagem traduzida (inglês no teste)
    assert!(selector_text(&harness).contains("Login"), "o seletor volta ao tipo original");
    assert_eq!(harness.state().add_name, "Gmail editado", "a edição do usuário continua no formulário");
    let it = item_by_id(harness.state(), &id);
    assert_eq!(it["kind"], "login", "o item em memória fica intacto");
    assert_eq!(it["name"], "Gmail", "a edição não foi aplicada");
    assert_eq!(std::fs::read(&harness.state().path).expect("cofre em disco"), antes, "o arquivo não foi regravado");
    // A mensagem é do tipo que a pessoa escolheu: trocar de tipo a apaga (não fica obsoleta na tela).
    assert!(harness.state().form_err.is_some());
    if harness.query_by_label("👤 Identity").is_none() {
        harness.get_by_label("Type").click_accesskit();
        harness.run_steps(2);
    }
    harness.get_by_label("👤 Identity").click_accesskit();
    harness.run_steps(2);
    assert!(harness.state().form_err.is_none(), "trocar o tipo limpa a mensagem obsoleta");
    assert!(harness.query_by_label_contains("cannot change type").is_none(), "a linha do erro some junto");
    assert_eq!(harness.state().add_name, "Gmail editado", "trocar o tipo não descarta a edição do usuário");
}

/// Gravação que falha com a conversão pendente: o motivo aparece também DENTRO do modal (não só na tela de
/// trás, que fica coberta), e o seletor continua no tipo escolhido para a pessoa tentar de novo.
#[test]
fn a_failed_save_with_a_pending_conversion_shows_the_error_in_the_form() {
    let mut app = unlocked_kinds("change-save-fail", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("change-save-fail");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    // A pasta não existe: a gravação atômica falha (nada chega ao disco em lugar nenhum).
    app.path = dir.join("sem-pasta/cofre.tkeys").display().to_string();
    let id = item_id(&app, "Gmail");
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.state_mut().open_edit_form(&id);
    harness.run_steps(2);
    harness.get_by_label("Type").click_accesskit();
    harness.run_steps(2);
    harness.get_by_label("📝 Note").click_accesskit();
    harness.run_steps(2);
    close_type_popup(&mut harness);
    harness.get_by_label("Save").click();
    harness.run_steps(2);
    assert!(harness.state().form_open, "a falha mantém o formulário aberto");
    let na_tela_de_tras = harness.state().err.clone().expect("o erro da gravação");
    assert_eq!(harness.state().form_err.as_deref(), Some(na_tela_de_tras.as_str()), "o mesmo motivo aparece no modal");
    // O motivo aparece DOIS lugares: o modal (por `form_err`) e a linha da tela de trás (por `err`).
    let na_tela = harness.get_all_by_label_contains("falha ao gravar").count();
    assert!(na_tela >= 2, "o motivo aparece no modal e na tela de trás: {na_tela}");
    assert!(selector_text(&harness).contains("Note"), "o tipo escolhido continua no seletor para tentar de novo");
    assert_eq!(item_by_id(harness.state(), &id)["kind"], "login", "nada foi convertido em memória");
}

/// Gravação que falha numa edição COMUM (sem troca de tipo): o motivo aparece também DENTRO do
/// formulário aberto, não só na linha de erro da tela de trás (que fica coberta pelo modal).
#[test]
fn a_failed_save_of_a_plain_edit_shows_the_error_in_the_form() {
    let mut app = unlocked("plain-save-fail", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("plain-save-fail");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    // A pasta não existe: a gravação atômica falha (nada chega ao disco em lugar nenhum).
    app.path = dir.join("sem-pasta/cofre.tkeys").display().to_string();
    let id = item_id(&app, "192.168.1.34");
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.state_mut().open_edit_form(&id);
    harness.run_steps(2);
    harness.state_mut().add_name = "192.168.1.34 editado".into();
    harness.state_mut().save_form();
    harness.run_steps(2);
    assert!(harness.state().form_open, "a falha mantém o formulário aberto");
    let na_tela_de_tras = harness.state().err.clone().expect("o erro da gravação");
    assert_eq!(harness.state().form_err.as_deref(), Some(na_tela_de_tras.as_str()), "o mesmo motivo aparece no modal");
    let na_tela = harness.get_all_by_label_contains("falha ao gravar").count();
    assert!(na_tela >= 2, "o motivo aparece no modal e na tela de trás: {na_tela}");
    assert_eq!(item_by_id(harness.state(), &id)["name"], "192.168.1.34", "a edição não foi aplicada");
}

/// Campo personalizado com a chave "Oculto": o valor sai mascarado (👁 revela), o botão de copiar
/// leva o valor real para a área de transferência e salvar grava `hidden: true`.
#[test]
fn a_hidden_custom_field_masks_reveals_and_copies() {
    let mut app = unlocked("custom-hidden", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("custom-hidden");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    app.open_add_form(NewKind::FreeItem);
    app.add_name = "Recado".into();
    let f = app.form_custom.first_mut().expect("item livre abre com um campo");
    f.name = "pin".into();
    f.value = "1234".into();
    f.hidden = true;
    let mut harness = harness(app);
    harness.run_steps(2);
    // oculto: o valor sai mascarado, com 👁 para revelar e cópia ao lado
    assert_eq!(harness.get_by_label("Value").accesskit_node().role(), Role::PasswordInput);
    harness.get_by_label("👁").click();
    harness.run_steps(2);
    assert_eq!(harness.get_by_label("Value").accesskit_node().role(), Role::TextInput, "revelado");
    harness.get_by_label("Hidden");
    // copiar de verdade: o valor oculto (mascarado ou revelado) vai para a área de transferência.
    // O app copia direto no clipboard do SO (o harness não intercepta); no Windows dá para conferir
    // o conteúdo — no Linux de CI, sem clipboard, o clique roda e a cópia falha em silêncio.
    harness.get_by_label("Copy").click();
    harness.run_steps(2);
    #[cfg(windows)]
    {
        let copied = clipboard_win::get_clipboard_string().unwrap_or_default();
        assert_eq!(copied, "1234", "o valor oculto copiado para a área de transferência");
    }
    // salvar grava o campo como oculto no cofre
    harness.get_by_label("Save").click();
    harness.run_steps(2);
    assert!(!harness.state().form_open, "salvou: {:?}", harness.state().err);
    let raw = harness.state().raw.as_ref().expect("cofre");
    let it = raw["items"].as_array().expect("itens").iter().find(|i| i["name"] == "Recado").expect("item");
    assert_eq!(it["kind"], "note");
    let campos = it["customFields"].as_array().expect("campos");
    assert_eq!(campos.len(), 1);
    assert_eq!(campos[0]["name"], "pin");
    assert_eq!(campos[0]["value"], "1234");
    assert_eq!(campos[0]["hidden"], true);
}

/// Espaço sobrando nos campos não chega ao cofre: o save trima o que o Android trima (usuário, titular,
/// bandeira, os cinco da identidade; notas só o fim) e NUNCA a senha.
#[test]
fn saving_trims_the_fields_like_the_android() {
    let mut app = unlocked("trims", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("trims");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();

    app.open_add_form(NewKind::Card);
    app.add_name = "  Itaú  ".into();
    app.add_cardholder = "  João da Silva  ".into();
    app.add_brand = "  Mastercard  ".into();
    app.add_number = "5555555555554444".into();
    app.save_form();

    app.open_add_form(NewKind::Identity);
    app.add_name = "Passaporte".into();
    app.add_first = "  Ana  ".into();
    app.add_last = "  Souza  ".into();
    app.add_email = "  ana@exemplo.com  ".into();
    app.add_phone = "  11 91234-5678  ".into();
    app.add_address = "  Rua B, 45  ".into();
    app.add_notes = "  recado  \n  segunda linha  \n\n".into();
    app.save_form();

    app.open_add_form(NewKind::Login);
    app.add_name = "Gmail 2".into();
    app.add_user = "  joao  ".into();
    app.add_pass = "  senha com espaços  ".into();
    app.save_form();

    let raw = app.raw.as_ref().expect("cofre");
    let acha = |nome: &str| raw["items"].as_array().expect("itens").iter().find(|i| i["name"] == nome).expect(nome);
    let card = acha("Itaú");
    assert_eq!(card["card"]["cardholder"], "João da Silva");
    assert_eq!(card["card"]["brand"], "Mastercard");
    let ident = acha("Passaporte");
    assert_eq!(ident["identity"]["firstName"], "Ana");
    assert_eq!(ident["identity"]["lastName"], "Souza");
    assert_eq!(ident["identity"]["email"], "ana@exemplo.com");
    assert_eq!(ident["identity"]["phone"], "11 91234-5678");
    assert_eq!(ident["identity"]["address"], "Rua B, 45");
    // notas: só o fim (o começo fica como digitado, o `trimEnd` do Android)
    assert_eq!(ident["notes"], "  recado  \n  segunda linha");
    let login = acha("Gmail 2");
    assert_eq!(login["login"]["username"], "joao");
    assert_eq!(login["login"]["password"], "  senha com espaços  ", "a senha nunca é trimada");
}

/// A linha da lista mostra o resumo do tipo: usuário, bandeira e `•••• 1234`, nome completo, começo da
/// nota — e o "Com TOTP" do popup Filtrar continua servindo com itens de qualquer tipo na tela.
#[test]
fn the_list_shows_the_right_summary_per_kind() {
    let mut harness = harness(unlocked_kinds("summaries", Prefs::new("en", ThemeMode::Dark)));
    harness.run_steps(2);
    harness.get_by_label_contains("joao");
    harness.get_by_label_contains("Elo  •••• 7013");
    harness.get_by_label_contains("João Silva");
    harness.get_by_label_contains("comprar pão");
    // o ícone do tipo acompanha o nome na linha
    harness.get_by_label_contains("💳 Nubank");
    // o filtro "Com TOTP" esconde o que não tem segredo (cartão, identidade, nota) e mantém o login
    harness.get_by_label("Filter").click();
    harness.run_steps(2);
    harness.get_by_label("With TOTP").click();
    harness.run_steps(2);
    assert_eq!(harness.query_all_by_label_contains("••••").count(), 0, "cartão escondido");
    assert_eq!(harness.query_all_by_label_contains("comprar pão").count(), 0, "nota escondida");
    harness.get_by_label_contains("joao");
}

/// O botão "Filtrar" abre o popup; tipo e "mostrar só" se combinam com E, o botão mostra quantos filtros
/// estão ligados e "Limpar" volta a lista inteira.
#[test]
fn the_filter_popup_combines_filters_shows_the_count_and_clears() {
    use localkeys_core::filters::KindFilter;

    let mut harness = harness(unlocked_kinds("filters", Prefs::new("en", ThemeMode::Dark)));
    harness.run_steps(2);
    assert!(harness.state().filters.is_clear());

    // Só o tipo: mostra o cartão, esconde o login.
    harness.get_by_label("Filter").click();
    harness.run_steps(2);
    harness.get_by_label("Cards").click();
    harness.run_steps(2);
    assert_eq!(harness.state().filters.kind, KindFilter::Cards);
    harness.get_by_label_contains("Nubank");
    assert_eq!(harness.query_all_by_label_contains("Gmail").count(), 0, "login fora do tipo");

    // Combina tipo + "Com TOTP": cartão não tem TOTP, então some — e o botão mostra 2.
    harness.get_by_label("With TOTP").click();
    harness.run_steps(2);
    assert_eq!(harness.state().filters.active_count(), 2);
    harness.get_by_label("Filter (2)");
    assert_eq!(harness.query_all_by_label_contains("Nubank").count(), 0, "cartão sem TOTP");

    // Troca o tipo para Logins: o login com TOTP volta (2 filtros ainda).
    harness.get_by_label("Logins").click();
    harness.run_steps(2);
    assert_eq!(harness.state().filters.active_count(), 2);
    harness.get_by_label_contains("Gmail");

    // Limpar zera tudo e a lista volta inteira.
    harness.get_by_label("Clear").click();
    harness.run_steps(2);
    assert!(harness.state().filters.is_clear());
    harness.get_by_label("Filter");
    harness.get_by_label_contains("Nubank");
    harness.get_by_label_contains("comprar pão");
}

/// Os chips dos filtros ligados: o × desliga SÓ aquele filtro (sem abrir o popup), a contagem do botão
/// cai e a lista atualiza; o outro filtro continua ligado.
#[test]
fn the_filter_chips_remove_one_filter_without_opening_the_popup() {
    let mut harness = harness(unlocked("chips", Prefs::new("en", ThemeMode::Dark)));
    harness.run_steps(2);
    harness.get_by_label("Filter").click();
    harness.run_steps(2);
    harness.get_by_label("Cards").click();
    harness.run_steps(2);
    harness.get_by_label("Favorites").click();
    harness.run_steps(2);
    // "Done" fecha o popup. A lista fica vazia (não há cartão favorito).
    harness.get_by_label("Done").click();
    harness.run_steps(2);
    assert!(harness.query_by_label("Done").is_none(), "o popup fechou");
    harness.get_by_label("Remove filter Cards");
    harness.get_by_label("Remove filter Favorites");
    harness.get_by_label_contains("No items match these filters");

    // O × do tipo desliga só o tipo: favoritos fica e o item favorito volta.
    harness.get_by_label("Remove filter Cards").click();
    harness.run_steps(2);
    let filtros = harness.state().filters;
    assert_eq!(filtros.kind, localkeys_core::filters::KindFilter::All);
    assert!(filtros.only.favorites, "o outro filtro continua ligado");
    assert_eq!(filtros.active_count(), 1);
    harness.get_by_label("Filter (1)");
    harness.get_by_label_contains("192.168.1.34");
    assert!(harness.query_by_label("Remove filter Cards").is_none(), "o chip do tipo sumiu");
    harness.get_by_label("Remove filter Favorites");
}

/// O estado vazio diz a causa real: só busca, só pasta, ou mais de uma coisa.
#[test]
fn the_empty_list_names_the_real_cause() {
    let mut app = unlocked_kinds("empty-cause", Prefs::new("en", ThemeMode::Dark));
    app.search = "zzz-nada-assim".into();
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.get_by_label_contains("No items match the search");
    assert_eq!(harness.query_all_by_label_contains("filters").count(), 0, "não culpa filtros que não existem");

    harness.state_mut().folder_filter = Some("pasta-inexistente".into());
    harness.run_steps(2);
    harness.get_by_label_contains("No items match the search, folder and filters");

    harness.state_mut().search.clear();
    harness.run_steps(2);
    harness.get_by_label_contains("No items in this folder");
}

/// O popup Filtrar acha "Com anexos" e "Favoritos" de verdade na lista.
#[test]
fn the_filter_popup_finds_attachments_and_favorites() {
    let mut app = unlocked("filters-only", Prefs::new("en", ThemeMode::Dark));
    let id = item_id(&app, "1lib.sk");
    let raw = app.raw.as_mut().expect("cofre");
    assert!(crate::vault::add_attachment(raw, &id, "nota.txt", "text/plain", "QQ=="), "anexa");
    app.items = crate::vault::items_view(app.raw.as_ref().expect("cofre"));

    let mut harness = harness(app);
    harness.run_steps(2);
    harness.get_by_label("Filter").click();
    harness.run_steps(2);
    harness.get_by_label("With attachments").click();
    harness.run_steps(2);
    harness.get_by_label_contains("1lib.sk");
    assert_eq!(harness.query_all_by_label_contains("192.168.1.34").count(), 0, "sem anexo, escondido");

    // Troca para Favoritos: o item com anexo (não favorito) some e sobra o favorito.
    harness.get_by_label("With attachments").click();
    harness.run_steps(2);
    harness.get_by_label("Favorites").click();
    harness.run_steps(2);
    harness.get_by_label_contains("192.168.1.34");
    assert_eq!(harness.query_all_by_label_contains("1lib.sk").count(), 0, "não favorito, escondido");
}

/// Filtros escondendo tudo num cofre com itens: mensagem própria (+ Limpar), e o rodapé "Items:" conta
/// o cofre inteiro, não a lista filtrada.
#[test]
fn filtered_out_shows_the_message_and_the_footer_counts_the_whole_vault() {
    let mut harness = harness(unlocked_kinds("empty-filter", Prefs::new("en", ThemeMode::Dark)));
    harness.run_steps(2);
    harness.get_by_label("Items: 4");

    harness.get_by_label("Filter").click();
    harness.run_steps(2);
    harness.get_by_label("Cards").click();
    harness.run_steps(2);
    harness.get_by_label("With TOTP").click();
    harness.run_steps(2);
    harness.get_by_label("Filter (2)").click(); // fecha o popup: sobra o Limpar do estado vazio
    harness.run_steps(2);
    harness.get_by_label_contains("No items match these filters");
    harness.get_by_label("Items: 4");

    harness.get_by_label("Clear").click();
    harness.run_steps(2);
    assert!(harness.state().filters.is_clear());
    assert_eq!(harness.query_all_by_label_contains("No items match these filters").count(), 0);
    harness.get_by_label_contains("Nubank");
}

/// Um cofre com duas pastas ("Trabalho", "Pessoal") e um login em cada, para o teste da pasta lembrada.
/// Devolve o cofre e os ids das pastas, na ordem em que foram criadas.
fn folders_vault() -> (serde_json::Value, String, String) {
    let mut raw: serde_json::Value = serde_json::from_str(crate::vault::EMPTY_VAULT).expect("cofre vazio");
    crate::vault::add_folder(&mut raw, "Trabalho");
    crate::vault::add_folder(&mut raw, "Pessoal");
    for (name, user) in [("github", "hades"), ("banco", "joao")] {
        let form = crate::vault::LoginForm { name, username: user, password: "x", totp: "", url: "", notes: "" };
        crate::vault::add_login(&mut raw, form);
    }
    let folders = crate::vault::folders(&raw);
    let (work, home) = (folders[0].id.clone(), folders[1].id.clone());
    for (name, folder) in [("github", &work), ("banco", &home)] {
        let id = crate::vault::items_view(&raw).into_iter().find(|item| item.name == name).expect("item do cofre").id;
        assert!(crate::vault::set_item_folder(&mut raw, &id, Some(folder)), "item entra na pasta");
    }
    (raw, work, home)
}

/// O app destrancado nesse cofre (o config.json de teste vai para o scratch).
fn opened(name: &str, prefs: Prefs, raw: serde_json::Value) -> KeysApp {
    let mut app = app(name, prefs);
    app.path = "C:\\Users\\qa\\Desktop\\meu-cofre.tkeys".into();
    app.adopt(crate::crypto::test_session(), raw);
    app
}

/// A lista abre na pasta escolhida da última vez, não em "Todas": só os itens dela aparecem, e trocar de pasta
/// (ou voltar para "Todas") grava a escolha no config.json.
#[test]
fn the_list_opens_in_the_remembered_folder() {
    let (raw, work, home) = folders_vault();
    let mut harness = harness(opened("folder-memory", Prefs::new("en", ThemeMode::Dark).with_folder(&work), raw));
    harness.run_steps(2);
    assert_eq!(harness.state().folder_filter.as_deref(), Some(work.as_str()), "abriu na pasta lembrada");
    harness.get_by_label("Trabalho"); // o chip da pasta lembrada está na barra
    harness.get_by_label_contains("github");
    assert_eq!(harness.query_all_by_label_contains("banco").count(), 0, "item da outra pasta escondido");

    // Clicar em outra pasta filtra por ela e grava no config.json de teste.
    harness.get_by_label("Pessoal").click();
    harness.run_steps(2);
    assert_eq!(harness.state().folder_filter.as_deref(), Some(home.as_str()), "trocou para a pasta clicada");
    harness.get_by_label_contains("banco");
    assert_eq!(harness.query_all_by_label_contains("github").count(), 0, "a troca de pasta filtra a lista");
    assert_eq!(Prefs::load_from(&harness.state().config_file).folder.as_deref(), Some(home.as_str()), "a escolha foi gravada");

    // "Todas" também é uma escolha: volta a listar tudo e é gravada como tal.
    harness.get_by_label("All").click();
    harness.run_steps(2);
    assert_eq!(harness.state().folder_filter, None, "voltou para todas");
    harness.get_by_label_contains("github");
    harness.get_by_label_contains("banco");
    assert_eq!(Prefs::load_from(&harness.state().config_file).folder, None, "todas foi gravado");
}

/// Pasta lembrada que não existe no cofre aberto (apagada aqui ou em outro aparelho) cai em "Todas" — e a
/// preferência fica como estava: reabrir o cofre que tem essa pasta volta para ela.
#[test]
fn a_remembered_folder_that_is_gone_opens_in_all() {
    let (raw, _, _) = folders_vault();
    let prefs = Prefs::new("en", ThemeMode::Dark).with_folder("pasta-apagada");
    let mut app = app("folder-gone", prefs.clone());
    // O config.json como ficou da sessão anterior: abrir o cofre não pode reescrevê-lo.
    prefs.save_to(&app.config_file).expect("config do teste");
    app.path = "C:\\Users\\qa\\Desktop\\meu-cofre.tkeys".into();
    app.adopt(crate::crypto::test_session(), raw);

    let mut harness = harness(app);
    harness.run_steps(2);
    assert_eq!(harness.state().folder_filter, None, "pasta inexistente caiu em todas");
    harness.get_by_label_contains("github");
    harness.get_by_label_contains("banco");
    assert_eq!(
        Prefs::load_from(&harness.state().config_file).folder.as_deref(),
        Some("pasta-apagada"),
        "a preferência fica como estava (só a lista caiu em todas)"
    );
}

/// Gravar uma sincronização em que a pasta do filtro não existe mais no cofre mesclado cai em "Todas" pela
/// preferência (como no Android), em vez de deixar o filtro apontando para uma pasta que o cofre não tem.
#[test]
fn a_sync_that_loses_the_filtered_folder_falls_back_to_all() {
    let (raw, work, _) = folders_vault();
    let mut app = opened("sync-folder-gone", Prefs::new("en", ThemeMode::Dark).with_folder(&work), raw);
    assert_eq!(app.folder_filter.as_deref(), Some(work.as_str()), "abriu na pasta lembrada");
    let dir = scratch("sync-folder-gone");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("a.tkeys").display().to_string();
    app.persist(app.raw.clone().expect("cofre")).then_some(()).expect("grava o cofre atual");

    // O cofre mesclado sem a pasta do filtro (a outra cópia apagou a pasta).
    let mut merged = app.raw.clone().expect("cofre");
    merged["folders"].as_array_mut().expect("pastas").retain(|folder| folder["id"] != work.as_str());
    let other_path = dir.join("other.tkeys");
    tkeys_com(app.session.as_ref().expect("sessão"), &merged, &other_path);
    app.sync_plan = Some(crate::SyncPlan {
        other_path: other_path.display().to_string(),
        merged,
        summary: crate::merge::MergeSummary { added: 1, ..Default::default() },
    });
    app.sync_open = true;

    let mut harness = harness(app);
    harness.run_steps(2);
    harness.get_by_label("Save to both copies").click();
    harness.run_steps(2);
    assert_eq!(harness.state().folder_filter, None, "a pasta sumiu do cofre mesclado: caiu em todas");
    assert_eq!(Prefs::load_from(&harness.state().config_file).folder, None, "a preferência acompanhou");
    harness.get_by_label_contains("github");
    harness.get_by_label_contains("banco");
}

/// As capturas do popup Filtrar (com tipo e "mostrar só" ligados) e do cartão com a bandeira detectada
/// digitando o número, claro e escuro, 340 e 1100. Só renderiza com `LOCALKEYS_QA_RENDER=1`.
#[test]
fn filter_shots() {
    for (width, label) in [(340.0, "340"), (1100.0, "1100")] {
        for (appearance, name) in [(ThemeMode::Light, "light"), (ThemeMode::Dark, "dark")] {
            let mut harness = harness_sized(unlocked_kinds("shots-filter", Prefs::new("en", appearance)), width);
            harness.run_steps(2);
            harness.get_by_label("Filter").click();
            harness.run_steps(2);
            harness.get_by_label("Logins").click();
            harness.run_steps(2);
            harness.get_by_label("With TOTP").click();
            harness.run_steps(2);
            shot(&mut harness, &format!("filter-popup-{name}-{label}"));
        }
    }
}

/// As capturas dos chips dos filtros (dois ligados, popup fechado para os chips e a lista aparecerem),
/// claro e escuro, 340 e 1100. Só renderiza com `LOCALKEYS_QA_RENDER=1`.
#[test]
fn filter_chips_shots() {
    for (width, label) in [(340.0, "340"), (1100.0, "1100")] {
        for (appearance, name) in [(ThemeMode::Light, "light"), (ThemeMode::Dark, "dark")] {
            let mut harness = harness_sized(unlocked_kinds("shots-chips", Prefs::new("en", appearance)), width);
            harness.run_steps(2);
            harness.get_by_label("Filter").click();
            harness.run_steps(2);
            harness.get_by_label("Logins").click();
            harness.run_steps(2);
            harness.get_by_label("With TOTP").click();
            harness.run_steps(2);
            harness.get_by_label("Filter (2)").click(); // fecha o popup
            harness.run_steps(2);
            harness.get_by_label("Remove filter Logins");
            harness.get_by_label("Remove filter With TOTP");
            shot(&mut harness, &format!("filter-chips-{name}-{label}"));
        }
    }
}

/// As capturas da fase A (pedido 022): seletor de tipo, formulários de cada tipo e a lista com os
/// quatro tipos — claro e escuro, na largura mínima (340) e numa larga (1100). Só renderiza com
/// `LOCALKEYS_QA_RENDER=1` (o CI roda sem).
#[test]
fn kind_shots() {
    for (width, label) in [(340.0, "340"), (1100.0, "1100")] {
        for (appearance, name) in [(ThemeMode::Light, "light"), (ThemeMode::Dark, "dark")] {
            // seletor de tipo do "+"
            let mut harness = harness_sized(unlocked("shots-kind", Prefs::new("en", appearance)), width);
            harness.run_steps(2);
            harness.get_by_label("Add").click();
            harness.run_steps(2);
            shot(&mut harness, &format!("kind-picker-{name}-{label}"));

            // formulário de cartão (com campo personalizado oculto)
            let mut app = unlocked("shots-kind", Prefs::new("en", appearance));
            app.open_add_form(NewKind::Card);
            app.add_name = "Nubank".into();
            app.add_cardholder = "João da Silva".into();
            app.add_brand = "Elo".into();
            app.add_number = "6362970000457013".into();
            app.add_exp = "01/2027".into();
            app.form_custom.push(FormCustomField { id: String::new(), name: "PIN".into(), value: "1234".into(), hidden: true, revealed: false });
            let mut harness = harness_sized(app, width);
            harness.run_steps(2);
            shot(&mut harness, &format!("kind-form-card-{name}-{label}"));

            // formulário de identidade
            let mut app = unlocked("shots-kind", Prefs::new("en", appearance));
            app.open_add_form(NewKind::Identity);
            app.add_name = "Documentos".into();
            app.add_first = "João".into();
            app.add_last = "Silva".into();
            let mut harness = harness_sized(app, width);
            harness.run_steps(2);
            shot(&mut harness, &format!("kind-form-identity-{name}-{label}"));

            // item livre (nota com o campo personalizado aberto) e nota
            let mut app = unlocked("shots-kind", Prefs::new("en", appearance));
            app.open_add_form(NewKind::FreeItem);
            app.add_name = "Keys of the apartment".into();
            let mut harness = harness_sized(app, width);
            harness.run_steps(2);
            shot(&mut harness, &format!("kind-form-free-{name}-{label}"));
            let mut app = unlocked("shots-kind", Prefs::new("en", appearance));
            app.open_add_form(NewKind::Note);
            app.add_name = "Market list".into();
            app.add_notes = "comprar pão".into();
            let mut harness = harness_sized(app, width);
            harness.run_steps(2);
            shot(&mut harness, &format!("kind-form-note-{name}-{label}"));

            // a lista com um item de cada tipo (resumos por tipo)
            let mut harness = harness_sized(unlocked_kinds("shots-kind", Prefs::new("en", appearance)), width);
            harness.run_steps(2);
            shot(&mut harness, &format!("kind-list-{name}-{label}"));
        }
    }
}

/// Colar um `otpauth://` NO CAMPO grava só a chave base32 e preenche nome e usuário quando estão
/// vazios — nunca sobrescreve o que a pessoa já digitou.
#[test]
fn pasting_an_otpauth_link_fills_name_and_user() {
    let mut app = unlocked("totp-paste", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("totp-paste");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    app.open_add_form(NewKind::Login);
    let mut harness = harness(app);
    harness.run_steps(2);
    // O campo TOTP do form (o rótulo "TOTP" também existe na lista de fundo — busca por papel+rótulo;
    // o campo nasce MASCARADO, então é PasswordInput).
    let campo =
        harness.get_all_by_role(Role::PasswordInput).find(|node| node.accesskit_node().label().is_some_and(|l| l == "TOTP")).expect("campo TOTP");
    campo.click();
    harness.run_steps(1);
    harness.input_mut().events.push(egui::Event::Paste("otpauth://totp/GitHub:joao?secret=jbswy3dpehpk3pxp".into()));
    harness.run_steps(2);
    let app = harness.state();
    assert_eq!(app.add_totp, "JBSWY3DPEHPK3PXP", "o campo guarda só a chave base32 limpa");
    assert_eq!(app.add_name, "GitHub", "nome vazio é preenchido pelo emissor do link");
    assert_eq!(app.add_user, "joao", "usuário vazio é preenchido pela conta do link");

    // Nome e usuário já preenchidos: o link não sobrescreve.
    harness.state_mut().add_name = "Meu GitHub".into();
    harness.state_mut().add_user = "joaozinho".into();
    let campo =
        harness.get_all_by_role(Role::PasswordInput).find(|node| node.accesskit_node().label().is_some_and(|l| l == "TOTP")).expect("campo TOTP");
    // O clique posiciona o cursor onde clica: na borda DIREITA, como quem cola no fim do campo (no
    // meio, o paste entraria no meio da chave — comportamento normal de editor de texto).
    let borda = egui::pos2(campo.rect().right() - 6.0, campo.rect().center().y);
    click_at(&mut harness, borda);
    harness.run_steps(1);
    harness.input_mut().events.push(egui::Event::Paste("otpauth://totp/Binance:joao%40exemplo.com?secret=JBSWY3DPEHPK3PXP&issuer=Binance".into()));
    harness.run_steps(3);
    let app = harness.state();
    assert_eq!(app.add_name, "Meu GitHub", "nome preenchido fica");
    assert_eq!(app.add_user, "joaozinho", "usuário preenchido fica");
    assert_eq!(app.add_totp, "JBSWY3DPEHPK3PXP");

    // E o que vai para o cofre é a chave, não o link.
    harness.state_mut().add_pass = "x".into();
    harness.get_by_label("Save").click();
    harness.run_steps(2);
    let raw = harness.state().raw.as_ref().expect("cofre");
    let it = raw["items"].as_array().expect("itens").iter().find(|i| i["name"] == "Meu GitHub").expect("item");
    assert_eq!(it["login"]["totp"], "JBSWY3DPEHPK3PXP");
}

/// Os erros aparecem claros embaixo do campo: HOTP (não é TOTP), link sem `secret` e chave que não é
/// base32 — o texto colado fica no campo, não vira chave.
#[test]
fn an_invalid_totp_link_shows_a_clear_error() {
    let mut app = unlocked("totp-erros", Prefs::new("en", ThemeMode::Dark));
    app.open_add_form(NewKind::Login);
    let mut harness = harness(app);
    harness.run_steps(2);
    for (link, erro) in [("otpauth://hotp/X?secret=JBSWY3DP&counter=1", "not a TOTP link"), ("otpauth://totp/X?issuer=X", "no secret key")] {
        let campo =
            harness.get_all_by_role(Role::PasswordInput).find(|node| node.accesskit_node().label().is_some_and(|l| l == "TOTP")).expect("campo TOTP");
        campo.click();
        harness.run_steps(1);
        harness.input_mut().events.push(egui::Event::Paste(link.into()));
        harness.run_steps(2);
        let label = node_text(&harness.get_by_label_contains(erro));
        assert!(label.contains(erro), "erro visível: {label}");
        assert_eq!(harness.state().add_totp, link, "o link inválido fica no campo, não vira chave");
        harness.state_mut().add_totp.clear();
        harness.run_steps(1);
    }
    // Chave digitada que não é base32.
    harness.state_mut().add_totp = "senha1!".into();
    harness.run_steps(2);
    let label = node_text(&harness.get_by_label_contains("must be base32"));
    assert!(label.contains("base32"), "erro visível: {label}");
}

/// QR de imagem: os bytes de um PNG com QR (o caminho do seletor de arquivo) aplicam a chave, preenchem
/// o nome vazio e avisam quando os parâmetros saem do padrão — a chave vai inteira mesmo assim.
#[test]
fn a_qr_image_fills_the_key_and_warns_on_non_default_params() {
    let mut app = unlocked("totp-qr", Prefs::new("en", ThemeMode::Dark));
    app.open_add_form(NewKind::Login);
    // QR padrão (6/30s/SHA-1): chave + nome, sem aviso.
    app.apply_qr_bytes(&crate::qr::qr_png("otpauth://totp/GitHub:joao?secret=JBSWY3DPEHPK3PXP", 8));
    assert_eq!(app.add_totp, "JBSWY3DPEHPK3PXP");
    assert_eq!(app.add_name, "GitHub");
    assert_eq!(app.add_user, "joao");
    assert!(app.totp_warn.is_none());

    // QR com 8 dígitos/60 s/SHA-256: a chave é gravada com o aviso de que os códigos podem não bater.
    app.apply_qr_bytes(&crate::qr::qr_png("otpauth://totp/X:y?secret=JBSWY3DPEHPK3PXP&digits=8&period=60&algorithm=SHA256", 8));
    assert_eq!(app.add_totp, "JBSWY3DPEHPK3PXP", "fora do padrão também grava a chave");
    let aviso = app.totp_warn.as_deref().expect("aviso de parâmetros");
    assert!(aviso.contains("8 digits") && aviso.contains("SHA256"), "o aviso diz o que o QR pede: {aviso}");

    // O aviso aparece no form e some quando o campo muda (edição de verdade no campo).
    let mut harness = harness(app);
    harness.run_steps(2);
    let texto = node_text(&harness.get_by_label_contains("codes may not match"));
    assert!(texto.contains("may not match"), "aviso visível: {texto}");
    let campo =
        harness.get_all_by_role(Role::PasswordInput).find(|node| node.accesskit_node().label().is_some_and(|l| l == "TOTP")).expect("campo TOTP");
    campo.click();
    harness.run_steps(1);
    harness.input_mut().events.push(egui::Event::Text("X".into()));
    harness.run_steps(2);
    assert!(harness.state().totp_warn.is_none(), "o aviso é do link aplicado; mexer no campo limpa");

    // Imagem sem QR e bytes que não são imagem avisam sem quebrar nada.
    let lisa = image::GrayImage::from_pixel(64, 64, image::Luma([128]));
    let mut png = Vec::new();
    image::DynamicImage::ImageLuma8(lisa).write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png).expect("png");
    harness.state_mut().apply_qr_bytes(&png);
    assert!(harness.state().toast.as_ref().is_some_and(|(m, _)| m.contains("no QR code found")), "sem QR: aviso");
    harness.state_mut().apply_qr_bytes(b"isto nao e uma imagem");
    assert!(harness.state().toast.as_ref().is_some_and(|(m, _)| m.contains("image file")), "bytes que não são imagem: aviso");
}

/// Com chave válida no form, a prévia mostra o código atual com a contagem regressiva.
#[test]
fn the_totp_field_shows_a_live_preview() {
    let mut app = unlocked("totp-preview", Prefs::new("en", ThemeMode::Dark));
    app.open_add_form(NewKind::Login);
    app.add_totp = "JBSWY3DPEHPK3PXP".into();
    let mut harness = harness(app);
    harness.run_steps(2);
    let preview = node_text(&harness.get_by_label_contains("current code:"));
    // "current code: 123 456 · 12s" — código em blocos de 3 e a contagem no fim.
    assert!(preview.contains("current code: "), "prévia visível: {preview}");
    let codigo = preview.split(": ").nth(1).unwrap_or_default().split(" ·").next().unwrap_or_default();
    assert_eq!(codigo.len(), 7, "6 dígitos em dois blocos: {codigo:?}");
    assert!(codigo.chars().next().is_some_and(|c| c.is_ascii_digit()));
    assert!(preview.ends_with('s') && preview.contains("·"), "contagem regressiva: {preview}");
}

/// A lista gera o código de um link `otpauth://` GRAVADO (pelo `secret` do link), como o formulário e o
/// Android; base32 puro continua valendo; HOTP e chave inválida saem com o motivo traduzido.
#[test]
fn the_list_generates_the_code_from_a_stored_otpauth_link() {
    let link = "otpauth://totp/GitHub:joao?secret=JBSWY3DPEHPK3PXP&issuer=GitHub";
    // O caminho antigo mandava o texto gravado direto ao núcleo: o link não é base32, então dava erro (com o
    // texto cru, em português, na lista).
    assert!(crate::totp::now(link).is_err(), "o link não é base32 puro");
    assert_eq!(
        match crate::otpauth::parse(link) {
            crate::otpauth::ParseResult::Ok(p) => p.secret,
            crate::otpauth::ParseResult::Invalid(r) => panic!("o link é válido: {r:?}"),
        },
        "JBSWY3DPEHPK3PXP",
        "o código sai do `secret` do link"
    );

    let from_link = crate::totp_list_code(link).expect("link válido gera código");
    assert_eq!(from_link.code.len(), 6);
    assert!(from_link.code.chars().all(|c| c.is_ascii_digit()));
    assert!(from_link.seconds_remaining >= 1 && from_link.seconds_remaining <= 30);

    let from_b32 = crate::totp_list_code("JBSWY3DP EHPK 3PXP").expect("base32 puro gera código");
    assert_eq!(from_b32.code.len(), 6, "base32 puro continua valendo");

    assert_eq!(
        crate::totp_list_code("otpauth://hotp/X?secret=JBSWY3DP&counter=1").err(),
        Some(Some("TOTP: this is not a TOTP link (HOTP is not supported)".to_string())),
        "HOTP: o motivo traduzido do formulário"
    );
    assert_eq!(
        crate::totp_list_code("!!! nao e base32 !!!").err(),
        Some(Some("TOTP: invalid key: it must be base32 (letters A-Z and digits 2-7)".to_string())),
        "chave que não serve: o motivo traduzido do formulário"
    );
    // Segredo vazio: nada a mostrar na linha.
    assert_eq!(crate::totp_list_code("").err(), Some(None));
}

/// As capturas da fase B (TOTP): prévia do código com contagem, aviso de parâmetros fora do padrão e
/// os botões de QR — claro e escuro, 340 e 1100. Só renderiza com `LOCALKEYS_QA_RENDER=1`.
#[test]
fn totp_shots() {
    for (width, label) in [(340.0, "340"), (1100.0, "1100")] {
        for (appearance, name) in [(ThemeMode::Light, "light"), (ThemeMode::Dark, "dark")] {
            // prévia do código
            let mut app = unlocked("shots-totp", Prefs::new("en", appearance));
            app.open_add_form(NewKind::Login);
            app.add_name = "GitHub".into();
            app.add_user = "joao".into();
            app.add_totp = "JBSWY3DPEHPK3PXP".into();
            let mut harness = harness_sized(app, width);
            harness.run_steps(2);
            shot(&mut harness, &format!("totp-preview-{name}-{label}"));

            // aviso de parâmetros fora do padrão (QR que pede 8 dígitos/60 s/SHA-256)
            let mut app = unlocked("shots-totp", Prefs::new("en", appearance));
            app.open_add_form(NewKind::Login);
            app.add_name = "Binance".into();
            app.add_totp = "JBSWY3DPEHPK3PXP".into();
            app.totp_warn =
                Some(crate::unsupported_text(&crate::otpauth::Unsupported { digits: Some(8), period: Some(60), algorithm: Some("SHA256".into()) }));
            let mut harness = harness_sized(app, width);
            harness.run_steps(2);
            shot(&mut harness, &format!("totp-warn-{name}-{label}"));
        }
    }
}

/// A chave TOTP é segredo de longo prazo: o campo nasce mascarado (PasswordInput) e o 👁 revela — o
/// 👁 certo é o primeiro ABAIXO do campo (o da senha fica acima). Fechar o form volta a ocultar.
#[test]
fn the_totp_field_masks_the_key_until_revealed() {
    let mut app = unlocked("totp-mask", Prefs::new("en", ThemeMode::Dark));
    app.open_add_form(NewKind::Login);
    app.add_totp = "JBSWY3DPEHPK3PXP".into();
    let mut harness = harness(app);
    harness.run_steps(2);
    // mascarado: PasswordInput pelo rótulo "TOTP"
    let campo = harness
        .get_all_by_role(Role::PasswordInput)
        .find(|node| node.accesskit_node().label().is_some_and(|l| l == "TOTP"))
        .expect("campo TOTP mascarado");
    let campo_rect = campo.rect();
    // o 👁 do TOTP: o botão 👁 na mesma faixa vertical do campo (à direita dele — o da senha fica
    // numa linha acima).
    let olho = harness
        .get_all_by_role(Role::Button)
        .filter(|node| node_text(node) == "👁")
        .map(|node| node.rect())
        .filter(|rect| rect.min.y <= campo_rect.max.y && rect.max.y >= campo_rect.min.y)
        .min_by(|a, b| a.min.x.total_cmp(&b.min.x))
        .expect("o 👁 do TOTP ao lado do campo");
    click_at(&mut harness, olho.center());
    harness.run_steps(2);
    harness.get_all_by_role(Role::TextInput).find(|node| node.accesskit_node().label().is_some_and(|l| l == "TOTP")).expect("revelado (TextInput)");
    // fechar o form volta a ocultar (o 👁 reseta como o da senha).
    press(&mut harness, egui::Key::Escape, egui::Modifiers::NONE);
    harness.run_steps(2);
    assert!(!harness.state().form_open);
    assert!(!harness.state().reveal_totp, "fecha ocultando de novo");
}

/// Digitando o número do cartão: blocos de 4 na tela, bandeira detectada sozinha, CVV oculto — e o
/// cofre guarda só os dígitos.
#[test]
fn typing_a_card_number_shows_blocks_detects_brand_and_hides_the_code() {
    let mut app = unlocked("card-typing", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("card-typing");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    app.open_add_form(NewKind::Card);
    app.add_name = "Cartão".into();
    let mut harness = harness(app);
    harness.run_steps(2);
    let campo = harness.get_by_label("Number");
    campo.click();
    harness.run_steps(1);
    for chunk in ["4111", "1111", "1111", "1111"] {
        harness.input_mut().events.push(egui::Event::Text(chunk.into()));
        harness.run_steps(1);
    }
    harness.run_steps(1);
    assert_eq!(harness.state().add_number, "4111 1111 1111 1111", "o campo mostra blocos de 4");
    assert_eq!(harness.state().add_brand, "Visa", "bandeira detectada pelo número");
    // o CVV nasce oculto (PasswordInput), como a senha
    assert!(
        harness.get_all_by_role(Role::PasswordInput).any(|n| n.accesskit_node().label().is_some_and(|l| l == "Security code")),
        "o código de segurança é oculto"
    );
    // salvar grava só os dígitos (blocos são só exibição)
    harness.get_by_label("Save").click();
    harness.run_steps(2);
    let raw = harness.state().raw.as_ref().expect("cofre");
    let it = raw["items"].as_array().expect("itens").iter().find(|i| i["name"] == "Cartão").expect("item");
    assert_eq!(it["card"]["number"], "4111111111111111");
    assert_eq!(it["card"]["brand"], "Visa");
}

/// A bandeira digitada pela pessoa fica (a detecção não sobrescreve); apagar tudo devolve a detecção
/// automática pelo número — o `brandAuto` do Android.
#[test]
fn the_detected_brand_follows_the_number_but_a_typed_one_stays() {
    let mut app = unlocked("brand-auto", Prefs::new("en", ThemeMode::Dark));
    app.open_add_form(NewKind::Card);
    app.add_name = "Cartão".into();
    let mut harness = harness(app);
    harness.run_steps(2);
    // bandeira digitada à mão
    let brand = harness.get_by_label("Brand");
    brand.click();
    harness.run_steps(1);
    harness.input_mut().events.push(egui::Event::Text("Minha Loja".into()));
    harness.run_steps(2);
    assert_eq!(harness.state().add_brand, "Minha Loja");
    // o número detectaria Visa, mas a digitada fica
    let campo = harness.get_by_label("Number");
    campo.click();
    harness.run_steps(1);
    harness.input_mut().events.push(egui::Event::Text("4111".into()));
    harness.run_steps(2);
    assert_eq!(harness.state().add_number, "4111");
    assert_eq!(harness.state().add_brand, "Minha Loja", "bandeira digitada não é sobrescrita");
    // apagar a bandeira devolve a detecção automática
    let brand = harness.get_by_label("Brand");
    let borda = egui::pos2(brand.rect().right() - 6.0, brand.rect().center().y);
    click_at(&mut harness, borda);
    harness.run_steps(1);
    for _ in 0.."Minha Loja".len() {
        press(&mut harness, egui::Key::Backspace, egui::Modifiers::NONE);
    }
    harness.run_steps(1);
    assert_eq!(harness.state().add_brand, "", "apagada");
    // voltar ao campo do número (o foco estava na bandeira) e digitar mais: a detecção volta
    let campo = harness.get_by_label("Number");
    let borda_num = egui::pos2(campo.rect().right() - 6.0, campo.rect().center().y);
    click_at(&mut harness, borda_num);
    harness.run_steps(1);
    harness.input_mut().events.push(egui::Event::Text("11".into()));
    harness.run_steps(2);
    assert_eq!(harness.state().add_brand, "Visa", "campo vazio volta a acompanhar o número");
}

/// O aviso de Luhn aparece com 13+ dígitos errados — e NÃO bloqueia o salvar.
#[test]
fn luhn_warns_but_does_not_block_saving() {
    let mut app = unlocked("luhn", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("luhn");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    app.open_add_form(NewKind::Card);
    app.add_name = "Quebrado".into();
    let mut harness = harness(app);
    harness.run_steps(2);
    let campo = harness.get_by_label("Number");
    campo.click();
    harness.run_steps(1);
    for chunk in ["4111", "1111", "1111", "1112"] {
        harness.input_mut().events.push(egui::Event::Text(chunk.into()));
        harness.run_steps(1);
    }
    harness.run_steps(2);
    let aviso = node_text(&harness.get_by_label_contains("fails the card check"));
    assert!(aviso.contains("Luhn"), "aviso visível: {aviso}");
    // sem 13 dígitos ainda não avisa
    // (o aviso é derivado do número a cada quadro; salvar segue de livre)
    harness.get_by_label("Save").click();
    harness.run_steps(2);
    assert!(!harness.state().form_open, "o aviso não bloqueia o salvar: {:?}", harness.state().err);
    let raw = harness.state().raw.as_ref().expect("cofre");
    let it = raw["items"].as_array().expect("itens").iter().find(|i| i["name"] == "Quebrado").expect("item");
    assert_eq!(it["card"]["number"], "4111111111111112", "salvo com o número que falha no Luhn");
}

/// Os atalhos de documento da identidade criam campos personalizados com o nome preenchido e o valor
/// livre; "Documento (outro)" deixa o nome vazio.
#[test]
fn document_shortcuts_create_named_custom_fields() {
    let mut app = unlocked("docs", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("docs");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    app.open_add_form(NewKind::Identity);
    app.add_name = "Documentos".into();
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.get_by_label("Add a document");
    // click_accesskit: o clique comum só pega o que está no viewport do ScrollArea; o chip pode
    // estar na dobra.
    harness.get_by_label("CPF").click_accesskit();
    harness.run_steps(1);
    harness.get_by_label("RG").click_accesskit();
    harness.run_steps(1);
    harness.get_by_label("Document (other)").click_accesskit();
    harness.run_steps(1);
    let nomes: Vec<String> = harness.state().form_custom.iter().map(|f| f.name.clone()).collect();
    assert_eq!(nomes, vec!["CPF", "RG", ""], "atalhos criam campos com o nome pronto; 'outro' fica vazio");
    // o valor é livre
    harness.state_mut().form_custom[0].value = "529.982.247-25".into();
    harness.state_mut().form_custom[2].value = "carteira de motorista antiga".into();
    harness.get_by_label("Save").click();
    harness.run_steps(2);
    let raw = harness.state().raw.as_ref().expect("cofre");
    let it = raw["items"].as_array().expect("itens").iter().find(|i| i["name"] == "Documentos").expect("item");
    let campos = it["customFields"].as_array().expect("campos");
    assert_eq!(campos[0]["name"], "CPF");
    assert_eq!(campos[0]["value"], "529.982.247-25");
    assert_eq!(campos[2]["name"], "");
}

/// O campo CPF formata 000.000.000-00 enquanto digita, avisa quando o verificador não fecha (sem
/// bloquear) e deixa formato colado diferente como veio.
#[test]
fn a_cpf_field_formats_digits_and_warns_on_check_digits() {
    let mut app = unlocked("cpf", Prefs::new("en", ThemeMode::Dark));
    app.open_add_form(NewKind::Identity);
    app.add_name = "Documentos".into();
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.get_by_label("CPF").click_accesskit();
    harness.run_steps(2);
    // o campo novo fica abaixo da dobra do ScrollArea do form: o ponteiro precisa estar sobre ele
    // para a roda rolar (egui rola o que está sob o cursor), e então o clique foca.
    harness.input_mut().events.push(egui::Event::PointerMoved(egui::pos2(170.0, 300.0)));
    harness.run_steps(1);
    for _ in 0..10 {
        harness.input_mut().events.push(egui::Event::MouseWheel {
            unit: egui::MouseWheelUnit::Line,
            delta: egui::vec2(0.0, -3.0),
            phase: egui::TouchPhase::Move,
            source: egui::MouseWheelSource::Unknown,
            modifiers: egui::Modifiers::default(),
        });
        harness.run_steps(1);
    }
    let valor = harness.get_by_label("Value");
    valor.click();
    harness.run_steps(1);
    harness.input_mut().events.push(egui::Event::Text("52998224726".into()));
    harness.run_steps(2);
    assert_eq!(harness.state().form_custom[0].value, "529.982.247-26", "formata enquanto digita");
    let aviso = node_text(&harness.get_by_label_contains("CPF check digits"));
    assert!(aviso.contains("don't match"), "aviso de verificador: {aviso}");
    // corrigir o último dígito: o aviso some
    press(&mut harness, egui::Key::Backspace, egui::Modifiers::NONE);
    harness.input_mut().events.push(egui::Event::Text("5".into()));
    harness.run_steps(2);
    assert_eq!(harness.state().form_custom[0].value, "529.982.247-25");
    assert_eq!(harness.query_all_by_label_contains("CPF check digits").count(), 0, "correto: sem aviso");
    // formato colado diferente fica como colado (mesmos dígitos, sem aviso)
    for _ in 0.."529.982.247-25".len() {
        press(&mut harness, egui::Key::Backspace, egui::Modifiers::NONE);
    }
    harness.input_mut().events.push(egui::Event::Paste("529982247/25".into()));
    harness.run_steps(2);
    assert_eq!(harness.state().form_custom[0].value, "529982247/25", "colado em outro formato fica como veio");
    assert_eq!(harness.query_all_by_label_contains("CPF check digits").count(), 0, "os dígitos conferem: sem aviso");
}

/// As capturas da fase C: formulário de cartão com blocos/bandeira/aviso de Luhn e o de identidade com
/// os atalhos de documento e o aviso de CPF — claro e escuro, 340 e 1100.
#[test]
fn smart_shots() {
    for (width, label) in [(340.0, "340"), (1100.0, "1100")] {
        for (appearance, name) in [(ThemeMode::Light, "light"), (ThemeMode::Dark, "dark")] {
            let mut app = unlocked("shots-c", Prefs::new("en", appearance));
            app.open_add_form(NewKind::Card);
            app.add_name = "Visa".into();
            app.add_cardholder = "João da Silva".into();
            app.add_exp = "01/2027".into();
            app.add_code = "123".into();
            let mut harness = harness_sized(app, width);
            harness.run_steps(2);
            // O número é DIGITADO (não setado no estado): a bandeira só é detectada no `changed`, como
            // a pessoa vê ao preencher o formulário de verdade.
            let campo = harness
                .get_all_by_role(Role::TextInput)
                .find(|node| node.accesskit_node().label().is_some_and(|l| l == "Number"))
                .expect("campo Number do cartão");
            campo.click();
            harness.run_steps(1);
            harness.input_mut().events.push(egui::Event::Text("4111111111111111".into()));
            harness.run_steps(2);
            assert_eq!(harness.state().add_number, "4111 1111 1111 1111", "o número aparece em blocos");
            assert_eq!(harness.state().add_brand, "Visa", "a bandeira é detectada ao digitar");
            shot(&mut harness, &format!("smart-card-form-{name}-{label}"));

            let mut app = unlocked("shots-c", Prefs::new("en", appearance));
            app.open_add_form(NewKind::Identity);
            app.add_name = "Documentos".into();
            app.add_first = "João".into();
            app.add_last = "Silva".into();
            app.form_custom.push(FormCustomField {
                id: String::new(),
                name: "CPF".into(),
                value: "529.982.247-26".into(),
                hidden: false,
                revealed: false,
            });
            let mut harness = harness_sized(app, width);
            harness.run_steps(2);
            shot(&mut harness, &format!("smart-identity-form-{name}-{label}"));
        }
    }
}

/// Grava um cofre cifrado com a sessão de teste (o arquivo da "outra cópia").
fn tkeys_com(session: &crate::crypto::SessionKey, raw: &serde_json::Value, path: &std::path::Path) {
    let bytes = serde_json::to_vec(raw).expect("serializa");
    let sealed = session.seal(&bytes).expect("sela");
    std::fs::write(path, sealed).expect("grava o .tkeys");
}

/// Lê e decifra um `.tkeys` da sessão de teste (para conferir os dois arquivos depois da sincronização).
fn abrir_tkeys(session: &crate::crypto::SessionKey, path: &std::path::Path) -> serde_json::Value {
    let file = std::fs::read(path).expect("lê o .tkeys");
    let (plain, _) = crate::crypto::open_with_key(&session.key_bytes(), &file).expect("abre");
    serde_json::from_slice(&plain).expect("cofre válido")
}

/// O resumo da sincronização aparece ANTES de gravar, e "Gravar nas duas cópias" deixa os dois
/// arquivos com o mesmo conteúdo (lidos de novo do disco, decifrados e comparados por conteúdo).
#[test]
fn the_sync_summary_shows_before_writing_and_updates_both_files() {
    let mut app = unlocked("sync", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("sync");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("a.tkeys").display().to_string();
    let raw_atual = app.raw.clone().expect("cofre");
    app.persist(raw_atual.clone()).then_some(()).expect("grava o cofre atual");

    // A outra cópia: sem um item daqui (que vai para ela) e com um item novo (que vem para cá).
    let mut other = raw_atual.clone();
    other["items"].as_array_mut().expect("itens").pop();
    let mut com_novo = other.clone();
    com_novo["items"].as_array_mut().expect("itens").push(serde_json::json!({
        "id": "novo-da-outra", "kind": "login", "name": "Novo da outra", "favorite": false,
        "folderId": null, "notes": "", "createdAt": 1, "updatedAt": 1, "deletedAt": null,
        "login": {"username": "u", "password": "p", "uris": [], "totp": ""}
    }));
    let other_path = dir.join("other.tkeys");
    // A outra cópia é cifrada com a MESMA sessão do cofre aberto (cópias do mesmo cofre, mesma senha).
    tkeys_com(app.session.as_ref().expect("sessão"), &com_novo, &other_path);

    // O resumo aparece ANTES de gravar: nada mudou nos arquivos ainda.
    app.sync_with_other(&other_path.display().to_string());
    assert!(app.sync_open, "modal do resumo");
    let antes = std::fs::read(&other_path).expect("outra cópia ainda no disco");
    let mut harness = harness(app);
    harness.run_steps(2);
    let linha = node_text(&harness.get_by_label_contains("added from the other copy"));
    assert!(linha.contains("1 item added"), "resumo antes de gravar, no singular: {linha}");
    harness.get_by_label_contains("1 item goes to the other copy");

    // Gravar: os DOIS arquivos ficam iguais e o cofre adota o item novo.
    harness.get_by_label("Save to both copies").click();
    harness.run_steps(2);
    assert!(!harness.state().sync_open);
    assert!(harness.state().toast.as_ref().is_some_and(|(m, _)| m.contains("identical")), "toast de sincronizado");
    let a = abrir_tkeys(harness.state().session.as_ref().expect("sessão"), &dir.join("a.tkeys"));
    let b = abrir_tkeys(harness.state().session.as_ref().expect("sessão"), &other_path);
    assert!(crate::merge::json_same(&a, &b), "as duas cópias ficaram iguais");
    assert!(a["items"].as_array().is_some_and(|arr| arr.iter().any(|i| i["name"] == "Novo da outra")));
    assert_ne!(std::fs::read(&other_path).expect("regravada"), antes, "a outra cópia mudou no disco");
    assert!(crate::copia_abre(harness.state().session.as_ref().expect("sessão"), &other_path));
}

/// O resumo da sincronização usa a forma certa do plural: "2 items added" ao lado de "1 item updated",
/// nunca "1 items".
#[test]
fn the_sync_summary_pluralizes_counts() {
    let mut app = unlocked("sync-plural", Prefs::new("en", ThemeMode::Dark));
    app.sync_plan = Some(crate::SyncPlan {
        other_path: r"C:\Users\joao\OneDrive\cofre.tkeys".into(),
        merged: app.raw.clone().expect("cofre"),
        summary: crate::merge::MergeSummary {
            added: 2,
            updated: 1,
            trashed: 0,
            restored: 0,
            conflicts: 0,
            folders_added: 0,
            to_other: 0,
            folders_to_other: 0,
        },
    });
    app.sync_open = true;
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.get_by_label_contains("2 items added from the other copy");
    harness.get_by_label_contains("1 item updated (the other copy was newer)");
    assert_eq!(harness.query_all_by_label_contains("1 items").count(), 0, "nada de \"1 items\"");
}

/// O relatório da importação usa o plural: "1 item imported" e "2 cards/identities skipped".
#[test]
fn the_import_report_pluralizes_counts() {
    let mut app = unlocked("import-report", Prefs::new("en", ThemeMode::Dark));
    app.import_report = Some((1, 2));
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.get_by_label_contains("1 item imported");
    harness.get_by_label_contains("2 cards/identities skipped — Local Keys keeps logins and notes");
    assert_eq!(harness.query_all_by_label_contains("1 items").count(), 0, "nada de \"1 items\"");
}

/// "Salvar cópia em pasta sincronizada": a cópia é conferida (abre), passa a ser o cofre em uso e a
/// antiga fica no disco como backup.
#[test]
fn carrying_the_vault_checks_the_copy_and_switches_to_it() {
    let mut app = unlocked("carry", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("carry");
    let synced = dir.join("OneDrive");
    std::fs::create_dir_all(&synced).expect("pasta sincronizada");
    let original = dir.join("orig.tkeys");
    app.path = original.display().to_string();
    let raw = app.raw.clone().expect("cofre");
    app.persist(raw).then_some(()).expect("grava o cofre atual");

    let destino = synced.join("cofre.tkeys");
    app.carry_vault_to(&destino);
    assert_eq!(app.path, destino.display().to_string(), "passa a usar a cópia");
    assert!(destino.exists(), "a cópia existe");
    assert!(crate::copia_abre(app.session.as_ref().expect("sessão"), &destino), "a cópia abre com a mesma senha");
    assert!(original.exists(), "a antiga fica como backup");
    assert!(app.toast.as_ref().is_some_and(|(m, _)| m.contains("vault now")));
}

/// Bordas da sincronização: arquivo que não abre (senha errada/corrompido), o próprio cofre em uso e
/// uma cópia já idêntica (nada a fazer).
#[test]
fn sync_edges_wrong_password_same_file_and_identical_copy() {
    let mut app = unlocked("sync-edge", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("sync-edge");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("a.tkeys").display().to_string();
    let raw = app.raw.clone().expect("cofre");
    app.persist(raw.clone()).then_some(()).expect("grava");

    // Bytes que não são um .tkeys: o erro diz O QUÊ (não é um cofre .tkeys), sem pinar mensagem de
    // senha para arquivo que nem é cofre.
    let lixo = dir.join("lixo.tkeys");
    std::fs::write(&lixo, b"isto nao e um cofre").expect("grava lixo");
    app.sync_with_other(&lixo.display().to_string());
    assert!(!app.sync_open);
    assert!(app.err.as_deref().is_some_and(|e| e.contains(".tkeys vault")), "{:?}", app.err);

    // JSON decifrado com a senha certa, mas sem cara de cofre (array): erro de cofre válido e NADA
    // gravado — hoje seria "cofre vazio" e a outra cópia seria regravada.
    let lista = dir.join("lista.tkeys");
    let selado = app.session.as_ref().expect("sessão").seal(b"[1,2,3]").expect("sela");
    std::fs::write(&lista, &selado).expect("grava o array cifrado");
    let antes_atual = std::fs::read(dir.join("a.tkeys")).expect("cofre atual no disco");
    app.err = None;
    app.sync_with_other(&lista.display().to_string());
    assert!(app.err.as_deref().is_some_and(|e| e.contains("valid vault")), "{:?}", app.err);
    assert!(!app.sync_open, "nada de modal");
    assert_eq!(std::fs::read(&lista).expect("outro"), selado, "a outra cópia intocada");
    assert_eq!(std::fs::read(dir.join("a.tkeys")).expect("atual"), antes_atual, "o cofre atual intocado");

    // O próprio arquivo em uso é barrado.
    app.err = None;
    let em_uso = app.path.trim().to_string();
    app.sync_with_other(&em_uso);
    assert!(app.err.as_deref().is_some_and(|e| e.contains("vault in use")), "{:?}", app.err);

    // Cópia idêntica: o resumo diz que não há nada a fazer (e só oferece fechar).
    let copia = dir.join("copia.tkeys");
    tkeys_com(app.session.as_ref().expect("sessão"), &raw, &copia);
    app.err = None;
    app.sync_with_other(&copia.display().to_string());
    assert!(app.sync_open);
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.get_by_label_contains("already identical");
    assert!(harness.query_by_label("Save to both copies").is_none(), "sem gravar quando nada muda");
}

/// As capturas da fase D: o resumo da sincronização antes de gravar, claro e escuro, 340 e 1100.
#[test]
fn sync_shots() {
    for (width, label) in [(340.0, "340"), (1100.0, "1100")] {
        for (appearance, name) in [(ThemeMode::Light, "light"), (ThemeMode::Dark, "dark")] {
            let mut app = unlocked("shots-sync", Prefs::new("en", appearance));
            app.sync_plan = Some(crate::SyncPlan {
                other_path: r"C:\Users\joao\OneDrive\cofre.tkeys".into(),
                merged: app.raw.clone().expect("cofre"),
                summary: crate::merge::MergeSummary {
                    added: 2,
                    updated: 1,
                    trashed: 1,
                    restored: 1,
                    conflicts: 1,
                    folders_added: 1,
                    to_other: 3,
                    folders_to_other: 1,
                },
            });
            app.sync_open = true;
            let mut harness = harness_sized(app, width);
            harness.run_steps(2);
            shot(&mut harness, &format!("sync-summary-{name}-{label}"));
        }
    }
}

/// As larguras das capturas de revisão (mínima e média), com a barra de cima à mostra nos dois temas.
#[test]
fn review_shots() {
    for (width, label) in [(340.0, "340"), (1100.0, "1100")] {
        for (appearance, name) in [(ThemeMode::Light, "light"), (ThemeMode::Dark, "dark")] {
            let mut harness = harness_sized(app("shots", Prefs::new("en", appearance)), width);
            harness.run_steps(2);
            shot(&mut harness, &format!("bar-{name}-{label}"));
            click_bar(&mut harness, GEAR);
            shot(&mut harness, &format!("settings-{name}-{label}"));
            press(&mut harness, egui::Key::Escape, egui::Modifiers::NONE);
            click_bar(&mut harness, SEARCH);
            shot(&mut harness, &format!("palette-{name}-{label}"));

            let mut harness = harness_sized(unlocked("shots-vault", Prefs::new("en", appearance)), width);
            harness.run_steps(2);
            shot(&mut harness, &format!("vault-{name}-{label}"));
            click_bar(&mut harness, SEARCH);
            shot(&mut harness, &format!("vault-palette-{name}-{label}"));
            press(&mut harness, egui::Key::Escape, egui::Modifiers::NONE);
            click_more(&mut harness);
            shot(&mut harness, &format!("vault-more-{name}-{label}"));
            press(&mut harness, egui::Key::Escape, egui::Modifiers::NONE);
            harness.get_by_label("Folders").click();
            harness.run_steps(2);
            shot(&mut harness, &format!("vault-folders-{name}-{label}"));
        }
    }
}

/// Ctrl+clique marca uma linha e desmarca na segunda; a barra conta os marcados e a linha marcada
/// expõe o estado de seleção para o leitor de tela. Clique simples não mexe na seleção.
#[test]
fn ctrl_click_selects_two_rows_and_the_bar_counts_them() {
    let mut harness = harness(unlocked("select-ctrl", Prefs::new("en", ThemeMode::Dark)));
    harness.run_steps(2);
    click_row(&mut harness, "192.168.1.34", egui::Modifiers::COMMAND);
    click_row(&mut harness, "1lib.sk", egui::Modifiers::COMMAND);
    assert_eq!(harness.state().selection.len(), 2);
    let id = item_id(harness.state(), "1lib.sk");
    assert!(harness.state().selection.contains(&id));
    harness.get_by_label("2 selected");
    let node = harness.get_by_label_contains("1lib.sk");
    assert_eq!(node.accesskit_node().data().toggled(), Some(Toggled::True), "a linha marcada expõe o estado de seleção");
    // O clique simples na linha continua como hoje: não mexe na seleção.
    click_row(&mut harness, "account.matrix.org", egui::Modifiers::NONE);
    assert_eq!(harness.state().selection.len(), 2);
}

/// Shift+clique seleciona o intervalo pela ordem visível (a mesma da lista filtrada).
#[test]
fn shift_click_selects_the_visible_range() {
    let mut harness = harness(unlocked("select-shift", Prefs::new("en", ThemeMode::Dark)));
    harness.run_steps(2);
    click_row(&mut harness, "192.168.1.34", egui::Modifiers::COMMAND);
    click_row(&mut harness, "account.matrix.org", egui::Modifiers::SHIFT);
    // Ordem visível: 192.168.1.34 (favorito), 1lib.sk, account.matrix.org.
    assert_eq!(harness.state().selection.len(), 3, "as {:?}", harness.state().selection.ids().collect::<Vec<_>>());
    harness.get_by_label("3 selected");
}

/// Ctrl+A pega tudo que está visível e `Esc` limpa; a barra some com a seleção vazia.
#[test]
fn ctrl_a_selects_visible_items_and_escape_clears() {
    let mut harness = harness(unlocked("select-all", Prefs::new("en", ThemeMode::Dark)));
    harness.run_steps(2);
    press(&mut harness, egui::Key::A, egui::Modifiers::COMMAND);
    assert_eq!(harness.state().selection.len(), 3);
    harness.get_by_label("3 selected");
    press(&mut harness, egui::Key::Escape, egui::Modifiers::NONE);
    assert!(harness.state().selection.is_empty());
    assert!(harness.query_by_label("3 selected").is_none(), "a barra some com a seleção vazia");
}

/// Trocar a busca zera a seleção: os ids marcados podem nem estar na lista nova.
#[test]
fn changing_the_search_clears_the_selection() {
    let mut harness = harness(unlocked("select-search", Prefs::new("en", ThemeMode::Dark)));
    harness.run_steps(2);
    click_row(&mut harness, "192.168.1.34", egui::Modifiers::COMMAND);
    click_row(&mut harness, "1lib.sk", egui::Modifiers::COMMAND);
    assert_eq!(harness.state().selection.len(), 2);
    harness.get_by_role(Role::TextInput).click();
    harness.run_steps(2);
    harness.input_mut().events.push(egui::Event::Text("1lib".into()));
    harness.run_steps(2);
    assert_eq!(harness.state().search, "1lib", "o texto entrou no campo de busca");
    assert!(harness.state().selection.is_empty(), "a busca nova não herda a seleção da lista antiga");
}

/// Trocar o filtro de tipo também zera a seleção (a lista visível muda).
#[test]
fn changing_the_filter_clears_the_selection() {
    let mut harness = harness(unlocked("select-filter", Prefs::new("en", ThemeMode::Dark)));
    harness.run_steps(2);
    click_row(&mut harness, "192.168.1.34", egui::Modifiers::COMMAND);
    click_row(&mut harness, "1lib.sk", egui::Modifiers::COMMAND);
    assert_eq!(harness.state().selection.len(), 2);
    harness.get_by_label("Filter").click();
    harness.run_steps(2);
    harness.get_by_label("Cards").click();
    harness.run_steps(2);
    assert!(!harness.state().filters.is_clear(), "o filtro mudou");
    assert!(harness.state().selection.is_empty(), "a lista filtrada não herda a seleção da anterior");
}

/// "Move to folder…" põe os dois itens na pasta com UMA gravação; com o filtro por pasta a lista
/// passa a mostrar só eles.
#[test]
fn moving_the_selection_to_a_folder_updates_both_items_in_one_write() {
    let mut app = unlocked_folder("select-move", "Work", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("select-move");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let baseline = persist_now(&mut app);
    let folder_id = app.raw.as_ref().and_then(|r| crate::vault::folders(r).into_iter().next()).map(|f| f.id).expect("pasta criada");
    let mut harness = harness(app);
    harness.run_steps(2);
    click_row(&mut harness, "192.168.1.34", egui::Modifiers::COMMAND);
    click_row(&mut harness, "1lib.sk", egui::Modifiers::COMMAND);
    harness.get_by_label("Move to folder…").click();
    harness.run_steps(2);
    click_menu_item(&mut harness, "Work");
    assert_eq!(harness.state().persist_count - baseline, 1, "o lote grava uma vez só");
    for name in ["192.168.1.34", "1lib.sk"] {
        let id = item_id(harness.state(), name);
        assert_eq!(item_by_id(harness.state(), &id)["folderId"], serde_json::Value::String(folder_id.clone()), "{name} foi para a pasta");
    }
    assert!(harness.state().selection.is_empty());
    // Com o filtro por pasta, só os dois aparecem.
    harness.get_by_label("Work").click();
    harness.run_steps(2);
    assert_eq!(harness.state().folder_filter.as_deref(), Some(folder_id.as_str()));
    assert!(harness.query_by_label_contains("192.168.1.34").is_some());
    assert!(harness.query_by_label_contains("1lib.sk").is_some());
    assert!(harness.query_by_label_contains("account.matrix.org").is_none(), "o que ficou fora da pasta sai da lista");
}

/// "Delete selected" pede confirmação com a contagem, manda os dois para a lixeira com UMA gravação e
/// limpa a seleção; o `Delete` do teclado abre a mesma confirmação.
#[test]
fn deleting_the_selection_asks_with_the_count_and_moves_items_to_the_trash() {
    let mut app = unlocked("select-delete", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("select-delete");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let baseline = persist_now(&mut app);
    let ids = [item_id(&app, "192.168.1.34"), item_id(&app, "1lib.sk")];
    let mut harness = harness(app);
    harness.run_steps(2);
    click_row(&mut harness, "192.168.1.34", egui::Modifiers::COMMAND);
    click_row(&mut harness, "1lib.sk", egui::Modifiers::COMMAND);
    // O teclado aciona a mesma confirmação; cancelar não muda nada.
    press(&mut harness, egui::Key::Delete, egui::Modifiers::NONE);
    harness.get_by_label("Move 2 items to the trash?");
    harness.get_by_label("Cancel").click();
    harness.run_steps(2);
    assert_eq!(harness.state().selection.len(), 2, "cancelar preserva a seleção");
    harness.get_by_label("Delete selected").click();
    harness.run_steps(2);
    harness.get_by_label("Move 2 items to the trash?");
    harness.get_by_label("Confirm").click();
    harness.run_steps(2);
    assert_eq!(harness.state().persist_count - baseline, 1, "o lote grava uma vez só");
    assert!(harness.state().selection.is_empty());
    assert!(harness.query_by_label("2 selected").is_none(), "a barra some com a seleção vazia");
    for (name, id) in [("192.168.1.34", &ids[0]), ("1lib.sk", &ids[1])] {
        assert!(harness.state().items.iter().all(|item| &item.id != id), "{name} saiu da lista principal");
        let item = item_by_id(harness.state(), id);
        assert!(item.get("deletedAt").is_some_and(|v| !v.is_null()), "{name} foi para a lixeira");
        assert_eq!(item["deletedAt"], item["updatedAt"], "o lote carimba os dois campos com o mesmo now");
    }
    assert!(harness.query_by_label_contains("192.168.1.34").is_none(), "a linha apagada sai da tela");
}

/// Um item da seleção que sumiu do cofre: mensagem de erro traduzida e NADA gravado.
#[test]
fn a_missing_selected_item_reports_the_error_and_writes_nothing() {
    let mut app = unlocked("select-missing", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("select-missing");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let baseline = persist_now(&mut app);
    let antes = std::fs::read(&app.path).expect("cofre em disco");
    app.selection.select_only("fantasma");
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.get_by_label("Delete selected").click();
    harness.run_steps(2);
    harness.get_by_label("Move 1 item to the trash?");
    harness.get_by_label("Confirm").click();
    harness.run_steps(2);
    let erro = harness.state().err.clone().expect("a mensagem do lote");
    assert!(erro.contains("no longer exists"), "mensagem traduzida da variante: {erro}");
    assert_eq!(harness.state().persist_count, baseline, "nada foi gravado");
    assert_eq!(std::fs::read(&harness.state().path).expect("cofre em disco"), antes, "o arquivo ficou byte a byte igual");
}

/// Uma release falsa só para o prompt do atualizador abrir (nada é baixado, nada instala).
fn fake_release() -> craft_update::Release {
    craft_update::Release {
        tag: "v99.0.0".into(),
        version: craft_update::parse_version("99.0.0"),
        name: "Local Keys v99.0.0".into(),
        published_at: None,
        html_url: "https://github.com/Anon5T4R/Local-Keys/releases/tag/v99.0.0".into(),
        notes: String::new(),
        prerelease: false,
        assets: Vec::new(),
    }
}

/// O prompt do atualizador aberto é um modal como os outros: o teclado da seleção (`Delete` inclusive) não age
/// por baixo dele.
#[test]
fn the_open_update_prompt_keeps_the_selection_keys_off_the_list() {
    let mut app = unlocked("select-update-prompt", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("select-update-prompt");
    // Sem o arquivo do launcher: o prompt pode abrir.
    app.updater = app.updater.with_pins_path(dir.join("pins.json"));
    let mut harness = harness(app);
    harness.run_steps(2);
    click_row(&mut harness, "192.168.1.34", egui::Modifiers::COMMAND);
    click_row(&mut harness, "1lib.sk", egui::Modifiers::COMMAND);
    assert_eq!(harness.state().selection.len(), 2);

    harness.state_mut().updater.offer(fake_release());
    harness.run_steps(2);
    assert!(harness.state().updater.prompt_open(), "o prompt do atualizador está aberto");
    assert!(harness.state().any_modal_open(), "e conta como modal para o teclado da seleção");

    press(&mut harness, egui::Key::Delete, egui::Modifiers::NONE);
    assert!(harness.state().batch_delete_ask.is_none(), "o Delete não abre a confirmação por baixo do prompt");
    assert_eq!(harness.state().selection.len(), 2, "a seleção fica intacta");
}

/// Mover a seleção para a pasta em que os itens JÁ estão não muda nada: nada é gravado e a seleção continua para
/// a pessoa escolher outro destino.
#[test]
fn moving_the_selection_into_the_folder_it_is_already_in_writes_nothing() {
    let (mut app, folder_id) = unlocked_in_folder("select-same-move", "Work", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("select-same-move");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let baseline = persist_now(&mut app);
    let mut harness = harness(app);
    harness.run_steps(2);
    click_row(&mut harness, "192.168.1.34", egui::Modifiers::COMMAND);
    click_row(&mut harness, "1lib.sk", egui::Modifiers::COMMAND);
    harness.get_by_label("Move to folder…").click();
    harness.run_steps(2);
    click_menu_item(&mut harness, "Work");
    assert_eq!(harness.state().persist_count, baseline, "os itens já estavam na pasta: nada é gravado");
    assert_eq!(harness.state().selection.len(), 2, "sem mudança nenhuma, a seleção fica");
    assert!(harness.state().err.is_none(), "e nenhum erro aparece: {:?}", harness.state().err);
    for name in ["192.168.1.34", "1lib.sk"] {
        let id = item_id(harness.state(), name);
        assert_eq!(item_by_id(harness.state(), &id)["folderId"], serde_json::Value::String(folder_id.clone()), "{name} continua na pasta");
    }
}

/// "No folder" no menu de mover é uma operação como as outras: tira os itens da pasta com UMA gravação e limpa a
/// seleção.
#[test]
fn moving_the_selection_to_no_folder_takes_them_out_of_the_folder() {
    let (mut app, _folder_id) = unlocked_in_folder("select-no-folder", "Work", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("select-no-folder");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let baseline = persist_now(&mut app);
    let mut harness = harness(app);
    harness.run_steps(2);
    click_row(&mut harness, "192.168.1.34", egui::Modifiers::COMMAND);
    click_row(&mut harness, "1lib.sk", egui::Modifiers::COMMAND);
    harness.get_by_label("Move to folder…").click();
    harness.run_steps(2);
    click_menu_item(&mut harness, "No folder");
    assert_eq!(harness.state().persist_count - baseline, 1, "o lote grava uma vez só");
    assert!(harness.state().selection.is_empty(), "a barra some com a seleção vazia");
    for name in ["192.168.1.34", "1lib.sk"] {
        let id = item_id(harness.state(), name);
        assert!(item_by_id(harness.state(), &id)["folderId"].is_null(), "{name} saiu da pasta");
    }
}

/// Um item marcado que sai da LISTA por outra via (o ★ desmarcado com o filtro "Favoritos" ligado) sai também da
/// seleção: o rodapé não pode contar nem apagar às cegas um item que não está na tela.
#[test]
fn a_marked_row_that_leaves_the_list_drops_out_of_the_selection() {
    let mut app = unlocked("select-prune", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("select-prune");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let mut harness = harness(app);
    harness.run_steps(2);

    // Só o favorito na lista: desfavoritá-lo tira a própria linha que está marcada. (O filtro entra direto no
    // estado: o que este teste exercita é a poda da seleção, não o popup de filtros.)
    harness.state_mut().filters.only.favorites = true;
    harness.run_steps(2);
    click_row(&mut harness, "192.168.1.34", egui::Modifiers::COMMAND);
    assert_eq!(harness.state().selection.len(), 1);
    harness.get_by_label("1 selected");

    let id = item_id(harness.state(), "192.168.1.34");
    harness.state_mut().toggle_favorite(&id);
    harness.run_steps(2);

    assert!(harness.state().selection.is_empty(), "o item marcado saiu da lista: sai da seleção");
    assert!(harness.query_by_label_contains("selected").is_none(), "o rodapé some junto");
    assert!(harness.state().err.is_none(), "a poda não inventa erro: {:?}", harness.state().err);
}

/// O menu "Move to folder…" é um popup do egui: com ele aberto o teclado é dele — `Esc` fecha o menu em vez de
/// limpar a seleção, e `Delete` não abre a confirmação por baixo.
#[test]
fn an_open_popup_keeps_the_selection_keys_off_the_list() {
    let mut harness = harness(unlocked("select-popup", Prefs::new("en", ThemeMode::Dark)));
    harness.run_steps(2);
    click_row(&mut harness, "192.168.1.34", egui::Modifiers::COMMAND);
    click_row(&mut harness, "1lib.sk", egui::Modifiers::COMMAND);
    assert_eq!(harness.state().selection.len(), 2);

    harness.get_by_label("Move to folder…").click();
    harness.run_steps(2);
    assert!(harness.ctx.any_popup_open(), "o menu de mover está aberto como popup do egui");

    press(&mut harness, egui::Key::Delete, egui::Modifiers::NONE);
    assert!(harness.state().batch_delete_ask.is_none(), "o Delete não abre a confirmação por baixo do menu");
    assert_eq!(harness.state().selection.len(), 2, "a seleção fica intacta");

    press(&mut harness, egui::Key::Escape, egui::Modifiers::NONE);
    assert_eq!(harness.state().selection.len(), 2, "o Esc fecha o menu, não limpa a seleção");
}

/// Um cofre com uma pasta, um item COM anexo nela e os outros fora — para o export levar só o escolhido.
fn unlocked_for_vault_export(name: &str, prefs: Prefs) -> (KeysApp, String, String) {
    let mut app = unlocked(name, prefs);
    let mut raw = app.raw.clone().expect("cofre aberto");
    crate::vault::add_folder(&mut raw, "Work");
    let folder_id = crate::vault::folders(&raw).into_iter().next().map(|f| f.id).expect("pasta criada");
    let item_id = raw["items"]
        .as_array()
        .and_then(|items| items.iter().find(|item| item["name"] == "192.168.1.34"))
        .and_then(|item| item["id"].as_str())
        .map(str::to_string)
        .expect("item do fixture");
    crate::vault::set_item_folder(&mut raw, &item_id, Some(&folder_id));
    assert!(crate::vault::add_attachment(&mut raw, &item_id, "nota.txt", "text/plain", "aGk="), "anexo do fixture");
    app.adopt(crate::crypto::test_session(), raw);
    (app, item_id, folder_id)
}

/// KDF mínimo: o export não pode custar 1 s de Argon2id em cada rodada do gate.
fn light_kdf() -> crate::crypto::KdfParams {
    crate::crypto::KdfParams { m_cost: 512, t_cost: 1, p_cost: 1 }
}

/// O diálogo "Export to vault" desenha os dois campos de senha com largura usável em 480 px (eles colapsavam num
/// quadradinho dentro do `Grid`).
#[test]
fn the_export_vault_password_fields_have_a_usable_width_at_480() {
    let (mut app, item_id, _folder) = unlocked_for_vault_export("vexport-width", Prefs::new("en", ThemeMode::Dark));
    app.selection.select_only(&item_id);
    let mut harness = harness(app);
    harness.run_steps(2);
    harness.state_mut().export_vault_chosen("C:\\tmp\\cofre-exportado.tkeys".into());
    harness.run_steps(2);
    assert!(harness.state().export_vault_open, "o diálogo das senhas abriu");

    let fields: Vec<_> = harness.get_all_by_role(Role::PasswordInput).collect();
    assert!(fields.len() >= 2, "os dois campos de senha estão na árvore: {}", fields.len());
    for (i, field) in fields.iter().take(2).enumerate() {
        let rect = field.rect();
        assert!(rect.width() >= 150.0, "campo {i} com {} px (diálogo de 400 px)", rect.width());
        assert!(harness.ctx.content_rect().contains_rect(rect), "campo {i} fora da janela: {rect:?}");
    }
}

/// A barra da seleção mostra "Clear selection" DENTRO da janela em 480 px (antes a ação ficava cortada na borda).
#[test]
fn the_selection_bar_shows_clear_selection_inside_the_window_at_480() {
    let mut app = unlocked("selbar-480", Prefs::new("en", ThemeMode::Dark));
    app.selection.select_only(&item_id(&app, "1lib.sk"));
    let mut harness = harness(app);
    harness.run_steps(2);

    let clear = harness.get_by_label("Clear selection");
    let rect = clear.rect();
    let window = harness.ctx.content_rect();
    assert!(window.contains_rect(rect), "Clear selection em {rect:?}, janela {window:?}");

    clear.click();
    harness.run_steps(2);
    assert!(harness.state().selection.is_empty(), "o botão limpa a seleção");
}

/// Com a lista mais alta que a janela (10 itens, 480×660 e 480×780), o rodapé reserva o próprio espaço antes da
/// lista: o "Clear selection" fica dentro da janela e funciona. Antes, o `ScrollArea` tomava toda a altura e a
/// barra desenhada depois caía fora.
#[test]
fn the_selection_bar_fits_in_the_window_with_a_long_list() {
    for (width, height) in [(480.0, 660.0), (480.0, 780.0)] {
        let mut app = unlocked_with(10, "selbar-long", Prefs::new("en", ThemeMode::Dark));
        app.selection.select_only(&item_id(&app, "conta-00.exemplo.com"));
        let mut harness = harness_wh(app, width, height);
        harness.run_steps(2);

        let clear = harness.get_by_label("Clear selection");
        let rect = clear.rect();
        let window = harness.ctx.content_rect();
        assert!(window.contains_rect(rect), "Clear selection em {rect:?}, janela {window:?} ({width}×{height})");

        clear.click();
        harness.run_steps(2);
        assert!(harness.state().selection.is_empty(), "o botão limpa a seleção ({width}×{height})");
    }
}

/// A ação de exportar o cofre novo só existe com itens marcados (como as outras da barra de seleção).
#[test]
fn the_export_to_vault_action_only_appears_with_a_selection() {
    let mut harness = harness(unlocked("vexport-action", Prefs::new("en", ThemeMode::Dark)));
    harness.run_steps(2);
    assert!(harness.query_by_label("Export to vault…").is_none(), "sem seleção não há ação de exportar");

    click_row(&mut harness, "1lib.sk", egui::Modifiers::COMMAND);

    harness.get_by_label("Export to vault…");
}

/// Senhas vazias ou diferentes ficam no diálogo com a mensagem: nenhum job começa e nada é escrito.
#[test]
fn exporting_checks_the_password_twice_before_starting() {
    let (mut app, item_id, _folder) = unlocked_for_vault_export("vexport-check", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("vexport-check");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    let target = dir.join("nao-criado.tkeys");
    app.export_vault_kdf = light_kdf();
    app.selection.select_only(&item_id);
    let mut harness = harness(app);
    harness.run_steps(2);

    // O seletor nativo não roda no kittest: o caminho entra pelo mesmo ponto da resposta dele.
    harness.state_mut().export_vault_chosen(target.display().to_string());
    harness.run_steps(2);
    assert!(harness.state().export_vault_open, "o diálogo das senhas abriu");
    // O campo da confirmação só existe neste diálogo (a lista de trás também tem um botão "Password").
    harness.get_by_label("Confirm password");

    harness.state_mut().export_vault_pass = "uma-senha".into();
    harness.state_mut().export_vault_confirm = "outra-senha".into();
    harness.get_by_label("Create vault").click();
    harness.run_steps(2);
    assert_eq!(harness.state().export_vault_err.as_deref(), Some("the passwords do not match"));
    harness.get_by_label("the passwords do not match");
    assert!(harness.state().job_label.is_none(), "senhas diferentes não disparam o job");
    assert!(!target.exists(), "nada é escrito");

    harness.state_mut().export_vault_pass.clear();
    harness.state_mut().export_vault_confirm.clear();
    harness.get_by_label("Create vault").click();
    harness.run_steps(2);
    assert_eq!(harness.state().export_vault_err.as_deref(), Some("the password cannot be empty"));
    assert!(harness.state().job_label.is_none(), "senha vazia não dispara o job");
    assert!(!target.exists());
}

/// Fluxo completo: o arquivo novo reabre com a senha, tem SÓ os itens selecionados (com o anexo junto) e só as
/// pastas usadas — e o cofre aberto não é gravado nem muda.
#[test]
fn exporting_the_selection_writes_a_new_vault_that_reopens() {
    let (mut app, item_id, folder_id) = unlocked_for_vault_export("vexport-flow", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("vexport-flow");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    let target = dir.join("novo-cofre.tkeys");
    app.export_vault_kdf = light_kdf();
    app.selection.select_only(&item_id);
    let baseline = app.persist_count;
    let before_items = app.items.len();
    let mut harness = harness(app);
    harness.run_steps(2);

    harness.get_by_label("Export to vault…");
    harness.state_mut().export_vault_chosen(target.display().to_string());
    harness.run_steps(2);
    harness.state_mut().export_vault_pass = "senha-do-cofre-novo".into();
    harness.state_mut().export_vault_confirm = "senha-do-cofre-novo".into();
    harness.get_by_label("Create vault").click();
    wait_for_job(&mut harness);

    assert!(harness.state().err.is_none(), "o export não deu erro: {:?}", harness.state().err);
    assert!(!harness.state().export_vault_open, "o diálogo fecha ao disparar");
    assert!(target.is_file(), "o cofre novo foi escrito");

    let bytes = std::fs::read(&target).expect("o arquivo do cofre novo");
    let (plaintext, _session) = crate::crypto::open_vault("senha-do-cofre-novo", &bytes).expect("o cofre novo reabre com a senha");
    let exported: serde_json::Value = serde_json::from_slice(&plaintext).expect("o cofre novo é JSON");
    let ids: Vec<&str> = exported["items"].as_array().expect("itens").iter().filter_map(|item| item["id"].as_str()).collect();
    assert_eq!(ids, [item_id.as_str()], "só o item selecionado, com o mesmo id");
    let folders: Vec<&str> = exported["folders"].as_array().expect("pastas").iter().filter_map(|folder| folder["id"].as_str()).collect();
    assert_eq!(folders, [folder_id.as_str()], "só a pasta que o item usa");
    let attachments = exported["items"][0]["attachments"].as_array().expect("anexos do item exportado");
    assert_eq!(attachments.len(), 1, "o anexo vai junto");
    assert_eq!(attachments[0]["name"], "nota.txt");

    assert_eq!(harness.state().persist_count, baseline, "exportar não grava o cofre aberto");
    assert_eq!(harness.state().items.len(), before_items, "a lista aberta não muda");
    assert_eq!(harness.state().selection.len(), 1, "a seleção continua para o próximo passo");
    assert!(harness.state().selection.contains(&item_id));
}

/// Um id marcado que não existe mais (o cofre mudou por fora) vira a mensagem traduzida e nenhum arquivo é criado.
#[test]
fn a_selection_that_no_longer_exists_reports_and_writes_nothing() {
    let (mut app, _item_id, _folder) = unlocked_for_vault_export("vexport-missing", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("vexport-missing");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    let target = dir.join("nao-criado.tkeys");
    app.export_vault_kdf = light_kdf();
    app.selection.select_only("fantasma");
    let mut harness = harness(app);
    harness.run_steps(2);

    harness.state_mut().export_vault_chosen(target.display().to_string());
    harness.run_steps(2);
    harness.state_mut().export_vault_pass = "senha-do-cofre-novo".into();
    harness.state_mut().export_vault_confirm = "senha-do-cofre-novo".into();
    harness.get_by_label("Create vault").click();
    wait_for_job(&mut harness);

    let error = harness.state().err.clone().expect("a mensagem do núcleo");
    assert!(error.contains("no longer exists"), "mensagem traduzida da variante: {error}");
    assert!(!target.exists(), "nada é gerado quando a seleção é inválida");
    assert!(harness.state().job_label.is_none(), "o spinner sai depois da recusa");
}

/// Exportar por cima do cofre ABERTO é recusado nos dois exports (cofre novo e claro): nada roda e nada é escrito.
#[test]
fn exporting_over_the_open_vault_is_refused() {
    let (mut app, item_id, _folder) = unlocked_for_vault_export("export-over", Prefs::new("en", ThemeMode::Dark));
    app.export_vault_kdf = light_kdf();
    let open_path = app.path.clone();
    let mut harness = harness(app);
    harness.run_steps(2);

    // Cofre novo a partir da seleção: o caminho do cofre aberto no "Salvar como" não vira job.
    harness.state_mut().selection.select_only(&item_id);
    harness.state_mut().export_vault_chosen(open_path.clone());
    harness.run_steps(2);
    harness.state_mut().export_vault_pass = "senha".into();
    harness.state_mut().export_vault_confirm = "senha".into();
    click_labelled_button(&mut harness, "Create vault");
    assert_eq!(harness.state().export_vault_err.as_deref(), Some("Do not save over the open vault; choose another file."));
    assert!(harness.state().job_label.is_none(), "nada roda quando o destino é o cofre aberto");
    assert!(harness.state().export_vault_open, "o diálogo fica aberto para escolher outro arquivo");
    harness.state_mut().export_vault_open = false;

    // Export em claro: o mesmo caminho também é recusado.
    harness.state_mut().export_open = true;
    harness.state_mut().export_path = open_path;
    harness.run_steps(2);
    click_labelled_button(&mut harness, "Export");
    let err = harness.state().err.clone().expect("mensagem do export em claro");
    assert!(err.contains("Do not save over the open vault"), "{err}");
    assert!(harness.state().job_label.is_none(), "nada roda quando o destino é o cofre aberto");
}

// ── unidade 7: juntar outro cofre ──────────────────────────────────────

/// Um cofre com a pasta "Work" (itens dentro) + um `outro.tkeys` sintético: um item igual, um em colisão (mesmo id,
/// conteúdo diferente), dois novos e uma pasta com o mesmo nome ("Work", id diferente) e outra nova ("Nova").
/// Devolve também o JSON do outro cofre (sem o selo) para um teste montar outra variante dele.
fn unlocked_for_merge(name: &str, prefs: Prefs) -> (KeysApp, std::path::PathBuf, serde_json::Value) {
    let (app, _folder_id) = unlocked_in_folder(name, "Work", prefs);
    let dir = scratch(name);
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    let target = dir.join("outro.tkeys");
    let base = app.raw.clone().expect("cofre aberto");
    let items = base["items"].as_array().expect("itens");
    let equal = items.iter().find(|item| item["name"] == "1lib.sk").expect("item igual").clone();
    let mut collision = items.iter().find(|item| item["name"] == "192.168.1.34").expect("item da colisão").clone();
    collision["name"] = serde_json::json!("192.168.1.34 (outro)");
    let note = |id: &str, name: &str, folder: &str| {
        serde_json::json!({
            "id": id, "kind": "note", "name": name, "favorite": false, "folderId": folder,
            "notes": "", "createdAt": 1, "updatedAt": 2, "deletedAt": null
        })
    };
    let other = serde_json::json!({
        "version": 1,
        "folders": [
            {"id": "g1", "name": "Work"},
            {"id": "g2", "name": "Nova"}
        ],
        "items": [equal, collision, note("novo-work", "Novo no Work", "g1"), note("novo-nova", "Novo na Nova", "g2")]
    });
    let bytes = serde_json::to_vec(&other).expect("json do outro");
    let file = app.session.as_ref().expect("sessão viva").seal(&bytes).expect("sela o outro");
    std::fs::write(&target, &file).expect("grava o outro");
    (app, target, other)
}

/// Escreve um `outro.tkeys` com a senha PRÓPRIA (salt diferente): o cofre aberto não o abre pela chave da sessão.
fn write_other_with_password(target: &std::path::Path, password: &str) {
    write_other_with_password_and(target, password, &serde_json::json!({"version": 1, "folders": [], "items": []}));
}

/// O mesmo, com o conteúdo do outro cofre escolhido pelo teste.
fn write_other_with_password_and(target: &std::path::Path, password: &str, other: &serde_json::Value) {
    let bytes = serde_json::to_vec(other).expect("json do outro");
    let light = crate::crypto::KdfParams { m_cost: 8, t_cost: 1, p_cost: 1 };
    let (file, _session) = crate::crypto::create_vault_with(password, &bytes, light).expect("sela o outro");
    std::fs::write(target, &file).expect("grava o outro");
}

fn merge_chosen(harness: &mut Harness<'_, KeysApp>, path: &std::path::Path) {
    let ctx = harness.ctx.clone();
    harness.state_mut().merge_other_chosen(&ctx, &path.display().to_string());
}

/// Fluxo completo: o resumo mostra as contagens certas, confirmar grava UMA vez o cofre aberto e o outro arquivo
/// fica byte a byte igual.
#[test]
fn merging_another_vault_shows_the_summary_and_writes_once() {
    let (mut app, other_path, _other) = unlocked_for_merge("merge-other", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("merge-other-apply");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let baseline = persist_now(&mut app);
    let other_before = std::fs::read(&other_path).expect("outro em disco");
    let mut harness = harness(app);
    harness.run_steps(2);

    merge_chosen(&mut harness, &other_path);
    wait_for_job(&mut harness);

    let plan = harness.state().merge_plan.clone().expect("o resumo da junção");
    assert_eq!(
        (plan.summary.added, plan.summary.collisions, plan.summary.already_present, plan.summary.folders_added, plan.summary.folders_merged),
        (2, 1, 1, 1, 1)
    );
    harness.get_by_label("2 items added from the other vault");
    harness.get_by_label("1 item kept as a duplicate (same id)");
    harness.get_by_label("1 item was already here");
    harness.get_by_label("1 folder added from the other vault");
    harness.get_by_label("1 folder united with a folder of this vault");

    click_labelled_button(&mut harness, "Merge");

    assert!(harness.state().err.is_none(), "a junção não deu erro: {:?}", harness.state().err);
    assert_eq!(harness.state().persist_count - baseline, 1, "uma gravação só");
    assert!(harness.state().selection.is_empty(), "a seleção é limpa ao aplicar");
    let raw = harness.state().raw.clone().expect("cofre aberto");
    let items = raw["items"].as_array().expect("itens");
    assert_eq!(items.len(), 6, "3 do cofre + 2 novos + a duplicata da colisão");
    let work = raw["folders"].as_array().expect("pastas").iter().find(|folder| folder["name"] == "Work").expect("pasta Work").clone();
    assert_eq!(raw["folders"].as_array().expect("pastas").len(), 2, "Work (unida) + Nova");
    let novo = items.iter().find(|item| item["id"] == "novo-work").expect("o item novo entrou");
    assert_eq!(novo["folderId"], work["id"], "o item da pasta unida repontou para a pasta do cofre aberto");
    assert!(items.iter().any(|item| item["id"] == "novo-nova"));
    assert!(items.iter().any(|item| item["name"] == "192.168.1.34 (outro)" && item["id"] != "novo-work"));
    assert!(items.iter().any(|item| item["name"] == "1lib.sk"));

    assert_eq!(std::fs::read(&other_path).expect("outro em disco"), other_before, "o outro arquivo fica intocado");
}

/// Cancelar no resumo não grava nem muda o cofre; o outro arquivo continua igual.
#[test]
fn cancelling_the_merge_summary_writes_nothing() {
    let (mut app, other_path, _other) = unlocked_for_merge("merge-cancel", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("merge-cancel-write");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let baseline = persist_now(&mut app);
    let before = app.raw.clone().expect("cofre aberto");
    let other_before = std::fs::read(&other_path).expect("outro em disco");
    let mut harness = harness(app);
    harness.run_steps(2);

    merge_chosen(&mut harness, &other_path);
    wait_for_job(&mut harness);
    assert!(harness.state().merge_plan.is_some());
    harness.get_by_label("Cancel").click();
    harness.run_steps(2);

    assert!(harness.state().merge_plan.is_none(), "o resumo fecha");
    assert_eq!(harness.state().persist_count, baseline, "cancelar não grava");
    assert_eq!(harness.state().raw.as_ref(), Some(&before), "o cofre aberto não muda");
    assert_eq!(std::fs::read(&other_path).expect("outro em disco"), other_before);
}

/// O outro cofre com outra senha: sem a senha certa o resumo não abre; a mensagem aparece e nada é gravado.
#[test]
fn merging_the_other_vault_with_a_wrong_password_reports_and_writes_nothing() {
    let (mut app, other_path, _other) = unlocked_for_merge("merge-password", Prefs::new("en", ThemeMode::Dark));
    write_other_with_password(&other_path, "senha-do-outro");
    let dir = scratch("merge-password-write");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let baseline = persist_now(&mut app);
    let mut harness = harness(app);
    harness.run_steps(2);

    merge_chosen(&mut harness, &other_path);
    harness.run_steps(2);
    assert!(harness.state().merge_open, "a chave da sessão não abriu: pede a senha do outro cofre");
    harness.get_by_label("Open the other vault");

    harness.state_mut().merge_password = "senha-errada".into();
    harness.run_steps(2);
    click_labelled_button(&mut harness, "Open");
    wait_for_job(&mut harness);
    assert_eq!(harness.state().merge_error.as_deref(), Some("wrong password or corrupted/tampered file"));
    assert!(harness.state().merge_open, "o diálogo fica aberto para tentar de novo");
    assert!(harness.state().merge_plan.is_none(), "sem resumo");
    assert_eq!(harness.state().persist_count, baseline);

    // Com a senha certa o resumo abre (e nada é gravado até confirmar).
    harness.state_mut().merge_password = "senha-do-outro".into();
    harness.run_steps(2);
    click_labelled_button(&mut harness, "Open");
    wait_for_job(&mut harness);
    assert!(harness.state().merge_plan.is_some(), "a senha certa calcula a junção");
    assert_eq!(harness.state().persist_count, baseline, "nada gravado antes de confirmar");
}

/// Nada a juntar (o outro cofre já está inteiro aqui): o resumo diz isso e o único botão é Fechar.
/// Juntar só as chaves de uma pasta de mesmo nome (sem item nenhum do outro): a pasta conta em `folders_merged`, o
/// resumo mostra a linha (nunca um resumo vazio com "Juntar") e a confirmação grava as chaves.
#[test]
fn a_merge_that_only_copies_folder_keys_shows_the_united_line() {
    let (mut app, other_path, _other) = unlocked_for_merge("merge-keys", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("merge-keys-write");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let baseline = persist_now(&mut app);
    // O outro: só a pasta de MESMO nome ("Work") com uma chave que o aberto não tem; nenhum item.
    let other = serde_json::json!({
        "version": 1,
        "folders": [{"id": "g1", "name": "Work", "extraFolderKey": true}],
        "items": []
    });
    let bytes = serde_json::to_vec(&other).expect("json do outro");
    let file = app.session.as_ref().expect("sessão viva").seal(&bytes).expect("sela o outro");
    std::fs::write(&other_path, &file).expect("grava o outro");
    let mut harness = harness(app);
    harness.run_steps(2);

    merge_chosen(&mut harness, &other_path);
    wait_for_job(&mut harness);

    let plan = harness.state().merge_plan.clone().expect("o resumo");
    assert_eq!(plan.summary.folders_merged, 1, "a pasta com as chaves copiadas conta como unida");
    harness.get_by_label("1 folder united with a folder of this vault");
    assert!(harness.query_by_label("Nothing to merge: this vault already has everything from the other vault.").is_none());

    click_labelled_button(&mut harness, "Merge");
    assert!(harness.state().err.is_none(), "a junção não deu erro: {:?}", harness.state().err);
    assert_eq!(harness.state().persist_count - baseline, 1, "uma gravação só");
    let raw = harness.state().raw.clone().expect("cofre aberto");
    let work = raw["folders"].as_array().expect("pastas").iter().find(|folder| folder["name"] == "Work").expect("pasta Work").clone();
    assert_eq!(work["extraFolderKey"], true, "as chaves da pasta do outro entraram");
}

#[test]
fn merging_a_vault_with_nothing_new_says_nothing_to_merge() {
    let (mut app, other_path, _other) = unlocked_for_merge("merge-nothing", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("merge-nothing-write");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let baseline = persist_now(&mut app);
    // O outro cofre é o próprio cofre aberto: tudo já está aqui.
    let bytes = serde_json::to_vec(app.raw.as_ref().expect("cofre aberto")).expect("json");
    let file = app.session.as_ref().expect("sessão viva").seal(&bytes).expect("sela");
    std::fs::write(&other_path, &file).expect("grava o outro");
    let mut harness = harness(app);
    harness.run_steps(2);

    merge_chosen(&mut harness, &other_path);
    wait_for_job(&mut harness);

    assert!(harness.state().merge_plan.is_some());
    harness.get_by_label("Nothing to merge: this vault already has everything from the other vault.");
    assert!(harness.query_by_label("Merge").is_none(), "nada a juntar: sem botão de confirmar");
    harness.get_by_label("Close").click();
    harness.run_steps(2);
    assert!(harness.state().merge_plan.is_none());
    assert_eq!(harness.state().persist_count, baseline, "fechar não grava");
}

/// Juntar de novo o mesmo outro cofre (com um item novo): a duplicata que entrou na primeira passada agora conta em
/// "já estava com outro id" (linha própria do resumo) e o resto continua dizendo "já estava aqui".
#[test]
fn merging_again_reports_content_already_present() {
    let (mut app, other_path, mut other) = unlocked_for_merge("merge-again", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("merge-again-write");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let mut harness = harness(app);
    harness.run_steps(2);

    merge_chosen(&mut harness, &other_path);
    wait_for_job(&mut harness);
    click_labelled_button(&mut harness, "Merge");
    assert!(harness.state().err.is_none(), "a primeira junção não deu erro: {:?}", harness.state().err);
    harness.run_steps(2);

    // Outra variante do outro cofre: a colisão continua lá e um item novo chega.
    other["items"].as_array_mut().expect("itens do outro").push(serde_json::json!({
        "id": "novo-extra", "kind": "note", "name": "Extra", "favorite": false,
        "notes": "", "createdAt": 1, "updatedAt": 2, "deletedAt": null
    }));
    let bytes = serde_json::to_vec(&other).expect("json do outro");
    let file = harness.state().session.as_ref().expect("sessão viva").seal(&bytes).expect("sela o outro");
    std::fs::write(&other_path, &file).expect("grava o outro");

    merge_chosen(&mut harness, &other_path);
    wait_for_job(&mut harness);

    let plan = harness.state().merge_plan.clone().expect("o resumo da segunda junção");
    assert_eq!(
        (plan.summary.added, plan.summary.collisions, plan.summary.already_present, plan.summary.content_already_present),
        (1, 0, 3, 1),
        "o que veio novo entra; o que já está sob outro id não entra de novo"
    );
    harness.get_by_label("1 item added from the other vault");
    harness.get_by_label("3 items were already here");
    harness.get_by_label("1 item was already here under another id");
    click_labelled_button(&mut harness, "Cancel");
    harness.run_steps(2);
    assert!(harness.state().merge_plan.is_none(), "cancelar fecha o resumo");
}

/// O caminho alternativo do cofre em uso (`sub/../cofre.tkeys`, `./cofre.tkeys`, caminho relativo) é o mesmo arquivo:
/// o `same_path` resolve `.`/`..` mesmo sem o arquivo existir e o texto idêntico deixa de ser o único caso barrado.
#[test]
fn same_path_resolves_dot_components_without_touching_the_disk() {
    let base = std::env::temp_dir().join("localkeys-same-path");
    let file = base.join("cofre.tkeys");
    let via_parent = base.join("sub").join("..").join("cofre.tkeys");

    assert!(crate::same_path(&file.display().to_string(), &via_parent.display().to_string()), "sub/.. volta ao mesmo arquivo");
    let dot_relative = format!(".{sep}cofre.tkeys", sep = std::path::MAIN_SEPARATOR);
    assert!(crate::same_path("cofre.tkeys", &dot_relative), "relativo e ./relativo");
    #[cfg(windows)]
    assert!(
        crate::same_path(&file.display().to_string(), &file.display().to_string().to_uppercase()),
        "no Windows a caixa não separa dois caminhos do mesmo arquivo"
    );
    assert!(!crate::same_path(&file.display().to_string(), &base.join("outro.tkeys").display().to_string()));
    assert!(!crate::same_path("", &file.display().to_string()), "caminho vazio não é o mesmo arquivo que nada");
}

/// A sincronização barra o cofre em uso mesmo escrito de outro jeito (`dir/./a.tkeys`), não só com o texto idêntico.
#[test]
fn sync_refuses_the_vault_in_use_written_another_way() {
    let mut app = unlocked("sync-same-path", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("sync-same-path");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("a.tkeys").display().to_string();
    let raw = app.raw.clone().expect("cofre");
    app.persist(raw).then_some(()).expect("grava");

    app.sync_with_other(&format!("{}/./a.tkeys", dir.display()));

    assert!(app.err.as_deref().is_some_and(|e| e.contains("vault in use")), "{:?}", app.err);
    assert!(!app.sync_open, "nada de resumo para o próprio arquivo");
}

/// O resultado de um job que já começa o PRÓXIMO (fila de anexos) não pode apagar o rótulo do job novo nem dizer que
/// a operação terminou sem resultado.
#[test]
fn a_job_that_starts_the_next_attachment_keeps_its_label() {
    let mut app = unlocked("queue-label", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("queue-label");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    let raw = app.raw.clone().expect("cofre");
    app.persist(raw).then_some(()).expect("grava");
    let second = dir.join("segundo.txt");
    std::fs::write(&second, b"b").expect("grava o segundo anexo");
    let id = item_id(&app, "1lib.sk");

    // O canal entrega o primeiro resultado e FECHA (a thread do primeiro job acabou).
    let (tx, rx) = std::sync::mpsc::channel();
    tx.send(crate::jobs::Sent {
        generation: 5,
        result: crate::jobs::JobResult::AttachmentRead(Ok(crate::jobs::AttachmentRead {
            item_id: id.clone(),
            name: "primeiro.txt".into(),
            mime: "text/plain",
            size: 1,
            data_b64: "YQ==".into(),
        })),
    })
    .expect("manda o primeiro resultado");
    drop(tx);
    app.job_generation = 5;
    app.job_rx = Some(rx);
    app.attach_queue = vec![(id, second.display().to_string())];

    let ctx = egui::Context::default();
    app.poll_jobs(&ctx);

    assert_eq!(app.job_label, Some(crate::i18n::tr("reading file…")), "o rótulo do segundo job sobrevive");
    assert!(app.err.is_none(), "sem 'the operation ended without a result': {:?}", app.err);

    // Deixa o segundo job terminar (thread real) para não deixar trabalho pendurado.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
    while app.job_label.is_some() {
        assert!(std::time::Instant::now() < deadline, "o segundo job não terminou no prazo");
        std::thread::sleep(std::time::Duration::from_millis(20));
        app.poll_jobs(&ctx);
    }
}

/// Falha de gravação ao confirmar a junção: o cofre em memória continua o de antes (o plano não é adotado) e o plano
/// não fica pendurado para um segundo clique. O caminho do outro cofre (o do diálogo da senha) sai junto.
#[test]
fn a_failed_merge_persist_keeps_the_old_vault() {
    let (mut app, other_path, _other) = unlocked_for_merge("merge-persist-fail", Prefs::new("en", ThemeMode::Dark));
    write_other_with_password_and(
        &other_path,
        "senha-do-outro",
        &serde_json::json!({
            "version": 1, "folders": [], "items": [{
                "id": "outro-item", "kind": "note", "name": "Do outro", "favorite": false, "notes": "",
                "createdAt": 1, "updatedAt": 2, "deletedAt": null
            }]
        }),
    );
    let dir = scratch("merge-persist-fail-write");
    // A pasta não existe: a gravação atômica falha.
    app.path = dir.join("sem-pasta/cofre.tkeys").display().to_string();
    let before = app.raw.clone().expect("cofre aberto");
    let mut harness = harness(app);
    harness.run_steps(2);

    merge_chosen(&mut harness, &other_path);
    harness.run_steps(2);
    assert!(harness.state().merge_open, "a chave da sessão não abre o outro: pede a senha");
    harness.state_mut().merge_password = "senha-do-outro".into();
    harness.run_steps(2);
    click_labelled_button(&mut harness, "Open");
    wait_for_job(&mut harness);
    click_labelled_button(&mut harness, "Merge");

    assert!(harness.state().err.is_some(), "a falha da gravação aparece");
    assert_eq!(harness.state().raw.as_ref(), Some(&before), "a memória continua com o cofre antigo");
    assert!(harness.state().merge_plan.is_none(), "o plano não fica pendurado");
    assert!(harness.state().merge_path.is_empty(), "o caminho do outro cofre sai junto");
}

/// Trancar com o diálogo da senha aberto: o estado da junção sai todo (senha, caminho, plano).
#[test]
fn locking_with_the_merge_password_open_clears_it() {
    let (app, other_path, _other) = unlocked_for_merge("merge-lock-password", Prefs::new("en", ThemeMode::Dark));
    write_other_with_password(&other_path, "senha-do-outro");
    let mut harness = harness(app);
    harness.run_steps(2);

    merge_chosen(&mut harness, &other_path);
    harness.run_steps(2);
    assert!(harness.state().merge_open);
    harness.state_mut().merge_password = "digitada".into();

    harness.state_mut().lock();
    harness.run_steps(2);

    assert!(!harness.state().merge_open, "o diálogo fecha");
    assert!(harness.state().merge_password.is_empty(), "a senha não sobrevive ao trancar");
    assert!(harness.state().merge_path.is_empty(), "o caminho do outro cofre sai");
    assert!(harness.state().merge_plan.is_none());
    assert!(harness.state().raw.is_none());
}

/// Trancar com o resumo aberto: o plano (o cofre juntado) não sobrevive.
#[test]
fn locking_with_the_merge_summary_open_clears_it() {
    let (mut app, other_path, _other) = unlocked_for_merge("merge-lock-summary", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("merge-lock-summary-write");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    persist_now(&mut app);
    let mut harness = harness(app);
    harness.run_steps(2);

    merge_chosen(&mut harness, &other_path);
    wait_for_job(&mut harness);
    assert!(harness.state().merge_plan.is_some());

    harness.state_mut().lock();
    harness.run_steps(2);

    assert!(harness.state().merge_plan.is_none(), "o resumo não sobrevive ao trancar");
    assert!(harness.state().merge_path.is_empty());
    assert!(harness.state().raw.is_none());
}

/// Juntar o cofre ABERTO por outro jeito de escrever o caminho (`dir/./cofre.tkeys`) é recusado antes de ler.
#[test]
fn merging_the_vault_in_use_by_another_path_is_refused() {
    let (mut app, _other_path, _other) = unlocked_for_merge("merge-same-path", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("merge-same-path-write");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    persist_now(&mut app);
    let mut harness = harness(app);
    harness.run_steps(2);

    let ctx = harness.ctx.clone();
    harness.state_mut().merge_other_chosen(&ctx, &format!("{}/./cofre.tkeys", dir.display()));
    harness.run_steps(2);

    let err = harness.state().err.clone().expect("a recusa em claro");
    assert!(err.contains("the other vault is the vault in use"), "{err}");
    assert!(harness.state().job_label.is_none(), "nada roda quando o outro é o próprio cofre");
    assert!(harness.state().merge_plan.is_none());
}

/// Cancelar o diálogo da senha fecha na hora; o resultado do job que já estava lendo o outro cofre (rede fora do ar
/// pode levar minutos) chega com a geração antiga e é descartado — não reabre o diálogo nem abre o resumo.
#[test]
fn a_late_result_after_cancelling_the_password_is_ignored() {
    let (mut app, other_path, _other) = unlocked_for_merge("merge-cancel-late", Prefs::new("en", ThemeMode::Dark));
    app.merge_open = true;
    app.merge_path = other_path.display().to_string();
    app.job_label = Some(crate::i18n::tr("opening the other vault…"));
    app.job_generation = 7;
    let (tx, rx) = std::sync::mpsc::channel();
    app.job_rx = Some(rx);
    let mut harness = harness(app);
    harness.run_steps(2);

    // Cancelar com o job rodando: o diálogo fecha (o botão e o Esc continuam valendo) e a geração muda.
    click_labelled_button(&mut harness, "Cancel");
    assert!(!harness.state().merge_open, "o diálogo fecha com o job rodando");
    assert_eq!(harness.state().job_generation, 8);
    assert!(harness.state().job_label.is_none());

    // O job velho responde depois: geração 7 ≠ atual → ignorado.
    tx.send(crate::jobs::Sent {
        generation: 7,
        result: crate::jobs::JobResult::MergeOtherOpened(Ok(crate::jobs::OpenedOther {
            path: other_path.display().to_string(),
            raw: serde_json::json!({"version": 1, "folders": [], "items": []}),
        })),
    })
    .expect("manda o resultado tardio");
    drop(tx);
    harness.run_steps(2);

    assert!(!harness.state().merge_open, "o resultado velho não reabre o diálogo");
    assert!(harness.state().merge_plan.is_none(), "o resultado velho não abre o resumo");
    assert!(harness.state().job_label.is_none(), "o resultado velho não dispara outro job");
    assert!(harness.state().err.is_none(), "o resultado velho não vira erro");
}

// ── unidade 8: anexos grandes ─────────────────────────────────────────

/// O número de anexos VIVOS do item.
fn live_attachments(app: &KeysApp, id: &str) -> usize {
    item_by_id(app, id)["attachments"].as_array().map_or(0, |list| list.iter().filter(|a| a["deletedAt"].is_null()).count())
}

/// O aviso de tamanho vem DEPOIS da leitura (o cap de 10 MiB já atuou): o anexo fica retido no modal, Cancelar
/// descarta o lido e a fila, Continuar aplica; e o tamanho guardado passa a ser o do arquivo salvo, então o anexo
/// que já está no cofre não conta duas vezes.
#[test]
fn the_size_warning_comes_after_the_read_and_holds_the_attachment() {
    let mut app = unlocked("attach-warn", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("attach-warn");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    persist_now(&mut app);
    // O cofre "vale" o teto (o número guardado é o que o aviso usa; o arquivo real não é relido).
    app.vault_bytes = crate::size_limit::VAULT_WARN_BYTES;
    let id = item_id(&app, "1lib.sk");
    let first = dir.join("primeiro.txt");
    std::fs::write(&first, b"x").expect("grava o anexo");
    let second = dir.join("segundo.txt");
    std::fs::write(&second, b"y").expect("grava o segundo anexo");
    let mut harness = harness(app);
    harness.run_steps(2);
    let ctx = harness.ctx.clone();

    // Cancelar: o arquivo foi lido, mas nada é guardado e a fila é descartada junto.
    harness.state_mut().attachments_chosen(&id, vec![first.display().to_string(), second.display().to_string()], &ctx);
    wait_for_job(&mut harness);
    assert!(harness.state().attach_warn.is_some(), "acima do teto, o aviso segura o anexo lido");
    harness.get_by_label_contains("Large vault");
    assert_eq!(live_attachments(harness.state(), &id), 0, "nada guardado antes do Continuar");
    click_labelled_button(&mut harness, "Cancel");
    assert!(harness.state().attach_warn.is_none(), "o aviso fecha");
    assert!(harness.state().attach_queue.is_empty(), "cancelar descarta a fila da seleção");
    assert_eq!(live_attachments(harness.state(), &id), 0, "cancelar não anexa");

    // Continuar: aplica o anexo retido e grava; o tamanho guardado volta a ser o do arquivo de verdade.
    harness.state_mut().attachments_chosen(&id, vec![first.display().to_string()], &ctx);
    wait_for_job(&mut harness);
    assert!(harness.state().attach_warn.is_some());
    click_labelled_button(&mut harness, "Continue");
    assert!(harness.state().attach_warn.is_none());
    assert_eq!(live_attachments(harness.state(), &id), 1, "continuar anexa");
    let saved = std::fs::metadata(harness.state().path.trim()).expect("arquivo gravado").len();
    assert_eq!(harness.state().vault_bytes, saved, "o tamanho guardado acompanha a gravação");

    // Com o tamanho real (pequeno), outro anexo entra direto: o que já está no cofre não é contado em dobro.
    harness.state_mut().attachments_chosen(&id, vec![second.display().to_string()], &ctx);
    wait_for_job(&mut harness);
    assert!(harness.state().attach_warn.is_none(), "o anexo salvo está no tamanho do arquivo");
    assert_eq!(live_attachments(harness.state(), &id), 2);
}

/// No formulário, os anexos ainda não gravados entram na conta: o segundo só estoura o teto por causa do primeiro.
/// Depois de salvar, eles passam a estar no arquivo (e não são contados de novo).
#[test]
fn the_size_warning_counts_the_form_pending_attachments_once() {
    let mut app = unlocked("attach-warn-form", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("attach-warn-form");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    persist_now(&mut app);
    // Um anexo de 3 bytes ocupa 4 em base64: WARN-7 + 4 ainda cabe; o segundo estoura por causa do pendente.
    app.vault_bytes = crate::size_limit::VAULT_WARN_BYTES - 7;
    let small = dir.join("nota.txt");
    std::fs::write(&small, b"abc").expect("grava o anexo");
    let mut harness = harness(app);
    harness.run_steps(2);
    let ctx = harness.ctx.clone();
    harness.state_mut().open_add_form(NewKind::Login);

    harness.state_mut().attachments_chosen(crate::FORM_ATTACH, vec![small.display().to_string()], &ctx);
    wait_for_job(&mut harness);
    assert!(harness.state().attach_warn.is_none(), "o primeiro ainda cabe");
    assert_eq!(harness.state().form_attachments.len(), 1);

    harness.state_mut().attachments_chosen(crate::FORM_ATTACH, vec![small.display().to_string()], &ctx);
    wait_for_job(&mut harness);
    assert!(harness.state().attach_warn.is_some(), "o pendente do formulário conta: o segundo passa do teto");
    assert_eq!(harness.state().form_attachments.len(), 1, "nada guardado antes do Continuar");
    click_labelled_button(&mut harness, "Continue");
    assert_eq!(harness.state().form_attachments.len(), 2);

    // Salvar: os pendentes viram anexos do item e o tamanho guardado passa a ser o do arquivo.
    harness.state_mut().add_name = "Item com anexos".into();
    harness.state_mut().save_form();
    harness.run_steps(2);
    assert!(harness.state().form_attachments.is_empty(), "os pendentes foram para o item");
    let saved = std::fs::metadata(harness.state().path.trim()).expect("arquivo gravado").len();
    assert_eq!(harness.state().vault_bytes, saved);

    // Anexo novo no item salvo: o que já está no arquivo não conta de novo (sem aviso).
    let saved_id = item_id(harness.state(), "Item com anexos");
    harness.state_mut().attachments_chosen(&saved_id, vec![small.display().to_string()], &ctx);
    wait_for_job(&mut harness);
    assert!(harness.state().attach_warn.is_none(), "os anexos salvos estão no tamanho do arquivo");
    assert_eq!(live_attachments(harness.state(), &saved_id), 3);
}

/// Trancar com uma leitura de anexo em andamento: o resultado que chega depois é da geração antiga e não cai no
/// formulário (que já foi limpo).
#[test]
fn locking_discards_a_running_attachment_and_the_late_result_is_ignored() {
    let mut app = unlocked("lock-job", Prefs::new("en", ThemeMode::Dark));
    app.job_generation = 4;
    app.job_label = Some(crate::i18n::tr("reading file…"));
    let (tx, rx) = std::sync::mpsc::channel();
    app.job_rx = Some(rx);

    app.lock();

    assert!(app.job_label.is_none(), "o spinner do job não sobrevive ao trancar");
    assert_eq!(app.job_generation, 5, "a geração muda ao trancar");
    tx.send(crate::jobs::Sent {
        generation: 4,
        result: crate::jobs::JobResult::AttachmentRead(Ok(crate::jobs::AttachmentRead {
            item_id: crate::FORM_ATTACH.into(),
            name: "nota.txt".into(),
            mime: "text/plain",
            size: 1,
            data_b64: "YQ==".into(),
        })),
    })
    .expect("manda o resultado tardio");
    drop(tx);
    app.poll_jobs(&egui::Context::default());

    assert!(app.form_attachments.is_empty(), "a leitura velha não cai no formulário depois de trancar");
    assert!(app.err.is_none(), "o resultado velho não vira erro");
}

/// O cap por anexo (10 MiB) atua na leitura, antes do aviso: um arquivo de 200 MB nunca chega ao "Large vault".
#[test]
fn an_attachment_over_the_cap_is_refused_before_the_size_warning() {
    let mut app = unlocked("attach-cap", Prefs::new("en", ThemeMode::Dark));
    let dir = scratch("attach-cap");
    std::fs::create_dir_all(&dir).expect("pasta do teste");
    app.path = dir.join("cofre.tkeys").display().to_string();
    persist_now(&mut app);
    app.vault_bytes = crate::size_limit::VAULT_WARN_BYTES;
    let big = dir.join("grande.bin");
    std::fs::write(&big, vec![7u8; crate::jobs::MAX_ATTACHMENT_BYTES as usize + 1]).expect("grava o anexo grande");
    let id = item_id(&app, "1lib.sk");
    let mut harness = harness(app);
    harness.run_steps(2);
    let ctx = harness.ctx.clone();

    harness.state_mut().attachments_chosen(&id, vec![big.display().to_string()], &ctx);
    wait_for_job(&mut harness);

    assert!(harness.state().attach_warn.is_none(), "o cap de 10 MiB recusa antes do aviso de tamanho");
    let err = harness.state().err.clone().expect("o arquivo grande é recusado");
    assert!(err.contains("too large"), "{err}");
    assert_eq!(live_attachments(harness.state(), &id), 0);
}
