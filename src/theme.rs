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
    /// Texto secundário (legendas, rótulos pequenos e subtítulos), lido sobre a base, o cartão e o hover.
    pub muted: Color32,
    /// Hover/pressionado sobre superfícies.
    pub hover: Color32,
    pub pressed: Color32,
    /// Destaque de preenchimento, barra e links. O foco e a tinta do item marcado usam `accent_text`.
    pub accent: Color32,
    /// Tinta do texto SELECIONADO (linha/opção marcada) e dos anéis de foco, desenhada sobre `selection`.
    pub accent_text: Color32,
    /// Fundo de item selecionado (opções e a linha da paleta, que o repete × 0,35 sobre o cartão). Opaco.
    pub selection: Color32,
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
            // Secundário opaco: 6,7:1 na base, 7,5:1 no cartão, 6,0:1 no hover e 6,5:1 na linha da paleta.
            muted: Color32::from_rgb(0x4a, 0x58, 0x4f),
            hover: Color32::from_black_alpha(14),
            pressed: Color32::from_black_alpha(28),
            // 6,0:1 contra branco cru. O pill pinta ×0,9 (alfa 230) em repouso e ×1,12 no hover:
            // 4,85–4,98 com a tinta branca sobre o cartão/base. Sobre o hover dos chips: 4,8:1.
            accent: Color32::from_rgb(0x24, 0x71, 0x3f),
            // Tinta da opção marcada: 5,1:1 sobre a `selection` clara (o accent cheio dava 3,0:1).
            accent_text: Color32::from_rgb(0x1a, 0x5c, 0x34),
            // O antigo accent ×0,35 composto dava #b3d1c0 sobre o cartão e #abc9b6 sobre a base; este
            // token é #b9d5c3 (4–6 níveis mais claro no cartão, 12–14 na base), OPACO para não depender
            // do fundo.
            selection: Color32::from_rgb(0xb9, 0xd5, 0xc3),
            // Sobre o cartão do diálogo, que no claro é branco: 5,9:1 (aviso) e 7,3:1 (erro) — AA para texto pequeno.
            warn: Color32::from_rgb(0x8a, 0x5a, 0x00),
            // 5,2:1 também sobre o banner vermelho do exportar (o #b3261e dava 4,7:1, sem folga).
            danger: Color32::from_rgb(0xa8, 0x20, 0x19),
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
            // Secundário opaco: 7,0:1 na base, 7,5:1 no cartão, 5,9:1 no hover e 6,1:1 na linha da paleta.
            muted: Color32::from_rgb(0x9a, 0xa7, 0x9d),
            hover: Color32::from_white_alpha(16),
            pressed: Color32::from_white_alpha(30),
            accent: Color32::from_rgb(0x7c, 0xbf, 0x7f),
            // Tinta da opção marcada: 5,6:1 sobre a `selection` escura (o accent cheio dava 3,8:1).
            accent_text: Color32::from_rgb(0xa7, 0xd8, 0xaa),
            // O antigo accent ×0,35 composto sobre o cartão dava #344f36; este token é #354f37 (um
            // nível mais claro), OPACO.
            selection: Color32::from_rgb(0x35, 0x4f, 0x37),
            // Sobre o cartão do diálogo escuro (#0e120f): 8,6:1 (aviso) e 6,8:1 (erro).
            warn: Color32::from_rgb(0xe6, 0xa2, 0x3c),
            danger: Color32::from_rgb(0xe8, 0x7c, 0x7c),
            shadow: Color32::from_black_alpha(140),
        }
    }
}

/// Luminância perceptual (0–255) — decide a tinta sobre um preenchimento.
fn luminance255(c: Color32) -> f32 {
    0.299 * f32::from(c.r()) + 0.587 * f32::from(c.g()) + 0.114 * f32::from(c.b())
}

/// A tinta legível (preta ou branca) sobre `bg` — o pill primário usa isto em `Skin::on_accent`.
pub fn ink_on(bg: Color32) -> Color32 {
    if luminance255(bg) > 150.0 { Color32::BLACK } else { Color32::WHITE }
}

/// O preenchimento que `pill_colored` pinta num pill HABILITADO, antes de compor no fundo:
/// ×0,9 (alfa 230) em repouso e ×1,12 no hover.
pub fn pill_fill(fill: Color32, hovered: bool) -> Color32 {
    if hovered { fill.gamma_multiply(1.12) } else { fill.gamma_multiply(0.9) }
}

/// O fundo do aviso do exportar (vermelho translúcido sobre o cartão); a tinta por cima é `danger`.
/// É o vermelho que o aviso sempre teve; o pill destrutivo usa um tom mais escuro (`DANGER_PILL`)
/// porque a tinta dele é branca e passa pelo hover.
pub fn export_warning_fill() -> Color32 {
    Color32::from_rgb(0xC0, 0x3A, 0x3A).gamma_multiply(0.22)
}

/// O pill de ação destrutiva: o mesmo vermelho nos dois temas, com tinta branca.
pub const DANGER_PILL: Color32 = Color32::from_rgb(0xb1, 0x2f, 0x2f);
pub const ON_DANGER_PILL: Color32 = Color32::WHITE;

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
    v.selection.bg_fill = t.selection;
    v.selection.stroke = egui::Stroke::new(1.0, t.accent_text);
    v.hyperlink_color = t.accent;
    // Todo texto `.weak()` (legendas, rótulos pequenos, o grupo da paleta) sai daqui: um token só,
    // opaco e medido contra cada fundo em que aparece — nunca um `× 0,6` que muda com o fundo.
    v.weak_text_color = Some(t.muted);
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
/// o fundo sob o subtítulo `.small().weak()`. O subtítulo agora é o token `muted` OPACO, mas um lavado
/// ainda muda o FUNDO sob ele e pode baixar o contraste; sem lavado, a linha marcada tem exatamente o
/// fundo da lista.
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
    /// exatamente a mistura em gama). O subtítulo fraco é o token `muted` (OPACO, `weak_text_color`);
    /// o que um lavado mudaria é o FUNDO sob ele.
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

            // (3) O subtítulo fraco é o token `muted` OPACO (o `weak_text_color` do tema). Com a linha
            // marcada e sem marca o valor renderizado é O MESMO; se alguém reintroduzir lavado, o fundo
            // muda e (1) quebra (e o contraste do subtítulo é medido no teste de token).
            let legenda = |fundo: egui::Color32| over(visuals(&tokens).weak_text_color(), fundo);
            assert_eq!(
                legenda(fundo_marcado),
                legenda(tokens.base),
                "subtítulo fraco no tema {tema}: a linha marcada mudou o valor renderizado dele"
            );
        }
    }

    /// Compõe uma cor (possivelmente translúcida) sobre um fundo, como o egui desenha: os canais do
    /// `Color32` já são pré-multiplicados pelo alfa, então o resultado é `fg + bg × (1 − alfa)` em gama.
    fn over(fg: Color32, bg: Color32) -> Color32 {
        let alfa = f32::from(fg.a()) / 255.0;
        let canal = |s: u8, d: u8| (f32::from(s) + f32::from(d) * (1.0 - alfa)).round().clamp(0.0, 255.0) as u8;
        Color32::from_rgb(canal(fg.r(), bg.r()), canal(fg.g(), bg.g()), canal(fg.b(), bg.b()))
    }

    /// O teste cobre os pares de TEXTO HABILITADO que o app desenha, nos dois temas:
    /// - primário (`Visuals::text_color`) sobre painel, cartão, hover e `active` (o estado dos botões
    ///   do egui com foco), seleção e a linha selecionada da paleta;
    /// - secundário `.weak()` (`weak_text_color`) sobre painel/cartão, hover, `active` e a paleta;
    /// - rótulos "ghost" (`Skin.muted`) sobre painel/cartão e o hover deles — é a ligação `Skin` → tela;
    /// - accent (chips/links) sobre painel, hover e cartão; e a tinta do item marcado
    ///   (`selection.stroke.color`) sobre a seleção;
    /// - aviso/erro sobre cartão e campo, o erro sobre o banner do exportar, e o toast/aviso de job;
    /// - a tinta do pill primário e a do pill de perigo, em repouso e no hover, sobre painel e cartão.
    ///
    /// Fundos translúcidos são compostos como o egui desenha (gama, pré-multiplicado). As cores vêm de
    /// `visuals(&tokens)` e de `Skin::from_visuals`, nunca de cópias dos números: apagar a ligação de um
    /// token (ou trocar um campo do `Skin`) reprova o teste — exceto `selection.bg_fill`, cujo default
    /// do egui também passaria no limite.
    /// Fica de fora: texto DESABILITADO (a WCAG o dispensa; o pill usa `muted` sobre o fill ×0,45) e o
    /// conteúdo atrás do véu do modal (inativo/coberto). O prompt do atualizador é de `libs/` (craft-update).
    #[test]
    fn every_text_token_reads_on_the_surfaces_it_is_drawn_on() {
        let mut falhas = Vec::new();
        for tokens in [Tokens::light(), Tokens::dark()] {
            let tema = if tokens.dark { "escuro" } else { "claro" };
            let v = visuals(&tokens);
            let skin = crate::Skin::from_visuals(&v);
            let base = v.panel_fill;
            let card = skin.card;
            let hover_base = over(v.widgets.hovered.bg_fill, base);
            let hover_card = over(v.widgets.hovered.bg_fill, card);
            let active_base = over(v.widgets.active.bg_fill, base);
            let active_card = over(v.widgets.active.bg_fill, card);
            let selecao_na_base = over(v.selection.bg_fill, base);
            let selecao_no_cartao = over(v.selection.bg_fill, card);
            // A paleta (craft-palette) pinta a linha marcada com `selection.bg_fill × 0,35` sobre o cartão
            // (libs/craft-libs/crates/palette/src/lib.rs); o fator é cópia desse valor.
            let paleta = over(v.selection.bg_fill.gamma_multiply(0.35), card);
            let banner = over(export_warning_fill(), card);
            let text = v.text_color();
            // Tintas compostas sobre cada fundo: uma tinta translúcida seria medida contra o fundo errado.
            let weak = v.weak_text_color();
            let weak_base = over(weak, base);
            let weak_card = over(weak, card);
            let pill_rest_base = over(pill_fill(skin.accent, false), base);
            let pill_rest_card = over(pill_fill(skin.accent, false), card);
            let pill_hover_base = over(pill_fill(skin.accent, true), base);
            let pill_hover_card = over(pill_fill(skin.accent, true), card);
            let perigo_rest_base = over(pill_fill(DANGER_PILL, false), base);
            let perigo_rest_card = over(pill_fill(DANGER_PILL, false), card);
            let perigo_hover_base = over(pill_fill(DANGER_PILL, true), base);
            let perigo_hover_card = over(pill_fill(DANGER_PILL, true), card);
            let pares = [
                ("texto/base", text, base),
                ("texto/cartão", text, card),
                ("texto/hover (base)", text, hover_base),
                ("texto/hover (cartão)", text, hover_card),
                ("texto/active (base)", text, active_base),
                ("texto/active (cartão)", text, active_card),
                ("texto/seleção na base", text, selecao_na_base),
                ("texto/seleção no cartão", text, selecao_no_cartao),
                ("texto/paleta selecionada", text, paleta),
                ("Skin.text/cartão", skin.text, card),
                ("secundário/base", weak_base, base),
                ("secundário/cartão", weak_card, card),
                ("secundário/hover (base)", over(weak, hover_base), hover_base),
                ("secundário/hover (cartão)", over(weak, hover_card), hover_card),
                ("secundário/active (base)", over(weak, active_base), active_base),
                ("secundário/active (cartão)", over(weak, active_card), active_card),
                ("secundário/paleta selecionada", over(weak, paleta), paleta),
                ("Skin.muted/base", skin.muted, base),
                ("Skin.muted/cartão", skin.muted, card),
                ("Skin.muted/hover (base)", skin.muted, hover_base),
                ("Skin.muted/hover (cartão)", skin.muted, hover_card),
                ("Skin.muted/active (cartão)", skin.muted, active_card),
                ("accent/base", skin.accent, base),
                ("accent/hover", skin.accent, hover_base),
                ("accent/cartão", skin.accent, card),
                ("accent_text/seleção na base", v.selection.stroke.color, selecao_na_base),
                ("accent_text/seleção no cartão", v.selection.stroke.color, selecao_no_cartao),
                ("Skin.accent_text/seleção", skin.accent_text, selecao_no_cartao),
                ("aviso/cartão", skin.warn, card),
                ("aviso/campo (base)", skin.warn, skin.field),
                ("erro/cartão", skin.danger, card),
                ("erro/base", skin.danger, base),
                ("erro/banner do exportar", skin.danger, banner),
                ("pill primário repouso/base", skin.on_accent, pill_rest_base),
                ("pill primário repouso/cartão", skin.on_accent, pill_rest_card),
                ("pill primário hover/base", skin.on_accent, pill_hover_base),
                ("pill primário hover/cartão", skin.on_accent, pill_hover_card),
                ("pill de perigo repouso/base", ON_DANGER_PILL, perigo_rest_base),
                ("pill de perigo repouso/cartão", ON_DANGER_PILL, perigo_rest_card),
                ("pill de perigo hover/base", ON_DANGER_PILL, perigo_hover_base),
                ("pill de perigo hover/cartão", ON_DANGER_PILL, perigo_hover_card),
                ("toast/aviso de job", skin.inverted_text, skin.inverted_fill),
            ];
            for (par, fg, bg) in pares {
                let ratio = contrast(fg, bg);
                if ratio < 4.5 {
                    falhas.push(format!("{par} no tema {tema}: {ratio:.2}:1"));
                }
            }
        }
        assert!(falhas.is_empty(), "texto abaixo de 4,5:1 (WCAG AA):\n{}", falhas.join("\n"));
    }
}
