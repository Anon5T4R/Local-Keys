//! A seleção múltipla na LISTA do desktop (unidade 5, peça 2): os cliques com Ctrl/Shift, a barra de
//! ações do rodapé e a execução do lote (lixeira e pasta).
//!
//! O estado puro mora em [`crate::selection::Selection`]; aqui ficam só a interação e a gravação. O lote é
//! tudo ou nada e grava UMA vez: as funções da regra (`localkeys_core::batch`) rodam sobre um clone do cofre e
//! o `persist` só é chamado depois do `Ok` — uma falha deixa memória e disco intactos.

use egui::Ui;
use localkeys_core::{batch, vault};

use crate::KeysApp;
use crate::i18n::{tr, tr_n};

/// O `now` do lote: um só para o lote inteiro (cada gravação recifra o cofre).
fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// O motivo do lote recusado no idioma da interface. O `Display` do núcleo é diagnóstico em inglês e não
/// chega à tela; cada variante tem a sua mensagem (o id cru do item/pasta não aparece).
fn batch_error_text(error: &batch::BatchError) -> String {
    match error {
        batch::BatchError::MissingId => tr("The selection has an item that is not valid; nothing was changed."),
        batch::BatchError::UnknownId { .. } => tr("One of the selected items no longer exists in the vault; nothing was changed."),
        batch::BatchError::AlreadyTrashed { .. } => tr("One of the selected items is already in the trash; nothing was changed."),
        batch::BatchError::UnknownFolder { .. } => tr("The destination folder no longer exists; nothing was changed."),
        batch::BatchError::MalformedVault => tr("The vault has no valid item list; nothing was changed."),
        batch::BatchError::InvalidJson(_) => tr("The operation could not be completed; nothing was changed."),
    }
    .to_string()
}

impl KeysApp {
    /// Zera a seleção da lista (e a âncora do Shift e a confirmação pendente).
    pub(crate) fn clear_selection(&mut self) {
        self.selection.clear();
        self.selection_anchor = None;
        self.batch_delete_ask = None;
    }

    /// Trocar filtro, pasta ou busca: a lista visível muda e a seleção da anterior não vale.
    pub(crate) fn reset_selection_for_filter_change(&mut self) {
        self.selection.reset_for_filter_change();
        self.selection_anchor = None;
        self.batch_delete_ask = None;
    }

    /// Poda a seleção contra a lista visível ATUAL — chamado depois de cada `persist`. Um item marcado que saiu da
    /// tela por outra via (o ★ desmarcado com o filtro "Favoritos" ligado, pasta ou tipo trocados no editor, item
    /// que foi para a lixeira) não pode continuar contando no rodapé nem ser apagado/movido às cegas.
    pub(crate) fn prune_selection(&mut self) {
        let visible = self.visible_ids();
        let present: std::collections::HashSet<&str> = visible.iter().map(String::as_str).collect();
        self.selection.retain_visible(|id| present.contains(id));
        if self.selection_anchor.as_ref().is_some_and(|anchor| !present.contains(anchor.as_str())) {
            self.selection_anchor = None;
        }
        if self.batch_delete_ask.is_some() {
            // A confirmação aberta mostrava outra contagem; com a seleção podada ela acompanha (ou some vazia).
            self.batch_delete_ask = if self.selection.is_empty() { None } else { Some(self.selection.len()) };
        }
    }

    /// Clique numa linha: Ctrl alterna, Shift pega o intervalo pela ordem visível. O clique simples continua
    /// como antes (não mexe na seleção — as ações da linha são os botões dela).
    pub(crate) fn row_click(&mut self, id: &str, visible: &[String], modifiers: egui::Modifiers) {
        if modifiers.command {
            self.selection.toggle(id);
            self.selection_anchor = Some(id.to_string());
        } else if modifiers.shift {
            match self.selection_anchor.clone() {
                Some(anchor) => self.selection.range(&anchor, id, visible),
                None => {
                    self.selection.select_only(id);
                    self.selection_anchor = Some(id.to_string());
                }
            }
        }
    }

    /// Atalhos da seleção com a lista visível: Ctrl+A marca tudo, `Esc` limpa e `Delete` pede a confirmação
    /// do apagar. Com um diálogo aberto, um campo de texto focado, a paleta ou um POPUP (menu "Move to folder…",
    /// por exemplo) aberto, o teclado é deles — o `Esc` fecha o menu em vez de limpar a seleção, e o `Delete` não
    /// abre a confirmação por baixo dele.
    pub(crate) fn selection_keys(&mut self, ctx: &egui::Context, visible: &[String]) {
        if self.any_modal_open() || self.palette.is_open() || ctx.text_edit_focused() || ctx.any_popup_open() {
            return;
        }
        if ctx.input_mut(|i| i.consume_key(egui::Modifiers::COMMAND, egui::Key::A)) {
            self.selection.clear();
            for id in visible {
                self.selection.toggle(id);
            }
            self.selection_anchor = visible.first().cloned();
        }
        if ctx.input(|i| i.key_pressed(egui::Key::Escape)) && !self.selection.is_empty() {
            self.clear_selection();
        }
        if ctx.input(|i| i.key_pressed(egui::Key::Delete)) && !self.selection.is_empty() {
            self.batch_delete_ask = Some(self.selection.len());
        }
    }

    /// Manda a seleção para a lixeira com UMA gravação. Erro da regra ou falha do `persist`: nada muda.
    pub(crate) fn trash_selected(&mut self) {
        let ids: Vec<String> = self.selection.ids().map(str::to_string).collect();
        if ids.is_empty() {
            self.batch_delete_ask = None;
            return;
        }
        let Some(mut next) = self.raw.clone() else {
            return;
        };
        match batch::trash_many(&mut next, &ids, now_ms()) {
            Ok(_) => {
                if self.persist(next) {
                    self.clear_selection();
                } else {
                    // `persist` já pôs o motivo em `err`; a seleção fica para a pessoa tentar de novo.
                    self.batch_delete_ask = None;
                }
            }
            Err(error) => {
                self.err = Some(batch_error_text(&error));
                self.batch_delete_ask = None;
            }
        }
    }

    /// Move a seleção para `folder_id` (`None` = sem pasta) com UMA gravação; erro da regra ou falha do
    /// `persist` deixa o cofre como estava.
    pub(crate) fn move_selected(&mut self, folder_id: Option<String>) {
        let ids: Vec<String> = self.selection.ids().map(str::to_string).collect();
        if ids.is_empty() {
            return;
        }
        let Some(mut next) = self.raw.clone() else {
            return;
        };
        match batch::move_many(&mut next, &ids, folder_id.as_deref(), now_ms()) {
            // Nada mudou de fato (os itens já estavam nessa pasta): não há o que gravar, e a seleção fica para a
            // pessoa escolher outro destino.
            Ok(0) => {}
            Ok(_) => {
                if self.persist(next) {
                    self.clear_selection();
                }
            }
            Err(error) => self.err = Some(batch_error_text(&error)),
        }
    }

    /// A barra do rodapé quando há itens marcados: contagem, mover de pasta, exportar para um cofre novo, apagar
    /// (confirmação) e limpar.
    pub(crate) fn selection_bar(&mut self, ui: &mut Ui) {
        if self.selection.is_empty() {
            return;
        }
        let count = self.selection.len() as u64;
        let folders = self.raw.as_ref().map(vault::folders).unwrap_or_default();
        let busy = self.job_label.is_some() || self.pickers.busy();
        let mut move_to: Option<Option<String>> = None;
        let mut export_vault = false;
        ui.horizontal_wrapped(|ui| {
            ui.label(egui::RichText::new(tr_n(count, "{n} selected", "{n} selected")).strong());
            // A popup abre ACIMA da barra (TOP_START): na base da janela ela abria para baixo e ficava
            // cortada pela borda da janela (pedido 030, revisão — o mover a 1000×660).
            let menu = ui.add(egui::Button::new(tr("Move to folder…")));
            // Com muitas pastas a lista rola DENTRO da popup: ancorada em TOP_START, uma popup mais alta
            // que o espaço livre acima estourava a borda de CIMA e escondia justamente as primeiras
            // opções ("No folder" e as primeiras pastas — pedido 030, item 6). O teto é o espaço entre
            // o topo da janela e o topo do botão, descontada a moldura da popup.
            let teto = (menu.rect.top() - ui.ctx().content_rect().top() - 24.0).max(120.0);
            // Cada abertura incrementa o salt da rolagem interna (o ScrollArea reabria onde parou).
            if menu.clicked() {
                self.move_menu_opens += 1;
            }
            let _ = egui::Popup::menu(&menu).align(egui::RectAlign::TOP_START).show(|ui| {
                // Sem segue-foco manual: o próprio egui rola o ScrollArea até o widget que recebe o
                // foco do teclado (verificado por rects no kittest — a pasta do fim fica no meio da
                // vista com o foco nela); o teste the_move_menu_reaches trava essa visibilidade.
                egui::ScrollArea::vertical().id_salt(format!("mover-lista-{}", self.move_menu_opens)).max_height(teto).show(ui, |ui| {
                    if ui.button(tr("No folder")).clicked() {
                        move_to = Some(None);
                        ui.close();
                    }
                    for folder in &folders {
                        if ui.button(&folder.name).clicked() {
                            move_to = Some(Some(folder.id.clone()));
                            ui.close();
                        }
                    }
                });
            });
            // Exportar um cofre NOVO com a seleção (o nome dele é o do arquivo; a senha vem no diálogo seguinte).
            if ui.add_enabled_ui(!busy, |ui| crate::ghost(ui, tr("Export to vault…"))).inner.clicked() {
                export_vault = true;
            }
            if crate::pill_danger(ui, tr("Delete selected")).clicked() {
                self.batch_delete_ask = Some(self.selection.len());
            }
            if crate::ghost(ui, tr("Clear selection")).clicked() {
                self.clear_selection();
            }
        });
        if let Some(folder) = move_to {
            self.move_selected(folder);
        }
        if export_vault {
            self.export_vault_ask(ui.ctx());
        }
    }

    /// A confirmação do apagar em lote, com a contagem ("Move N items to the trash?").
    pub(crate) fn batch_delete_modal(&mut self, ctx: &egui::Context) {
        let Some(count) = self.batch_delete_ask else {
            return;
        };
        let mut confirm = false;
        let mut cancel = false;
        let resp = egui::Modal::new(egui::Id::unique("batch-delete")).frame(crate::modal_frame(ctx)).show(ctx, |ui| {
            crate::begin_dialog(ui);
            crate::dialog_width(ui, 340.0);
            ui.heading(tr("Delete"));
            ui.add_space(8.0);
            ui.label(tr_n(count as u64, "Move {n} item to the trash?", "Move {n} items to the trash?"));
            ui.add_space(12.0);
            ui.horizontal(|ui| {
                if crate::pill_danger(ui, tr("Confirm")).clicked() {
                    confirm = true;
                }
                if crate::pill(ui, tr("Cancel"), false).clicked() {
                    cancel = true;
                }
            });
        });
        if confirm {
            self.trash_selected();
        } else if cancel || resp.should_close() {
            self.batch_delete_ask = None;
        }
    }

    /// Algum diálogo/modal por cima da lista? (o teclado da seleção não pode agir por baixo deles)
    pub(crate) fn any_modal_open(&self) -> bool {
        self.form_open
            || self.new_kind_open
            || self.delete_ask.is_some()
            || self.import_open
            || self.import_report.is_some()
            || self.gen_open
            || self.change_open
            || self.attach_item.is_some()
            // O aviso de cofre grande é um Modal de verdade: esconde barra e rodapé (teste parametrizado).
            || self.attach_warn.is_some()
            || self.folders_open
            || self.folder_new_open
            || self.trash_open
            || self.export_open
            || self.export_vault_open
            || self.sync_open
            || self.merge_open
            || self.merge_plan.is_some()
            || self.settings_open
            // A paleta (Ctrl+K) NÃO entra aqui: ela é um popup no topo, sem véu — esconder o rodapé
            // deixaria uma faixa vazia que nada cobre. O guarda dela é o de teclado (update, acima),
            // onde ela já estava.
            // O prompt do atualizador (craft_update) é um modal do crate: enquanto ele está aberto, o teclado da
            // seleção não pode apagar por baixo dele.
            || self.updater.prompt_open()
            || self.batch_delete_ask.is_some()
    }
}
