//! Tema do Local Keys: o modo da suíte (`craft_icons::ThemeMode` — Automático, Claro, Escuro) + `Tokens` (as
//! cores da identidade do app) aplicados por `set_visuals_of` nos DOIS temas; no Automático o egui troca sozinho
//! quando o SO muda. Widgets nunca hard-codam cor: leem `ui.visuals()` (o `Skin` do app) ou os Tokens.

use craft_icons::ThemeMode;
use egui::Color32;

/// A preferência do egui de um modo: o Automático continua automático (não resolve para fixo).
fn preference(mode: ThemeMode) -> egui::ThemePreference {
    match mode {
        ThemeMode::Light => egui::ThemePreference::Light,
        ThemeMode::Dark => egui::ThemePreference::Dark,
        ThemeMode::System => egui::ThemePreference::System,
    }
}

/// As cores da identidade do LocalKeys; widgets nunca hard-codam cor.
#[derive(Clone, Copy, Debug)]
pub struct Tokens {
    pub dark: bool,
    /// Fundo de painel/janela.
    pub base: Color32,
    /// Fundo "afundado": campos, cards, gráficos.
    pub input: Color32,
    /// Bordas sutis e fortes.
    pub border: Color32,
    pub border_strong: Color32,
    /// Texto principal.
    pub text: Color32,
    /// Hover/pressionado sobre superfícies.
    pub hover: Color32,
    pub pressed: Color32,
    /// Destaque (seleção, links, foco).
    pub accent: Color32,
    /// Aviso em texto (o tipo do formulário mudou): legível sobre o cartão do diálogo nos dois temas.
    pub warn: Color32,
    /// Erro em texto (gravação recusada): legível sobre o cartão do diálogo nos dois temas.
    pub danger: Color32,
    /// Sombras.
    pub shadow: Color32,
}

impl Tokens {
    pub fn light() -> Self {
        Tokens {
            dark: false,
            base: Color32::from_rgb(0xf2, 0xf3, 0xf1),
            input: Color32::from_rgb(0xff, 0xff, 0xff),
            border: Color32::from_black_alpha(25),
            border_strong: Color32::from_black_alpha(55),
            text: Color32::from_rgb(0x1f, 0x26, 0x21),
            hover: Color32::from_black_alpha(14),
            pressed: Color32::from_black_alpha(28),
            // 5,3:1 com branco por cima (pill primário) e sobre a base (links): AA. O #2f8f55 de antes dava 4,0:1.
            accent: Color32::from_rgb(0x26, 0x7a, 0x48),
            // Sobre o cartão do diálogo, que no claro é branco: 5,9:1 (aviso) e 6,5:1 (erro) — AA para texto pequeno.
            warn: Color32::from_rgb(0x8a, 0x5a, 0x00),
            danger: Color32::from_rgb(0xb3, 0x26, 0x1e),
            shadow: Color32::from_black_alpha(45),
        }
    }

    pub fn dark() -> Self {
        Tokens {
            dark: true,
            base: Color32::from_rgb(0x16, 0x1b, 0x17),
            input: Color32::from_rgb(0x0e, 0x12, 0x0f),
            border: Color32::from_white_alpha(30),
            border_strong: Color32::from_white_alpha(65),
            text: Color32::from_rgb(0xdf, 0xe7, 0xdf),
            hover: Color32::from_white_alpha(16),
            pressed: Color32::from_white_alpha(30),
            accent: Color32::from_rgb(0x7c, 0xbf, 0x7f),
            // Sobre o cartão do diálogo escuro (#0e120f): 8,6:1 (aviso) e 6,8:1 (erro).
            warn: Color32::from_rgb(0xe6, 0xa2, 0x3c),
            danger: Color32::from_rgb(0xe8, 0x7c, 0x7c),
            shadow: Color32::from_black_alpha(140),
        }
    }
}

/// Aplica AMBOS os temas (`set_visuals_of` Light e Dark) + o modo. No Automático o egui alterna sozinho quando
/// o SO muda.
pub fn apply(ctx: &egui::Context, mode: ThemeMode) {
    ctx.set_visuals_of(egui::Theme::Light, visuals(&Tokens::light()));
    ctx.set_visuals_of(egui::Theme::Dark, visuals(&Tokens::dark()));
    ctx.all_styles_mut(|s| {
        s.spacing.item_spacing = egui::vec2(6.0, 4.0);
        s.spacing.button_padding = egui::vec2(8.0, 3.0);
        s.spacing.interact_size = egui::vec2(24.0, 22.0);
        s.spacing.menu_margin = egui::Margin::same(6);
    });
    ctx.options_mut(|o| {
        o.fallback_theme = egui::Theme::Dark;
        o.theme_preference = preference(mode);
    });
}

/// Visuals de um tema a partir dos Tokens (molde do local-office).
fn visuals(t: &Tokens) -> egui::Visuals {
    let mut v = if t.dark { egui::Visuals::dark() } else { egui::Visuals::light() };
    v.panel_fill = t.base;
    v.window_fill = t.input;
    v.extreme_bg_color = t.input;
    v.faint_bg_color = t.base;
    v.code_bg_color = t.input;
    v.window_stroke = egui::Stroke::new(1.0, t.border_strong);
    v.window_corner_radius = egui::CornerRadius::same(8);
    v.menu_corner_radius = egui::CornerRadius::same(6);
    v.selection.bg_fill = t.accent.gamma_multiply(0.35);
    v.selection.stroke = egui::Stroke::new(1.0, t.accent);
    v.hyperlink_color = t.accent;
    v.warn_fg_color = t.warn;
    v.error_fg_color = t.danger;
    v.widgets.noninteractive.bg_stroke = egui::Stroke::new(1.0, t.border);
    v.widgets.noninteractive.fg_stroke = egui::Stroke::new(1.0, t.text);
    v.widgets.inactive.bg_fill = t.input;
    v.widgets.inactive.weak_bg_fill = t.input;
    v.widgets.inactive.bg_stroke = egui::Stroke::new(1.0, t.border);
    v.widgets.inactive.fg_stroke = egui::Stroke::new(1.0, t.text);
    v.widgets.inactive.corner_radius = egui::CornerRadius::same(4);
    v.widgets.hovered.bg_fill = t.hover;
    v.widgets.hovered.weak_bg_fill = t.hover;
    v.widgets.hovered.bg_stroke = egui::Stroke::new(1.0, t.border_strong);
    v.widgets.hovered.fg_stroke = egui::Stroke::new(1.0, t.text);
    v.widgets.hovered.corner_radius = egui::CornerRadius::same(4);
    v.widgets.active.bg_fill = t.pressed;
    v.widgets.active.weak_bg_fill = t.pressed;
    v.widgets.active.fg_stroke = egui::Stroke::new(1.0, t.text);
    v.widgets.active.corner_radius = egui::CornerRadius::same(4);
    v.widgets.open.bg_fill = t.pressed;
    v.widgets.open.weak_bg_fill = t.pressed;
    v.popup_shadow = egui::Shadow { offset: [0, 4], blur: 16, spread: 0, color: t.shadow };
    v.window_shadow = egui::Shadow { offset: [0, 8], blur: 28, spread: 0, color: t.shadow };
    v
}

/// Fundo da linha marcada na seleção múltipla: TRANSPARENTE nos dois temas. O destaque é só a BARRA
/// do accent na borda esquerda + o contorno (revisão 2 do pedido 030, item A): o app renderiza com
/// blend em GAMA (framebuffer sem sRGB), e qualquer lavado — mesmo o sutil ×0,16 do claro — levanta
/// o fundo sob o subtítulo `.small().weak()`, que é um overlay (texto × 0,6 com alfa 153) e não
/// aguenta fundo mudado. Sem lavado, a linha marcada tem exatamente o fundo da lista.
pub fn selected_row_fill(_accent: Color32, _dark: bool) -> Color32 {
    Color32::TRANSPARENT
}

/// Contorno da linha marcada: o traço do accent completo fecha a seleção em volta da linha.
pub fn selected_row_stroke(accent: Color32) -> egui::Stroke {
    egui::Stroke::new(1.0, accent)
}

/// A barra do accent na borda esquerda da linha marcada (o traço forte da seleção).
pub fn selected_row_bar(accent: Color32) -> Color32 {
    accent
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn each_mode_maps_to_its_egui_preference() {
        assert_eq!(preference(ThemeMode::System), egui::ThemePreference::System);
        assert_eq!(preference(ThemeMode::Light), egui::ThemePreference::Light);
        assert_eq!(preference(ThemeMode::Dark), egui::ThemePreference::Dark);
    }

    /// A luminância relativa da WCAG (0–1) de uma cor sRGB.
    fn relative_luminance(c: Color32) -> f64 {
        let channel = |v: u8| {
            let v = f64::from(v) / 255.0;
            if v <= 0.04045 { v / 12.92 } else { ((v + 0.055) / 1.055).powf(2.4) }
        };
        0.2126 * channel(c.r()) + 0.7152 * channel(c.g()) + 0.0722 * channel(c.b())
    }

    /// O contraste WCAG entre duas cores (1:1 a 21:1).
    fn contrast(a: Color32, b: Color32) -> f64 {
        let (la, lb) = (relative_luminance(a), relative_luminance(b));
        (la.max(lb) + 0.05) / (la.min(lb) + 0.05)
    }

    /// O aviso e o erro do formulário são texto pequeno sobre o cartão do diálogo (`input` nos dois temas):
    /// passam de 4,5:1 nos dois (AA).
    #[test]
    fn warn_and_danger_read_on_the_dialog_card_in_both_themes() {
        for tokens in [Tokens::light(), Tokens::dark()] {
            for (nome, cor) in [("aviso", tokens.warn), ("erro", tokens.danger)] {
                let ratio = contrast(cor, tokens.input);
                assert!(ratio >= 4.5, "{nome} no tema {}: {ratio:.2}:1", if tokens.dark { "escuro" } else { "claro" });
            }
        }
    }

    /// A linha marcada da seleção múltipla trava três coisas. O MODELO é o que a tela realmente faz:
    /// este app renderiza com blend em GAMA (framebuffer sem sRGB; o fragment devolve valores de tela),
    /// então overlay é mistura de canal em espaço gama com o alfa real — e NÃO `Rgba::blend` linear
    /// (a revisão anterior inverteu o modelo; o print medido provou: o lavado que existia media
    /// exatamente a mistura em gama). `.weak()` é `text_color().gamma_multiply(0.6)`, que multiplica
    /// também o ALFA — o subtítulo é um overlay a 60%, não uma cor opaca.
    /// (1) o fundo da linha marcada é IGUAL ao da lista (sem lavado em nenhum tema);
    /// (2) a barra e o contorno marcam ≥ 3:1 contra a base, nos dois temas (não-texto, WCAG);
    /// (3) o subtítulo fraco sobre a base tem o MESMO valor com a linha marcada e sem marca.
    #[test]
    fn the_selected_row_marks_itself_with_the_accent_without_touching_the_background() {
        for tokens in [Tokens::light(), Tokens::dark()] {
            let tema = if tokens.dark { "escuro" } else { "claro" };

            // (1) O fundo da linha marcada: o que o Frame pinta (fill transparente sobre a base).
            let fill = selected_row_fill(tokens.accent, tokens.dark);
            assert_eq!(fill, egui::Color32::TRANSPARENT, "fundo da linha marcada no tema {tema}: não é transparente");
            let fundo_marcado = {
                let alfa = f32::from(fill.a()) / 255.0;
                let canal = |s: u8, d: u8| (f32::from(s) * alfa + f32::from(d) * (1.0 - alfa)).round() as u8;
                egui::Color32::from_rgb(canal(fill.r(), tokens.base.r()), canal(fill.g(), tokens.base.g()), canal(fill.b(), tokens.base.b()))
            };
            assert_eq!(fundo_marcado, tokens.base, "fundo da linha marcada no tema {tema}: difere do fundo da lista");

            // (2) A barra e o contorno são opacos e precisam se destacar da base (não-texto ≥ 3:1).
            assert!(contrast(selected_row_bar(tokens.accent), tokens.base) >= 3.0, "barra da linha marcada no tema {tema}: contraste < 3:1");
            assert!(
                contrast(selected_row_stroke(tokens.accent).color, tokens.base) >= 3.0,
                "contorno da linha marcada no tema {tema}: contraste < 3:1"
            );

            // (3) O subtítulo fraco é um overlay: texto × 0,6 com alfa 153 sobre o fundo. Com a linha
            // marcada e sem marca o valor renderizado é O MESMO (a marca não mexe no fundo — se alguém
            // reintroduzir lavado, esta igualdade quebra).
            let legenda = |fundo: egui::Color32| {
                let src = tokens.text.gamma_multiply(0.6);
                let alfa = f32::from(src.a()) / 255.0;
                let canal = |s: u8, d: u8| (f32::from(s) * alfa + f32::from(d) * (1.0 - alfa)).round() as u8;
                egui::Color32::from_rgb(canal(src.r(), fundo.r()), canal(src.g(), fundo.g()), canal(src.b(), fundo.b()))
            };
            assert_eq!(
                legenda(fundo_marcado),
                legenda(tokens.base),
                "subtítulo fraco no tema {tema}: a linha marcada mudou o valor renderizado dele"
            );
        }
    }
}
