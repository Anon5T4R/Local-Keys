//! Testes de UI sem janela (egui_kittest) da casca da suíte: o botão de tema, a engrenagem, a lupa, as
//! Configurações e a paleta. Nenhum teste toca o config.json real nem a rede.

use std::path::{Path, PathBuf};

use craft_icons::ThemeMode;
use egui::accesskit::Role;
use egui_kittest::Harness;
use egui_kittest::kittest::{NodeT, Queryable};

use crate::prefs::Prefs;

use crate::KeysApp;

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

// O kittest falha se algum texto da tela cair numa fonte sem o glyph (a "caixinha"): é o guarda dos emojis.
fn harness_sized(app: KeysApp, width: f32) -> Harness<'static, KeysApp> {
    Harness::builder().with_size(egui::vec2(width, 640.0)).with_step_dt(1.0 / 60.0).build_ui_state(
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

/// Os três botões da barra continuam inteiros (sem cobrir "LocalKeys") na largura estreita.
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
    harness.get_by_label("Change master password…").click();
    harness.run_steps(2);
    assert!(harness.state().change_open);
}

/// Todo diálogo do cofre cabe na janela do teste (340×640, menor que a inicial de 480×660): nenhum
/// controle passa da borda em nenhum dos dois eixos. Hoje nenhum passa de 640 de altura, então nenhum
/// precisa de rolagem.
#[test]
fn every_dialog_fits_the_narrow_window() {
    type Open = fn(&mut KeysApp);
    let dialogs: [(&str, Open); 8] = [
        ("add", |app| app.open_add_form()),
        ("generator", |app| app.gen_open = true),
        ("change", |app| app.change_open = true),
        ("import", |app| app.import_open = true),
        ("export", |app| app.export_open = true),
        ("folders", |app| app.folders_open = true),
        ("new-folder", |app| app.folder_new_open = true),
        ("trash", |app| app.trash_open = true),
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
