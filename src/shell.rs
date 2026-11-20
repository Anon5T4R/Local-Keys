//! A casca da suíte no Keys: os botões da barra de cima (engrenagem, tema, lupa), a janela de Configurações
//! (idioma, tema e Sobre) e a paleta de comandos. Nada aqui desenha ou mostra segredo: com o cofre trancado a
//! paleta só oferece destrancar, configurações, atualizações, sobre e sair.

use std::sync::atomic::Ordering;

use craft_icons::{Icon, ThemeMode};
use craft_palette::Command;
use craft_update::updater::Status;
use egui::{Key, KeyboardShortcut, Modifiers};

use crate::i18n::{self, tr, tr_fmt};
use crate::{KeysApp, begin_dialog, modal_frame, theme};

fn shortcut_text(ctx: &egui::Context) -> String {
    ctx.format_shortcut(&KeyboardShortcut::new(Modifiers::COMMAND, Key::K))
}

impl KeysApp {
    /// Grava o config.json (idioma e tema).
    fn save_prefs(&self) {
        let _ = self.prefs.save_to(&self.config_file);
    }

    /// Troca o modo de tema: aplica e grava.
    pub(crate) fn set_mode(&mut self, ctx: &egui::Context, mode: ThemeMode) {
        self.prefs.theme = mode.id().to_string();
        theme::apply(ctx, mode);
        self.save_prefs();
    }

    /// Troca o idioma (`auto` ou um código de `i18n::LANGUAGES`): vale a partir deste quadro e é gravado.
    fn set_language(&mut self, language: &str) {
        self.prefs.language = language.to_string();
        i18n::set_language(language);
        self.save_prefs();
    }

    /// Os controles da suíte à direita da barra de cima, num layout da direita para a esquerda: engrenagem
    /// (mais à direita), tema e lupa.
    pub(crate) fn shell_buttons(&mut self, ui: &mut egui::Ui) {
        // Longe da borda da janela, e um dedo de distância entre os botões.
        ui.add_space(8.0);
        if craft_icons::ui::button(ui, Icon::Settings, tr("Settings")).clicked() {
            self.settings_open = true;
        }
        ui.add_space(6.0);
        let mut mode = self.prefs.mode();
        let tooltip = tr_fmt("Theme: {mode}", &[("mode", tr(mode.label()))]);
        if craft_icons::ui::theme_button(ui, &mut mode, &tooltip) {
            self.set_mode(ui.ctx(), mode);
        }
        ui.add_space(6.0);
        let search = tr_fmt("Search commands ({shortcut})", &[("shortcut", &shortcut_text(ui.ctx()))]);
        if craft_icons::ui::button(ui, Icon::Search, &search).clicked() {
            self.palette.open();
        }
    }

    /// Configurações: idioma, tema, esquecer a chave rápida e a seção Sobre (versão, repositório, atualizações).
    pub(crate) fn settings_modal(&mut self, ctx: &egui::Context) {
        if !self.settings_open {
            return;
        }
        let mut close = false;
        let mut forget = false;
        let mut check = false;
        let language = self.prefs.language.clone();
        let mut new_language = language.clone();
        let mut mode = self.prefs.mode();
        let status = self.updater.status().clone();
        let quick = self.quick_ativa;
        let resp = egui::Modal::new(egui::Id::unique("settings")).frame(modal_frame(ctx)).show(ctx, |ui| {
            begin_dialog(ui);
            // Cabe na janela estreita: a moldura do diálogo come 40 px e a borda 16.
            ui.set_width(320.0_f32.min(ctx.viewport_rect().width() - 56.0));
            ui.horizontal(|ui| {
                ui.heading(tr("Settings"));
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if craft_icons::ui::close_button(ui, tr("Close")).clicked() {
                        close = true;
                    }
                });
            });
            ui.add_space(8.0);
            egui::Grid::new("settings-grid").num_columns(2).spacing([12.0, 8.0]).show(ui, |ui| {
                // "Automático" segue o idioma do sistema; os idiomas aparecem pelo nome no próprio idioma.
                let label = ui.label(tr("Language"));
                let shown = if language == i18n::AUTO { tr("Automatic") } else { i18n::name_of(&language) };
                egui::ComboBox::from_id_salt("settings-lang")
                    .selected_text(shown)
                    .show_ui(ui, |ui| {
                        ui.selectable_value(&mut new_language, i18n::AUTO.to_string(), tr("Automatic"));
                        for code in i18n::LANGUAGES {
                            ui.selectable_value(&mut new_language, code.to_string(), i18n::name_of(code));
                        }
                    })
                    .response
                    .labelled_by(label.id);
                ui.end_row();
                ui.label(tr("Theme"));
                ui.horizontal(|ui| {
                    for choice in ThemeMode::EVERY {
                        ui.selectable_value(&mut mode, choice, tr(choice.label()));
                    }
                });
                ui.end_row();
            });
            if quick {
                ui.add_space(8.0);
                if ui.button(tr("Forget key")).clicked() {
                    forget = true;
                }
            }
            ui.add_space(8.0);
            ui.separator();
            ui.add_space(4.0);
            ui.strong(tr("About Local Keys"));
            ui.label(tr_fmt("version {version}", &[("version", env!("CARGO_PKG_VERSION"))]));
            ui.hyperlink_to("github.com/Anon5T4R/Local-Keys", env!("CARGO_PKG_REPOSITORY"));
            ui.add_space(6.0);
            ui.horizontal(|ui| {
                let checking = matches!(status, Status::Checking);
                if ui.add_enabled(!checking, egui::Button::new(tr("Check for Updates…"))).clicked() {
                    check = true;
                }
                match &status {
                    Status::Checking => {
                        ui.spinner();
                    }
                    Status::UpToDate => {
                        ui.label(tr("You are on the newest version."));
                    }
                    Status::Failed(reason) => {
                        ui.label(format!("{}: {reason}", tr("Error")));
                    }
                    Status::Idle => {}
                }
            });
        });
        if new_language != language {
            self.set_language(&new_language);
        }
        if self.prefs.mode() != mode {
            self.set_mode(ctx, mode);
        }
        if forget {
            self.forget_quick_key();
        }
        if check {
            self.updater.check_now(ctx);
        }
        if close || resp.should_close() {
            self.settings_open = false;
            self.updater.dismiss_status();
        }
    }

    /// O que a paleta lista agora. Com o cofre trancado só o que não toca no cofre; nada aqui mostra um segredo.
    pub(crate) fn commands(&self) -> Vec<Command> {
        let (vault, settings, help, app) = (tr("Vault"), tr("Settings"), tr("Help"), "Local Keys");
        let mut list = Vec::new();
        if self.locked() {
            let ready = self.job_label.is_none() && !self.path.trim().is_empty() && !self.master.is_empty();
            list.push(Command::new("vault.unlock", tr("Unlock vault"), vault).enabled(ready));
        } else {
            list.extend([
                Command::new("vault.add", tr("Add"), vault),
                Command::new("vault.lock", tr("Lock"), vault),
                Command::new("vault.import", tr("Import"), vault),
                Command::new("vault.export", tr("Export"), vault),
                Command::new("vault.generator", tr("Generator"), vault),
                Command::new("vault.change_master", tr("Change master password"), vault),
                Command::new("vault.trash", tr("Trash"), vault),
                Command::new("vault.new_folder", tr("New folder"), vault),
                Command::new("vault.folders", tr("Folders"), vault),
            ]);
        }
        list.push(Command::new("settings.open", tr("Settings"), settings));
        for mode in ThemeMode::EVERY {
            let label = tr_fmt("Theme: {mode}", &[("mode", tr(mode.label()))]);
            list.push(Command::new(format!("settings.theme.{}", mode.id()), label, settings));
        }
        let checking = matches!(self.updater.status(), Status::Checking);
        list.extend([
            Command::new("help.updates", tr("Check for Updates…"), help).enabled(!checking),
            Command::new("help.about", tr("About Local Keys"), help),
            Command::new("app.exit", tr("Exit"), app),
        ]);
        list
    }

    /// Roda o que a paleta devolveu, pelo mesmo código dos botões.
    pub(crate) fn run_command(&mut self, ctx: &egui::Context, id: &str) {
        match id {
            "vault.unlock" => self.unlock(ctx),
            "vault.add" => self.open_add_form(),
            "vault.lock" => self.lock(),
            "vault.import" => {
                self.import_open = true;
                self.reveal_import = false;
            }
            "vault.export" => {
                self.export_path.clear();
                self.export_open = true;
            }
            "vault.generator" => self.gen_open = true,
            "vault.change_master" => self.change_open = true,
            "vault.trash" => {
                self.trash_open = true;
                self.purge_ask = None;
            }
            "vault.new_folder" => {
                self.folder_new.clear();
                self.folder_new_open = true;
            }
            "vault.folders" => {
                self.folders_open = true;
                self.folder_edit_id = None;
                self.folder_edit_name.clear();
            }
            "settings.open" | "help.about" => self.settings_open = true,
            "help.updates" => {
                // O resultado aparece nas Configurações, como no botão de lá.
                self.settings_open = true;
                self.updater.check_now(ctx);
            }
            // Sair de verdade (inclusive com bandeja): a flag da bandeja, que o frame transforma em Close.
            "app.exit" => self.quit.store(true, Ordering::Relaxed),
            _ => {
                if let Some(mode) = id.strip_prefix("settings.theme.") {
                    self.set_mode(ctx, ThemeMode::from_id(mode));
                }
            }
        }
    }

    /// A paleta, quando aberta.
    pub(crate) fn palette_ui(&mut self, ctx: &egui::Context) {
        if !self.palette.is_open() {
            return;
        }
        let commands = self.commands();
        let labels = craft_palette::Labels { placeholder: tr("Search commands"), empty: tr("No command matches.") };
        if let Some(id) = self.palette.show(ctx, &labels, &commands) {
            self.run_command(ctx, &id);
        }
    }
}
