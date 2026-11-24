//! Política da bandeja, sem depender do `tray-icon` (que só entra no Windows):
//! assim os testes rodam também no Linux, onde o CI compila.
//!
//! O menu Show/Exit é só do clique direito. O clique esquerdo mostra/esconde a
//! janela. O `tray-icon` 0.24 vem com `menu_on_left_click = true`, que no
//! Windows abre o menu no `WM_LBUTTONUP` — a janela aparece e o menu pisca.

/// Comando vindo da bandeja (thread própria → handlers do tray-icon).
#[cfg(any(windows, test))]
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum TrayCmd {
    ShowHide,
    Quit,
}

/// `true` abriria o menu no clique esquerdo (o padrão do `tray-icon`).
#[cfg(any(windows, test))]
pub const MENU_ON_LEFT_CLICK: bool = false;

/// Botão de um clique no ícone, na ordem do `tray_icon::MouseButton`.
#[cfg(any(windows, test))]
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Click {
    Left,
    Right,
    Middle,
}

/// Costura para o teste: o builder real guarda o flag num campo privado e o
/// crate não o expõe para leitura. O fake do teste registra o valor recebido.
#[cfg(any(windows, test))]
pub trait TrayPolicy {
    fn menu_on_left_click(self, enable: bool) -> Self;
}

#[cfg(windows)]
impl TrayPolicy for tray_icon::TrayIconBuilder {
    fn menu_on_left_click(self, enable: bool) -> Self {
        self.with_menu_on_left_click(enable)
    }
}

/// Aplica a política da bandeja a um builder. Único lugar que decide se o menu
/// aparece no clique esquerdo.
#[cfg(any(windows, test))]
pub fn configure<B: TrayPolicy>(builder: B) -> B {
    builder.menu_on_left_click(MENU_ON_LEFT_CLICK)
}

/// Clique no ícone → comando. Só o esquerdo solto faz alguma coisa; o direito
/// (e o do meio) não mandam comando — o menu é do SO.
#[cfg(any(windows, test))]
pub fn click_cmd(button: Click, released: bool) -> Option<TrayCmd> {
    match (button, released) {
        (Click::Left, true) => Some(TrayCmd::ShowHide),
        _ => None,
    }
}

/// Item do menu → comando, pelo id criado em `spawn_tray`.
#[cfg(any(windows, test))]
pub fn menu_cmd(id: &str) -> Option<TrayCmd> {
    match id {
        "show" => Some(TrayCmd::ShowHide),
        "quit" => Some(TrayCmd::Quit),
        _ => None,
    }
}

#[cfg(windows)]
impl From<tray_icon::MouseButton> for Click {
    fn from(button: tray_icon::MouseButton) -> Self {
        use tray_icon::MouseButton;
        match button {
            MouseButton::Left => Self::Left,
            MouseButton::Right => Self::Right,
            MouseButton::Middle => Self::Middle,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Builder falso: registra o que a política lhe passou.
    #[derive(Default)]
    struct FakeBuilder(Option<bool>);

    impl TrayPolicy for FakeBuilder {
        fn menu_on_left_click(mut self, enable: bool) -> Self {
            self.0 = Some(enable);
            self
        }
    }

    #[test]
    fn the_tray_policy_turns_the_menu_off_on_left_click() {
        let fake = configure(FakeBuilder::default());
        assert_eq!(fake.0, Some(false), "o menu não pode abrir no clique esquerdo");
    }

    #[test]
    fn the_real_builder_accepts_the_policy() {
        // Compila só no Windows: o builder real implementa a costura.
        #[cfg(windows)]
        let _ = configure(tray_icon::TrayIconBuilder::new());
    }

    #[test]
    fn spawn_tray_goes_through_the_policy() {
        // `spawn_tray` é `#[cfg(windows)]`; conferir o texto é o que dá para
        // testar no Linux. Por isso o corpo precisa casar até o fim: um
        // comentário com a frase, ou um `with_menu_on_left_click` depois do
        // `configure`, não passam.
        let main = include_str!("main.rs");
        let start = main.find("fn spawn_tray()").expect("spawn_tray");
        let rest = &main[start..];
        let body = rest.split_once("fn tray_icon()").expect("tray_icon, que vem depois").0;
        let code: String = body.lines().filter(|line| !line.trim_start().starts_with("//")).collect();
        let builders = code.matches("TrayIconBuilder::new()").count();
        assert_eq!(builders, 1, "spawn_tray monta a bandeja num builder só");
        assert!(!code.contains("with_menu_on_left_click("), "o menu foi religado por fora da política");
        assert!(code.contains("tray::configure(TrayIconBuilder::new())"), "o builder da bandeja sai sem a política");
    }

    #[test]
    fn a_released_left_click_toggles_the_window() {
        assert_eq!(click_cmd(Click::Left, true), Some(TrayCmd::ShowHide));
        assert_eq!(click_cmd(Click::Left, false), None, "soltar é o que conta");
    }

    #[test]
    fn other_buttons_send_no_command() {
        for button in [Click::Right, Click::Middle] {
            for released in [true, false] {
                assert_eq!(click_cmd(button, released), None, "o menu é do SO");
            }
        }
    }

    #[test]
    fn menu_items_map_to_commands() {
        assert_eq!(menu_cmd("show"), Some(TrayCmd::ShowHide));
        assert_eq!(menu_cmd("quit"), Some(TrayCmd::Quit));
        assert_eq!(menu_cmd(""), None);
        assert_eq!(menu_cmd("Show/Hide"), None);
    }
}
