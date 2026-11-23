//! Filtros da lista — espelho do `VaultFilters.kt` do Android, que é a referência: um TIPO escolhido
//! (nenhum = todos) e qualquer combinação de "mostrar só" (com TOTP, com anexos, favoritos), somados
//! com E. Busca e pasta ficam de fora (continuam na UI, cada uma no seu lugar). O popup do desktop
//! (pedido 022, fase E) mostra exatamente isto.

use crate::vault::{ItemKind, ItemView};

/// O tipo escolhido no popup — um só; [`Self::All`] = sem filtro de tipo.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub enum KindFilter {
    #[default]
    All,
    Logins,
    Notes,
    Cards,
    Identities,
}

impl KindFilter {
    /// O `kind` de item que este filtro deixa passar.
    fn kind(self) -> Option<ItemKind> {
        match self {
            Self::All => None,
            Self::Logins => Some(ItemKind::Login),
            Self::Notes => Some(ItemKind::Note),
            Self::Cards => Some(ItemKind::Card),
            Self::Identities => Some(ItemKind::Identity),
        }
    }
}

/// "Mostrar só" — vários ao mesmo tempo, combinados com E.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub struct Only {
    pub totp: bool,
    pub attachments: bool,
    pub favorites: bool,
}

/// O estado dos filtros do popup (o `ListFilter` do Android, sem pasta).
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub struct Filters {
    pub kind: KindFilter,
    pub only: Only,
}

impl Filters {
    /// Quantos filtros estão ligados — o número do botão "Filtrar (n)".
    pub fn active_count(&self) -> usize {
        usize::from(self.kind != KindFilter::All)
            + usize::from(self.only.totp)
            + usize::from(self.only.attachments)
            + usize::from(self.only.favorites)
    }

    /// Nada ligado (o botão volta a ser só "Filtrar").
    pub fn is_clear(&self) -> bool {
        self.active_count() == 0
    }

    /// O "Limpar" do popup.
    pub fn clear(&mut self) {
        *self = Self::default();
    }

    /// Os filtros ligados, na ordem do popup — cada um vira um chip removível ao lado do botão.
    pub fn active_chips(&self) -> Vec<Chip> {
        let mut chips = Vec::with_capacity(self.active_count());
        if self.kind != KindFilter::All {
            chips.push(Chip::Kind(self.kind));
        }
        if self.only.totp {
            chips.push(Chip::Totp);
        }
        if self.only.attachments {
            chips.push(Chip::Attachments);
        }
        if self.only.favorites {
            chips.push(Chip::Favorites);
        }
        chips
    }

    /// O × de um chip: desliga só aquele filtro; os outros continuam como estão.
    pub fn remove(&mut self, chip: Chip) {
        match chip {
            Chip::Kind(_) => self.kind = KindFilter::All,
            Chip::Totp => self.only.totp = false,
            Chip::Attachments => self.only.attachments = false,
            Chip::Favorites => self.only.favorites = false,
        }
    }
}

/// Um filtro ligado, na ordem em que aparece no popup — a UI o desenha como chip com ×.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Chip {
    Kind(KindFilter),
    Totp,
    Attachments,
    Favorites,
}

/// O item passa nos filtros? Tipo (se escolhido) E cada "só" ligado — o `VaultFilters.visible` do
/// Android sem pasta e sem busca.
pub fn matches(filters: &Filters, item: &ItemView) -> bool {
    matches_kind(filters.kind, item)
        && (!filters.only.totp || item.totp.is_some())
        && (!filters.only.attachments || item.has_attachments)
        && (!filters.only.favorites || item.favorite)
}

/// A parte do tipo sozinha.
pub fn matches_kind(kind: KindFilter, item: &ItemView) -> bool {
    kind.kind().is_none_or(|k| item.kind == k)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Um item com o que importa para os filtros.
    fn item(kind: ItemKind, totp: bool, attachments: bool, favorite: bool) -> ItemView {
        ItemView {
            id: String::new(),
            name: String::new(),
            kind,
            summary: None,
            username: None,
            totp: totp.then(|| "JBSWY3DPEHPK3PXP".to_string()),
            favorite,
            folder_id: None,
            has_attachments: attachments,
        }
    }

    #[test]
    fn sem_filtros_tudo_passa() {
        let f = Filters::default();
        assert!(f.is_clear());
        for (kind, ok) in [(ItemKind::Login, true), (ItemKind::Note, true), (ItemKind::Card, true), (ItemKind::Identity, true)] {
            assert_eq!(matches(&f, &item(kind, false, false, false)), ok);
        }
    }

    #[test]
    fn tipo_so_deixa_o_seu() {
        let f = Filters { kind: KindFilter::Cards, ..Default::default() };
        assert!(!matches(&f, &item(ItemKind::Login, false, false, false)));
        assert!(matches(&f, &item(ItemKind::Card, false, false, false)));
        assert!(!matches(&f, &item(ItemKind::Note, false, false, false)));
        let f = Filters { kind: KindFilter::Logins, ..Default::default() };
        assert!(matches(&f, &item(ItemKind::Login, false, false, false)));
        assert!(!matches(&f, &item(ItemKind::Identity, false, false, false)));
    }

    #[test]
    fn combinacoes_somam_com_e() {
        // tipo + TOTP
        let f = Filters { kind: KindFilter::Logins, only: Only { totp: true, ..Default::default() } };
        assert!(matches(&f, &item(ItemKind::Login, true, false, false)));
        assert!(!matches(&f, &item(ItemKind::Login, false, false, false)), "sem TOTP não passa");
        assert!(!matches(&f, &item(ItemKind::Card, true, false, false)), "fora do tipo não passa");
        // tipo + favoritos
        let f = Filters { kind: KindFilter::Notes, only: Only { favorites: true, ..Default::default() } };
        assert!(matches(&f, &item(ItemKind::Note, false, false, true)));
        assert!(!matches(&f, &item(ItemKind::Note, false, false, false)));
        // TOTP + anexos juntos
        let f = Filters { only: Only { totp: true, attachments: true, ..Default::default() }, ..Default::default() };
        assert!(matches(&f, &item(ItemKind::Login, true, true, false)));
        assert!(!matches(&f, &item(ItemKind::Login, true, false, false)), "falta anexo");
        // os três
        let f = Filters { kind: KindFilter::All, only: Only { totp: true, attachments: true, favorites: true } };
        assert!(matches(&f, &item(ItemKind::Card, true, true, true)));
        assert!(!matches(&f, &item(ItemKind::Card, true, true, false)));
    }

    #[test]
    fn contagem_do_botao_e_limpar() {
        let mut f = Filters::default();
        assert_eq!(f.active_count(), 0, "nada ligado");
        f.kind = KindFilter::Cards;
        assert_eq!(f.active_count(), 1, "só o tipo conta 1 (não 4 opções)");
        f.only.totp = true;
        assert_eq!(f.active_count(), 2);
        f.only.attachments = true;
        f.only.favorites = true;
        assert_eq!(f.active_count(), 4);
        f.kind = KindFilter::All;
        assert_eq!(f.active_count(), 3, "tipo em All não conta");
        assert!(!f.is_clear());
        f.clear();
        assert!(f.is_clear());
        assert_eq!(f.active_count(), 0);
    }

    #[test]
    fn chips_na_ordem_do_popup_e_remover_um_so() {
        let mut f = Filters { kind: KindFilter::Cards, only: Only { totp: true, attachments: true, favorites: true } };
        assert_eq!(f.active_chips(), vec![Chip::Kind(KindFilter::Cards), Chip::Totp, Chip::Attachments, Chip::Favorites]);
        assert_eq!(f.active_chips().len(), f.active_count(), "um chip por filtro ligado");
        f.remove(Chip::Kind(KindFilter::Cards));
        assert_eq!(f.kind, KindFilter::All);
        assert!(f.only.totp && f.only.attachments && f.only.favorites, "os outros filtros ficam");
        assert_eq!(f.active_count(), 3, "a contagem do botão cai");
        f.remove(Chip::Totp);
        assert_eq!(f.active_chips(), vec![Chip::Attachments, Chip::Favorites]);
        assert!(Filters::default().active_chips().is_empty(), "sem filtro, sem chip");
    }
}
