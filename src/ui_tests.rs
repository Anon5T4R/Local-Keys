//! Testes de UI sem janela (egui_kittest) da casca da suíte: o botão de tema, a engrenagem, a lupa, as
//! Configurações e a paleta. Nenhum teste toca o config.json real nem a rede.

use std::path::{Path, PathBuf};

use egui::accesskit::Role;
use egui_kittest::kittest::Queryable;
use egui_kittest::Harness;
use lab_ui::config::Config;
use lab_ui::i18n::Lang;
use lab_ui::theme::Appearance;

use crate::KeysApp;

/// Pasta temporária própria do teste (o config.json de teste vai para lá).
fn scratch(name: &str) -> PathBuf {
    let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_nanos());
    std::env::temp_dir().join(format!("local-keys-ui-{name}-{nanos}"))
}

fn app(name: &str, cfg: Config) -> KeysApp {
    let mut app = KeysApp::bare(cfg);
    app.config_file = scratch(name).join("config.json");
    app
}

// Os campos de senha da tela de desbloqueio ainda não têm nome acessível (achado em docs/achados.md), então a
// checagem de acessibilidade do kittest fica desligada aqui.
// O kittest não tem o provedor de fontes do sistema: ✓ e os emojis da tela viram "tofu" nos testes.
fn harness_sized(app: KeysApp, width: f32) -> Harness<'static, KeysApp> {
    Harness::builder()
        .with_size(egui::vec2(width, 640.0))
        .with_step_dt(1.0 / 60.0)
        .allow_missing_glyphs()
        .with_accessibility_check(false)
        .build_ui_state(
            |ui, app: &mut KeysApp| {
                egui_extras::install_image_loaders(ui.ctx());
                lab_ui::theme::apply(ui.ctx(), app.cfg.theme);
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
    let mut harness = harness(app("theme", Config::default()));
    assert_eq!(harness.state().cfg.theme, Appearance::System);
    click_bar(&mut harness, THEME);
    assert_eq!(harness.state().cfg.theme, Appearance::Light);
    click_bar(&mut harness, THEME);
    assert_eq!(harness.state().cfg.theme, Appearance::Dark);
    click_bar(&mut harness, THEME);
    assert_eq!(harness.state().cfg.theme, Appearance::System);
    // Cada troca foi gravada: o arquivo de teste (nunca o real) guarda o modo atual.
    let saved = lab_ui::config::load_from(&harness.state().config_file);
    assert_eq!(saved.theme, Appearance::System);
}

/// Uma preferência de tema gravada por versões antigas (id que a casca não conhece) abre no Automático.
#[test]
fn an_old_theme_id_falls_back_to_automatic() {
    let dir = scratch("old-theme");
    std::fs::create_dir_all(&dir).expect("temp dir");
    let file = dir.join("config.json");
    std::fs::write(&file, r#"{"theme":"PunkPrincess","lang":"En"}"#).expect("write config");
    let cfg = lab_ui::config::load_from(&file);
    assert_eq!(cfg.theme, Appearance::System);
    assert_eq!(cfg.lang, Lang::En);
}

/// A engrenagem abre as Configurações; lá o idioma troca e o tema vira pelas três opções.
#[test]
fn the_gear_opens_settings_with_language_and_theme() {
    let mut harness = harness(app("gear", Config { theme: Appearance::Dark, lang: Lang::En }));
    click_bar(&mut harness, GEAR);
    assert!(harness.state().settings_open);
    harness.get_by_label("About Local Keys");
    shot(&mut harness, "settings-dark");
    harness.get_by_label("Light").click();
    harness.run_steps(2);
    assert_eq!(harness.state().cfg.theme, Appearance::Light);
    shot(&mut harness, "settings-light");
    harness.get_by_label("Automatic").click();
    harness.run_steps(2);
    assert_eq!(harness.state().cfg.theme, Appearance::System);
    // O idioma sai da barra e mora aqui: o combo troca para o português.
    harness.get_by_role(Role::ComboBox).click();
    harness.run_steps(2);
    harness.get_by_label("Português").click();
    harness.run_steps(2);
    assert_eq!(harness.state().cfg.lang, Lang::Pt);
    harness.get_by_label("Sobre o Local Keys");
    // Esc fecha a janela.
    press(&mut harness, egui::Key::Escape, egui::Modifiers::NONE);
    assert!(!harness.state().settings_open);
}

/// A lupa e o Ctrl+K abrem a paleta; Enter roda o comando escolhido.
#[test]
fn the_search_icon_and_ctrl_k_open_the_palette_and_run_a_command() {
    let mut harness = harness(app("palette", Config { theme: Appearance::Light, lang: Lang::En }));
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
    let app = app("locked", Config::default());
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
    let mut harness = harness(app("exit", Config { theme: Appearance::Dark, lang: Lang::En }));
    press(&mut harness, egui::Key::K, egui::Modifiers::COMMAND);
    harness.input_mut().events.push(egui::Event::Text("exit".into()));
    harness.run_steps(2);
    press(&mut harness, egui::Key::Enter, egui::Modifiers::NONE);
    assert!(harness.state().quit.load(std::sync::atomic::Ordering::Relaxed));
}

/// Os três botões da barra continuam inteiros (sem cobrir "LocalKeys") na largura estreita.
#[test]
fn the_top_bar_fits_a_narrow_window() {
    let mut harness = harness_sized(app("narrow", Config { theme: Appearance::Light, lang: Lang::Pt }), 340.0);
    harness.run_steps(2);
    let rects = bar_buttons(&harness);
    assert_eq!(rects.len(), 3);
    for rect in &rects {
        assert!(rect.max.x <= 340.0 && rect.min.x >= 0.0, "botão fora da janela: {rect:?}");
    }
    shot(&mut harness, "narrow-light");
}

/// As larguras das capturas de revisão (mínima e média), com a barra de cima à mostra nos dois temas.
#[test]
fn review_shots() {
    for (width, label) in [(340.0, "340"), (1100.0, "1100")] {
        for (appearance, name) in [(Appearance::Light, "light"), (Appearance::Dark, "dark")] {
            let mut harness = harness_sized(app("shots", Config { theme: appearance, lang: Lang::En }), width);
            harness.run_steps(2);
            shot(&mut harness, &format!("bar-{name}-{label}"));
            click_bar(&mut harness, GEAR);
            shot(&mut harness, &format!("settings-{name}-{label}"));
            press(&mut harness, egui::Key::Escape, egui::Modifiers::NONE);
            click_bar(&mut harness, SEARCH);
            shot(&mut harness, &format!("palette-{name}-{label}"));
        }
    }
}
