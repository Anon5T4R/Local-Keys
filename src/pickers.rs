//! Seletor nativo que nunca bloqueia o frame.
//!
//! Molde: `local-pdf/crates/ui-egui/src/pickers.rs` — o app tem várias
//! continuações distintas (abrir cofre, importar, exportar, anexos), então a
//! resposta chega como closure `Then`, executada num frame posterior com o
//! app mutável. Um seletor por vez; cancelamento = paths vazios (a closure
//! decide o que fazer — tipicamente cai no campo de caminho digitado).
//!
//! Backends do rfd 0.17 (defaults): Windows = diálogo nativo; Linux = portal
//! XDG via zbus (Rust puro, fallback Zenity). macOS não é alvo da suíte;
//! se um dia for, criar o future na main thread antes de spawnar (ver
//! local-pdf).

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, PoisonError};

use crate::KeysApp;

/// Qual painel nativo mostrar.
pub(crate) enum Ask {
    /// Escolher um arquivo existente.
    File(rfd::AsyncFileDialog),
    /// Escolher vários arquivos existentes (multi-seleção).
    Files(rfd::AsyncFileDialog),
    /// Escolher onde salvar (nome sugerido já no diálogo).
    Save(rfd::AsyncFileDialog),
}

impl Ask {
    /// Nome curto do painel, para o registro dos testes.
    #[cfg(test)]
    fn kind(&self) -> &'static str {
        match self {
            Self::File(_) => "file",
            Self::Files(_) => "files",
            Self::Save(_) => "save",
        }
    }
}

/// O que fazer com os caminhos escolhidos, num frame posterior.
/// Paths vazios = usuário cancelou.
pub(crate) type Then = Box<dyn FnOnce(&mut KeysApp, Vec<PathBuf>) + Send>;

/// Uma escolha pronta, esperando o frame.
pub(crate) struct Picked {
    pub(crate) paths: Vec<PathBuf>,
    pub(crate) then: Then,
}

/// O que os testes observam em vez do diálogo nativo (que eles não podem abrir).
#[cfg(test)]
#[derive(Default)]
struct Spy {
    /// Pasta inicial DECIDIDA para cada seletor (`None` = o padrão do sistema).
    dialogs: Vec<Option<PathBuf>>,
    /// Tipo de cada pedido feito ao seletor (`file`, `files`, `save`), na ordem.
    asks: Vec<&'static str>,
    /// Não abre o diálogo nativo: o pedido registrado é a prova do teste.
    dry: bool,
}

/// Seletor em andamento + escolhas prontas.
#[derive(Default)]
pub(crate) struct Pickers {
    showing: Arc<AtomicBool>,
    done: Arc<Mutex<Vec<Picked>>>,
    #[cfg(test)]
    spy: Arc<Mutex<Spy>>,
}

impl Pickers {
    /// Um seletor está aberto (ou a resposta ainda espera o frame)?
    pub(crate) fn busy(&self) -> bool {
        self.showing.load(Ordering::SeqCst)
    }

    /// Mostra `ask` sem bloquear o frame; `then` roda num frame posterior com
    /// a escolha (vazia = cancelou). `false` = já há um seletor aberto (ou a
    /// thread não subiu) — o clique é ignorado, como hoje.
    pub(crate) fn ask(&self, ctx: &egui::Context, ask: Ask, then: Then) -> bool {
        if self.busy() {
            return false;
        }
        #[cfg(test)]
        if self.spy_records(ask.kind()) {
            // Modo seco: o pedido fica registrado e o seletor conta como OCUPADO até a resposta
            // chegar pela fila (`push_for_test`), como a thread real faz.
            self.showing.store(true, Ordering::SeqCst);
            return true;
        }
        self.showing.store(true, Ordering::SeqCst);
        let showing = self.showing.clone();
        let done = self.done.clone();
        let ctx = ctx.clone(); // Arc interno: barato; a thread exige 'static
        std::thread::Builder::new()
            .name("file-picker".into())
            .spawn(move || {
                let paths = pollster::block_on(async {
                    match ask {
                        Ask::File(d) => d.pick_file().await.map(|h| vec![h.path().to_path_buf()]).unwrap_or_default(),
                        Ask::Files(d) => d.pick_files().await.map(|hs| hs.into_iter().map(|h| h.path().to_path_buf()).collect()).unwrap_or_default(),
                        Ask::Save(d) => d.save_file().await.map(|h| vec![h.path().to_path_buf()]).unwrap_or_default(),
                    }
                });
                done.lock().unwrap_or_else(PoisonError::into_inner).push(Picked { paths, then });
                showing.store(false, Ordering::SeqCst);
                ctx.request_repaint(); // acorda o frame que consome a resposta
            })
            .is_ok()
    }

    /// Escolhas prontas — o frame que consome limpa a fila. A closure recebe
    /// o app mutável (chamada tipo: `for p in self.pickers.take() { (p.then)(self, p.paths); }`).
    pub(crate) fn take(&self) -> Vec<Picked> {
        std::mem::take(&mut *self.done.lock().unwrap_or_else(PoisonError::into_inner))
    }

    /// Enfileira uma resposta como a thread do seletor faria — só nos testes,
    /// que não podem abrir o diálogo nativo. O frame consome pelo `take`.
    /// Libera o próximo pedido, como a thread faz ao terminar.
    #[cfg(test)]
    pub(crate) fn push_for_test(&self, picked: Picked) {
        self.done.lock().unwrap_or_else(PoisonError::into_inner).push(picked);
        self.showing.store(false, Ordering::SeqCst);
    }

    /// Testes: registra o tipo do pedido e diz se o modo seco está ligado (o diálogo nativo não abre).
    #[cfg(test)]
    fn spy_records(&self, kind: &'static str) -> bool {
        let mut spy = self.spy.lock().unwrap_or_else(PoisonError::into_inner);
        spy.asks.push(kind);
        spy.dry
    }

    /// Testes: a pasta inicial que o app DECIDIU para o seletor (`None` = o padrão do sistema). A
    /// aplicação ao diálogo em si é provada pelo `Debug` do rfd (`file_dialog`).
    #[cfg(test)]
    pub(crate) fn dialog_asked(&self, start_dir: Option<PathBuf>) {
        self.spy.lock().unwrap_or_else(PoisonError::into_inner).dialogs.push(start_dir);
    }

    /// Testes: a pasta inicial decidida para cada seletor, na ordem.
    #[cfg(test)]
    pub(crate) fn dialogs_asked(&self) -> Vec<Option<PathBuf>> {
        self.spy.lock().unwrap_or_else(PoisonError::into_inner).dialogs.clone()
    }

    /// Testes: o tipo de cada pedido feito ao seletor, na ordem.
    #[cfg(test)]
    pub(crate) fn asks(&self) -> Vec<&'static str> {
        self.spy.lock().unwrap_or_else(PoisonError::into_inner).asks.clone()
    }

    /// Testes: daqui em diante o clique só registra o pedido — o diálogo nativo nunca abre (nenhum
    /// teste abre janela). O estado do `busy` é o mesmo da produção: o pedido ocupa o seletor até a
    /// resposta ser entregue por `push_for_test`.
    #[cfg(test)]
    pub(crate) fn set_dry(&self) {
        self.spy.lock().unwrap_or_else(PoisonError::into_inner).dry = true;
    }
}
